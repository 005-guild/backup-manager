package com.example.backupmanager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class BackupRetentionService {
    private static final Logger log = LoggerFactory.getLogger(BackupRetentionService.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_DAILY_DAYS = 7;
    private static final int DEFAULT_MONTHLY_DAYS = 365;

    private final BackupMapper backups;
    private final RuleMapper rules;
    @Value("${app.scheduler-enabled:false}") private boolean schedulerEnabled;

    BackupRetentionService(BackupMapper backups, RuleMapper rules) {
        this.backups = backups;
        this.rules = rules;
    }

    RetentionPolicy currentPolicy() {
        int dailyDays = DEFAULT_DAILY_DAYS;
        int monthlyDays = DEFAULT_MONTHLY_DAYS;
        List<RuleRow> configured = rules.findAll();
        for (RuleRow rule : configured) {
            if ("daily".equals(rule.kind())) {
                dailyDays = positiveOrDefault(rule.retentionDays(), DEFAULT_DAILY_DAYS);
            } else if ("monthly".equals(rule.kind())) {
                monthlyDays = positiveOrDefault(rule.retentionDays(), DEFAULT_MONTHLY_DAYS);
            }
        }
        return new RetentionPolicy(dailyDays, monthlyDays);
    }

    boolean shouldKeep(String kind, LocalDate backupDate, LocalDate today) {
        return currentPolicy().shouldKeep(kind, backupDate, today);
    }

    int cleanupDaily(LocalDate today) {
        LocalDate cutoff = currentPolicy().cutoff("daily", today);
        int deleted = backups.deleteOlderThan("daily", cutoff);
        log.info("Daily backup metadata cleanup removed {} records older than {}", deleted, cutoff);
        return deleted;
    }

    int cleanupMonthly(LocalDate today) {
        LocalDate cutoff = currentPolicy().cutoff("monthly", today);
        int deleted = backups.deleteOlderThan("monthly", cutoff);
        log.info("Monthly backup metadata cleanup removed {} records older than {}", deleted, cutoff);
        return deleted;
    }

    @Scheduled(cron = "${app.daily-cleanup-cron:0 0 2 * * SUN}", zone = "Asia/Shanghai")
    void scheduledDailyCleanup() {
        if (schedulerEnabled) cleanupDaily(LocalDate.now(SHANGHAI));
    }

    @Scheduled(cron = "${app.monthly-cleanup-cron:0 0 3 1 1 *}", zone = "Asia/Shanghai")
    void scheduledMonthlyCleanup() {
        if (schedulerEnabled) cleanupMonthly(LocalDate.now(SHANGHAI));
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    static final class RetentionPolicy {
        private final int dailyDays;
        private final int monthlyDays;

        RetentionPolicy(int dailyDays, int monthlyDays) {
            this.dailyDays = dailyDays;
            this.monthlyDays = monthlyDays;
        }

        boolean shouldKeep(String kind, LocalDate backupDate, LocalDate today) {
            LocalDate cutoff = cutoff(kind, today);
            return cutoff == null || !backupDate.isBefore(cutoff);
        }

        LocalDate cutoff(String kind, LocalDate today) {
            if ("daily".equals(kind)) return today.minusDays(dailyDays - 1L);
            if ("monthly".equals(kind)) return today.minusDays(monthlyDays - 1L);
            return null;
        }
    }
}
