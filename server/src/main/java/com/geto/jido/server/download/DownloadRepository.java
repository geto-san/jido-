package com.geto.jido.server.download;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DownloadRepository extends JpaRepository<Download, UUID> {

    /**
     * Scoped by user on every read. A download id is a UUID, which is
     * unguessable but not secret — leaving this off would mean any
     * authenticated user could read any other user's history by id.
     */
    Optional<Download> findByIdAndUserId(UUID id, UUID userId);

    List<Download> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Download> findByUserIdAndClientRef(UUID userId, String clientRef);

    /**
     * The worker's claim query. Uses {@code FOR UPDATE SKIP LOCKED} so two
     * workers never take the same row — without SKIP LOCKED, a second worker
     * blocks on the lock and then processes a job the first already finished.
     */
    @Query(value = """
            SELECT * FROM downloads
            WHERE status = 'PENDING'
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """, nativeQuery = true)
    List<Download> claimPending(@Param("limit") int limit);

    long countByUserIdAndStatusIn(UUID userId, List<DownloadStatus> statuses);

    /**
     * Jobs left mid-flight by a crash or restart. Nothing transitions these out
     * of RESOLVING/DOWNLOADING on its own, so without this sweep they would sit
     * active forever and never be re-claimed.
     *
     * <p>lastProgressAt is the reliable clock: it is written during transfer,
     * whereas created_at/started_at stay fixed once the job begins.
     */
    @Query("""
            SELECT d.id FROM Download d
            WHERE d.status IN :statuses
              AND COALESCE(d.lastProgressAt, d.startedAt, d.createdAt) < :cutoff
            """)
    List<UUID> findStaleIds(
            @Param("statuses") List<DownloadStatus> statuses, @Param("cutoff") java.time.Instant cutoff);

    /**
     * Requeues stale jobs as PENDING so the worker picks them up again.
     * Deliberately a bulk update: these rows are untouched by any live worker,
     * because a worker only ever holds a claim it marked within its own lease.
     */
    /**
     * Written as native SQL with an explicit enum cast. JPQL renders the
     * parameter as an untyped Postgres enum name ("PENDING"::downloadstatus),
     * which does not exist — the column type is lowercase {@code download_status}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE downloads
               SET status = CAST('PENDING' AS download_status),
                   error_detail = :detail
             WHERE id IN (:ids)
            """, nativeQuery = true)
    int requeueStale(
            @Param("ids") List<UUID> ids,
            @Param("detail") String detail);
}
