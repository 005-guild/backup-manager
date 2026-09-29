package com.example.backupmanager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CoverageService {
    private final CatalogRepository catalog;
    CoverageService(CatalogRepository catalog) { this.catalog = catalog; }

    List<CoverageGroup> coverage(DatabaseRow database) {
        return coverage(database, LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    List<CoverageGroup> coverage(DatabaseRow database, LocalDate today) {
        List<CoverageGroup> groups = new ArrayList<>();
        for (RuleRow rule : catalog.rules()) {
            List<LocalDate> dueDates = dueDates(rule, database, today);
            boolean sourceConnected = rule.enabled() || database.name().startsWith("DEMO_");
            if (dueDates.isEmpty()) {
                groups.add(new CoverageGroup(rule.kind(), sourceConnected, rule.retentionDays(), rule.graceDays(), 0, 0, 0, 0, 0, List.of()));
                continue;
            }
            LocalDate first = dueDates.get(0);
            LocalDate last = dueDates.get(dueDates.size() - 1);
            List<BackupRow> records = catalog.backupsForChecks(database.id(), rule.kind(), first, last.plusDays(rule.graceDays()));
            List<CoverageSlot> slots = evaluate(rule, dueDates, today, sourceConnected, records);
            int present = 0, missing = 0, pending = 0, unknown = 0;
            for (CoverageSlot slot : slots) {
                switch (slot.state()) {
                    case "ok" -> present++;
                    case "missing", "failed" -> missing++;
                    case "pending", "running" -> pending++;
                    default -> unknown++;
                }
            }
            groups.add(new CoverageGroup(rule.kind(), sourceConnected, rule.retentionDays(), rule.graceDays(), slots.size(), present, missing, pending, unknown, slots));
        }
        return groups;
    }

    static List<CoverageSlot> evaluate(RuleRow rule, List<LocalDate> dueDates, LocalDate today,
                                      boolean sourceConnected, List<BackupRow> records) {
        Set<Long> used = new HashSet<>();
        List<CoverageSlot> slots = new ArrayList<>();
        Comparator<BackupRow> byDate = Comparator.comparing(BackupRow::backupDate)
            .thenComparing(BackupRow::eventTime).thenComparingLong(BackupRow::id);
        for (LocalDate due : dueDates) {
            LocalDate deadline = due.plusDays(rule.graceDays());
            List<BackupRow> candidates = records.stream()
                .filter(record -> !used.contains(record.id()) && record.kind().equals(rule.kind()))
                .filter(record -> !record.backupDate().isBefore(due) && !record.backupDate().isAfter(deadline))
                // A dated backup belongs to its own scheduled day. Never borrow tomorrow's
                // daily backup to hide today's gap; monthly/yearly off-schedule dates may be late completions.
                .filter(record -> record.backupDate().equals(due) || !isDue(rule.kind(), record.backupDate()))
                .toList();
            BackupRow evidence = candidates.stream()
                .filter(record -> record.status().equalsIgnoreCase("successed") && !record.dateInferred())
                .min(byDate).orElse(null);
            boolean confirmed = evidence != null;
            if (evidence == null) evidence = candidates.stream()
                .filter(record -> record.status().equalsIgnoreCase("successed"))
                .min(byDate).orElse(null);
            boolean inferred = evidence != null && !confirmed;
            if (evidence == null) evidence = candidates.stream()
                .filter(record -> record.status().equalsIgnoreCase("cancel") || record.status().equalsIgnoreCase("dispatching"))
                .max(Comparator.comparing(BackupRow::eventTime).thenComparingLong(BackupRow::id)).orElse(null);
            if (evidence != null) used.add(evidence.id());

            String state, reason;
            if (confirmed) { state = "ok"; reason = "已有成功备份"; }
            else if (inferred) { state = "unknown"; reason = "成功记录的备份日期待核对"; }
            else if (!sourceConnected) { state = "unverified"; reason = "该类型规则尚未启用，暂不判断是否缺失"; }
            else if (evidence != null && evidence.status().equalsIgnoreCase("cancel")) {
                state = "failed"; reason = "备份取消或失败";
            } else if (today.isAfter(deadline)) {
                state = "missing"; reason = evidence == null ? "没有成功备份" : "已超过允许完成时间，仍未成功";
            } else if (evidence != null) { state = "running"; reason = "备份进行中"; }
            else { state = "pending"; reason = "仍在允许完成时间内"; }
            slots.add(new CoverageSlot(due, state, reason, evidence));
        }
        return slots;
    }

    static boolean isDue(String kind, LocalDate date) {
        return switch (kind) {
            case "daily" -> true;
            case "monthly" -> date.getDayOfMonth() == 1;
            case "yearly" -> date.getMonthValue() == 12 && date.getDayOfMonth() == 31;
            default -> false;
        };
    }

    static List<LocalDate> dueDates(RuleRow rule, DatabaseRow database, LocalDate today) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate start = database.monitorFrom();
        if (database.createdOn() != null && database.createdOn().isAfter(start)) start = database.createdOn();
        if (rule.retentionDays() != null) {
            LocalDate retainedFrom = today.minusDays(rule.retentionDays() - 1L);
            if (retainedFrom.isAfter(start)) start = retainedFrom;
        }
        if (start.isAfter(today)) return dates;
        switch (rule.kind()) {
            case "daily" -> {
                for (LocalDate due = start; !due.isAfter(today); due = due.plusDays(1)) dates.add(due);
            }
            case "monthly" -> {
                LocalDate due = YearMonth.from(start).atDay(1);
                if (due.isBefore(start)) due = due.plusMonths(1);
                for (; !due.isAfter(today); due = due.plusMonths(1)) dates.add(due);
            }
            case "yearly" -> {
                for (int year = start.getYear(); year <= today.getYear(); year++) {
                    LocalDate due = LocalDate.of(year, 12, 31);
                    if (!due.isBefore(start) && !due.isAfter(today)) dates.add(due);
                }
            }
            default -> { }
        }
        return dates;
    }
}
