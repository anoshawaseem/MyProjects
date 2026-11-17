package com.micomm.tenantmgmt.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Forgets used assertions once they have expired anyway (they could no longer be replayed). */
@Component
public class UsedAssertionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(UsedAssertionCleanupJob.class);

    private final OAuthClientRepository repository;

    public UsedAssertionCleanupJob(OAuthClientRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void cleanUp() {
        int deleted = repository.deleteExpiredAssertions();
        if (deleted > 0) {
            log.info("Deleted {} expired OAuth assertion records", deleted);
        }
    }
}
