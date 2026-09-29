package com.example.backupmanager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RuleService {
    private final CatalogRepository catalog;
    RuleService(CatalogRepository catalog) { this.catalog = catalog; }

    List<CheckRow> checks(DatabaseRow database, LocalDate from, LocalDate to, LocalDate today) {
        List<CheckRow> all = new ArrayList<>();
        for (RuleRow rule : catalog.rules()) {
            if (!rule.enabled()) continue;
            LocalDate start = from.isBefore(database.monitorFrom()) ? database.monitorFrom() : from;
            if (database.createdOn() != null && start.isBefore(database.createdOn())) start = database.createdOn();
            if (start.isAfter(to)) continue;
            List<BackupRow> records = catalog.backupsForChecks(database.id(), rule.kind(), start, to.plusDays(rule.graceDays()));
            all.addAll(evaluate(rule, start, to, today, records));
        }
        all.sort(Comparator.comparing(CheckRow::due).reversed().thenComparing(CheckRow::kind));
        return all;
    }

    static List<CheckRow> evaluate(RuleRow rule, LocalDate from, LocalDate to, LocalDate today, List<BackupRow> records) {
        List<LocalDate> dueDates = new ArrayList<>();
        for (LocalDate due = from; !due.isAfter(to); due = due.plusDays(1)) {
            if (CoverageService.isDue(rule.kind(), due)) dueDates.add(due);
        }
        return CoverageService.evaluate(rule, dueDates, today, true, records).stream().map(slot -> {
            String state;
            switch (slot.state()) {
                case "failed":
                    state = "missing";
                    break;
                case "running":
                    state = "pending";
                    break;
                case "unverified":
                    state = "unknown";
                    break;
                default:
                    state = slot.state();
                    break;
            }
            return new CheckRow(slot.due(), rule.kind(), state, slot.record());
        }).collect(Collectors.toList());
    }
}
