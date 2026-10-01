package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

class BackupRetentionServiceTests {
    @Test
    void policyUsesInclusiveRetentionWindowAndNeverExpiresYearlyBackups() {
        RuleMapper rules = mock(RuleMapper.class);
        when(rules.findAll()).thenReturn(Arrays.asList(
            new RuleRow(1L, "daily", true, 2, 7, ""),
            new RuleRow(2L, "monthly", true, 2, 365, ""),
            new RuleRow(3L, "yearly", true, 2, null, "")));
        BackupRetentionService.RetentionPolicy policy =
            new BackupRetentionService(mock(BackupMapper.class), rules).currentPolicy();
        LocalDate today = LocalDate.of(2026, 10, 1);

        assertTrue(policy.shouldKeep("daily", today.minusDays(6), today));
        assertFalse(policy.shouldKeep("daily", today.minusDays(7), today));
        assertTrue(policy.shouldKeep("monthly", today.minusDays(364), today));
        assertFalse(policy.shouldKeep("monthly", today.minusDays(365), today));
        assertTrue(policy.shouldKeep("yearly", LocalDate.of(2000, 12, 31), today));
    }

    @Test
    void invalidOrMissingRetentionValuesUseSafeDefaults() {
        RuleMapper rules = mock(RuleMapper.class);
        when(rules.findAll()).thenReturn(Arrays.asList(
            new RuleRow(1L, "daily", true, 2, null, ""),
            new RuleRow(2L, "monthly", true, 2, 0, "")));
        BackupRetentionService.RetentionPolicy policy =
            new BackupRetentionService(mock(BackupMapper.class), rules).currentPolicy();
        LocalDate today = LocalDate.of(2026, 10, 1);

        assertFalse(policy.shouldKeep("daily", today.minusDays(7), today));
        assertFalse(policy.shouldKeep("monthly", today.minusDays(365), today));
    }
}

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:retention_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-user=retention_admin",
    "app.admin-password=test-password-123",
    "app.demo-seed=false"
})
@ActiveProfiles("local")
class BackupRetentionPersistenceTests {
    @Autowired CatalogRepository catalog;
    @Autowired BackupRetentionService retention;
    @Autowired TaskScheduler taskScheduler;

    @Test
    void schedulerCanStartAssetAndDailySyncAtTheSameTime() {
        assertTrue(taskScheduler instanceof ThreadPoolTaskScheduler);
        assertEquals(4, ((ThreadPoolTaskScheduler) taskScheduler).getPoolSize());
    }

    @Test
    @Transactional
    void cleanupDeletesOnlyExpiredDailyAndMonthlyMetadata() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        LocalDate dailyCutoff = today.minusDays(6);
        LocalDate monthlyCutoff = today.minusDays(364);
        long databaseId = catalog.addDatabase("retention_policy_test_db", LocalDate.of(2000, 1, 1)).id();
        Instant eventTime = Instant.parse("2026-10-01T00:00:00Z");
        add(databaseId, "daily", "retention-daily-expired", dailyCutoff.minusDays(1), eventTime);
        add(databaseId, "daily", "retention-daily-boundary", dailyCutoff, eventTime);
        add(databaseId, "monthly", "retention-monthly-expired", monthlyCutoff.minusDays(1), eventTime);
        add(databaseId, "monthly", "retention-monthly-boundary", monthlyCutoff, eventTime);
        add(databaseId, "yearly", "retention-yearly-old", LocalDate.of(2000, 12, 31), eventTime);

        assertEquals(1, retention.cleanupDaily(today));
        assertFalse(catalog.hasBackup("daily", "retention-daily-expired"));
        assertTrue(catalog.hasBackup("daily", "retention-daily-boundary"));
        assertTrue(catalog.hasBackup("monthly", "retention-monthly-expired"));
        assertTrue(catalog.hasBackup("yearly", "retention-yearly-old"));

        assertEquals(1, retention.cleanupMonthly(today));
        assertFalse(catalog.hasBackup("monthly", "retention-monthly-expired"));
        assertTrue(catalog.hasBackup("monthly", "retention-monthly-boundary"));
        assertTrue(catalog.hasBackup("yearly", "retention-yearly-old"));
    }

    private void add(long databaseId, String kind, String externalId, LocalDate backupDate, Instant eventTime) {
        catalog.upsertBackup(databaseId, kind, externalId, backupDate, false, eventTime,
            "successed", "{}");
    }
}
