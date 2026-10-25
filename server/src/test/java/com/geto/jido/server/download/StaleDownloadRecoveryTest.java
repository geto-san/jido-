package com.geto.jido.server.download;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class StaleDownloadRecoveryTest {

    private static final Duration LEASE = Duration.ofHours(2);

    @Autowired
    private DownloadRepository downloads;

    @Autowired
    private DownloadWorker worker;

    @Autowired
    private EntityManager em;

    @Autowired
    private com.geto.jido.server.user.UserRepository users;

    private UUID newUser() {
        // downloads.user_id is a real foreign key, so the owner has to exist.
        return users.saveAndFlush(
                new com.geto.jido.server.user.User(
                        "dl-" + UUID.randomUUID() + "@example.com", "hash", null))
                .getId();
    }

    @Test
    @Transactional
    void requeuesJobsOrphanedMidFlight() {
        Download stuck = new Download(newUser(), "https://youtu.be/stale", "youtube", null);
        stuck.markDownloading();
        UUID id = downloads.saveAndFlush(stuck).getId();
        backdate(id, Duration.ofHours(3));

        int requeued = worker.recoverOrphaned(Instant.now().minus(LEASE));

        assertThat(requeued).isEqualTo(1);
        assertThat(downloads.findById(id).orElseThrow().getStatus())
                .isEqualTo(DownloadStatus.PENDING);
    }

    @Test
    @Transactional
    void leavesRecentJobsAlone() {
        Download running =
                new Download(newUser(), "https://youtu.be/running", "youtube", null);
        running.markDownloading();
        UUID id = downloads.saveAndFlush(running).getId();

        // A live worker is mid-transfer. Sweeping this would make two workers
        // download the same job and race on the same object key.
        assertThat(worker.recoverOrphaned(Instant.now().minus(LEASE))).isZero();
        assertThat(downloads.findById(id).orElseThrow().getStatus())
                .isEqualTo(DownloadStatus.DOWNLOADING);
    }

    @Test
    @Transactional
    void ignoresPendingJobs() {
        UUID id = downloads.saveAndFlush(
                new Download(newUser(), "https://youtu.be/pending", "youtube", null)).getId();
        backdate(id, Duration.ofHours(3));

        // A pending job is the queue's normal state, not an orphan.
        assertThat(downloads.findById(id).orElseThrow().getStatus())
                .isEqualTo(DownloadStatus.PENDING);
        assertThat(worker.recoverOrphaned(Instant.now().minus(LEASE))).isZero();
    }

    @Test
    @Transactional
    void ignoresFinishedJobs() {
        Download finished =
                new Download(newUser(), "https://youtu.be/done", "youtube", null);
        finished.markCompleted("users/x/a.mp4", "a.mp4", 10);
        UUID id = downloads.saveAndFlush(finished).getId();
        backdate(id, Duration.ofHours(3));

        assertThat(worker.recoverOrphaned(Instant.now().minus(LEASE))).isZero();
        assertThat(downloads.findById(id).orElseThrow().getStatus())
                .isEqualTo(DownloadStatus.COMPLETED);
    }

    private void backdate(UUID id, Duration age) {
        em.flush();
        em.clear();
        em.createNativeQuery("""
                UPDATE downloads
                   SET last_progress_at = :when, started_at = :when
                 WHERE id = :id
                """)
                .setParameter("when", Instant.now().minus(age))
                .setParameter("id", id)
                .executeUpdate();
        em.clear();
    }
}