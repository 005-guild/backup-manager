package com.example.backupmanager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class DashboardService {
    private static final int DISPLAY_LIMIT = 50;
    private final CatalogRepository catalog;

    DashboardService(CatalogRepository catalog) { this.catalog = catalog; }

    Map<String, Object> summary(LocalDate today) {
        LocalDate from = today.minusDays(6);
        List<DatabaseRow> databases = catalog.dashboardDatabases();
        List<RuleRow> rules = new ArrayList<>();
        int maxGrace = 0;
        for (RuleRow rule : catalog.rules()) {
            if (!rule.enabled()) continue;
            rules.add(rule);
            maxGrace = Math.max(maxGrace, rule.graceDays());
        }
        Map<Long, Map<String, List<BackupRow>>> records = new HashMap<>();
        for (BackupRow record : catalog.dashboardBackups(from, today.plusDays(maxGrace))) {
            Map<String, List<BackupRow>> byKind = records.get(record.databaseId());
            if (byKind == null) {
                byKind = new HashMap<>();
                records.put(record.databaseId(), byKind);
            }
            List<BackupRow> group = byKind.get(record.kind());
            if (group == null) {
                group = new ArrayList<>();
                byKind.put(record.kind(), group);
            }
            group.add(record);
        }

        long missing = 0, pending = 0, unknown = 0;
        List<Score> scored = new ArrayList<>(databases.size());
        for (DatabaseRow database : databases) {
            long dbMissing = 0, dbPending = 0, dbUnknown = 0;
            Map<String, List<BackupRow>> byKind = records.get(database.id());
            for (RuleRow rule : rules) {
                LocalDate start = from.isBefore(database.monitorFrom()) ? database.monitorFrom() : from;
                if (database.createdOn() != null && start.isBefore(database.createdOn())) start = database.createdOn();
                if (start.isAfter(today)) continue;
                List<BackupRow> evidence = byKind == null ? Collections.<BackupRow>emptyList() :
                    byKind.getOrDefault(rule.kind(), Collections.<BackupRow>emptyList());
                for (CheckRow check : RuleService.evaluate(rule, start, today, today, evidence)) {
                    switch (check.state()) {
                        case "missing": dbMissing++; break;
                        case "pending": dbPending++; break;
                        case "unknown": dbUnknown++; break;
                        default: break;
                    }
                }
            }
            missing += dbMissing;
            pending += dbPending;
            unknown += dbUnknown;
            scored.add(new Score(database, dbMissing, dbPending, dbUnknown));
        }
        scored.sort(Comparator.comparingLong(Score::missing).reversed()
            .thenComparing(score -> score.database().name())
            .thenComparingLong(score -> score.database().id()));
        List<Score> top = scored.subList(0, Math.min(scored.size(), DISPLAY_LIMIT));
        List<Long> ids = new ArrayList<>(top.size());
        for (Score score : top) ids.add(score.database().id());
        Map<Long, BackupRow> latest = new HashMap<>();
        for (BackupRow record : catalog.latestBackups(ids)) latest.put(record.databaseId(), record);
        List<Map<String, Object>> rows = new ArrayList<>(top.size());
        for (Score score : top) {
            BackupRow record = latest.get(score.database().id());
            rows.add(Compat.mapOf("database", score.database(),
                "latest", record == null ? Compat.mapOf() : record,
                "missing", score.missing(), "pending", score.pending(), "unknown", score.unknown()));
        }
        List<SyncRow> runs = catalog.syncRuns(1);
        return Compat.mapOf("databaseCount", databases.size(), "backupCount", catalog.backupCount(),
            "missing", missing, "pending", pending, "unknown", unknown,
            "databases", rows, "displayLimit", DISPLAY_LIMIT,
            "lastSync", runs.isEmpty() ? Compat.mapOf() : runs.get(0));
    }

    private static final class Score {
        private final DatabaseRow database;
        private final long missing, pending, unknown;
        Score(DatabaseRow database, long missing, long pending, long unknown) {
            this.database = database;
            this.missing = missing;
            this.pending = pending;
            this.unknown = unknown;
        }
        DatabaseRow database() { return database; }
        long missing() { return missing; }
        long pending() { return pending; }
        long unknown() { return unknown; }
    }
}
