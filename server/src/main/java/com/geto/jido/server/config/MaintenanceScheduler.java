package com.geto.jido.server.config;

import com.geto.jido.server.auth.RefreshTokenRepository;
import com.geto.jido.server.resolve.ResolveService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic housekeeping so neither cache grows without bound.
 *
 * <p>Both jobs are idempotent and safe to run concurrently on multiple
 * instances; they do not need a distributed lock.
 */
@Component
public class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);
    private static final Duration EXPIRED_TOKEN_GRACE = Duration.ofDays(7);

    private final ResolveService resolveService;
    private final RefreshTokenRepository refreshTokens;

    public MaintenanceScheduler(ResolveService resolveService, RefreshTokenRepository refreshTokens) {
        this.resolveService = resolveService;
        this.refreshTokens = refreshTokens;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void evictResolveCache() {
        int before = resolveService.cacheSize();
        resolveService.evictExpired();
        int after = resolveService.cacheSize();
        if (before != after) {
            log.debug("Evicted {} expired resolve cache entries", before - after);
        }
    }

    /**
     * Deletes tokens that expired more than a week ago. The grace period keeps
     * recently-expired rows around long enough to explain a failed refresh.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = refreshTokens.deleteExpiredBefore(Instant.now().minus(EXPIRED_TOKEN_GRACE));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }
}
