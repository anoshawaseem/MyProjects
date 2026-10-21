package com.micomm.tenantmgmt.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.micomm.tenantmgmt.onboarding.TenantOnboardingService.PartitionMaintenanceResult;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps every tenant schema's monthly partitions ahead of time and applies
 * retention. Runs once at startup and then on {@code micomm.partitioning.cron}.
 *
 * Inserts into a month with no partition fail, so this job (or apply-all)
 * must run at least once every {@code months-ahead} months.
 */
@Component
public class TenantPartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(TenantPartitionMaintenanceJob.class);

    private final TenantDatabaseRepository tenantDatabaseRepository;
    private final TenantOnboardingService onboardingService;
    private final int monthsAhead;
    private final int retentionMonths;

    public TenantPartitionMaintenanceJob(TenantDatabaseRepository tenantDatabaseRepository,
            TenantOnboardingService onboardingService,
            @Value("${micomm.partitioning.months-ahead:3}") int monthsAhead,
            @Value("${micomm.partitioning.retention-months:0}") int retentionMonths) {
        this.tenantDatabaseRepository = tenantDatabaseRepository;
        this.onboardingService = onboardingService;
        this.monthsAhead = monthsAhead;
        this.retentionMonths = retentionMonths;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    @Scheduled(cron = "${micomm.partitioning.cron:0 0 2 * * *}")
    public void run() {
        // Several products share one club schema, so maintain each schema once.
        List<String> schemas = tenantDatabaseRepository.findAll().stream()
                .filter(t -> "ACTIVE".equals(t.getStatus()))
                .map(TenantDatabase::getDatabaseIdentifier)
                .distinct()
                .toList();

        Map<PartitionMaintenanceResult, Integer> outcomes = new EnumMap<>(PartitionMaintenanceResult.class);
        int failed = 0;
        for (String schema : schemas) {
            try {
                PartitionMaintenanceResult result =
                        onboardingService.maintainPartitions(schema, monthsAhead, retentionMonths);
                outcomes.merge(result, 1, Integer::sum);
                if (result == PartitionMaintenanceResult.SCHEMA_MISSING) {
                    log.warn("Skipping partition maintenance for [{}]: schema does not exist", schema);
                } else if (result == PartitionMaintenanceResult.NOT_PARTITIONED) {
                    log.warn("Skipping partition maintenance for [{}]: not partitioned yet, "
                            + "run /admin/tenant-migrations/apply-all", schema);
                }
            } catch (Exception ex) {
                failed++;
                log.error("Partition maintenance failed for [{}]", schema, ex);
            }
        }
        log.info("Partition maintenance done: {} maintained, {} not partitioned, {} missing, {} failed "
                        + "(months-ahead={}, retention-months={})",
                outcomes.getOrDefault(PartitionMaintenanceResult.MAINTAINED, 0),
                outcomes.getOrDefault(PartitionMaintenanceResult.NOT_PARTITIONED, 0),
                outcomes.getOrDefault(PartitionMaintenanceResult.SCHEMA_MISSING, 0),
                failed, monthsAhead, retentionMonths == 0 ? "off" : retentionMonths);
    }
}
