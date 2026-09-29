package com.example.backupmanager;

import static com.example.backupmanager.Compat.listOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RuleServiceTests {
    private final RuleRow daily = new RuleRow(1, "daily", true, 2, 7, "");

    @Test void aBackupCoversItsOwnScheduledDay() {
        BackupRow record = new BackupRow(1, 1, "db", "daily", "one", LocalDate.of(2026, 9, 2), false, Instant.now(), "successed");
        List<CheckRow> checks = RuleService.evaluate(daily, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 6), listOf(record));
        assertEquals(listOf("missing", "ok"), checks.stream().map(CheckRow::state).collect(Collectors.toList()));
    }

    @Test void inferredUpdateTimeDoesNotProveCompliance() {
        BackupRow record = new BackupRow(1, 1, "db", "daily", "one", LocalDate.of(2026, 9, 1), true, Instant.now(), "successed");
        List<CheckRow> checks = RuleService.evaluate(daily, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), listOf(record));
        assertEquals("unknown", checks.get(0).state());
    }

    @Test void missingStartsAfterTwoDayGrace() {
        List<CheckRow> checks = RuleService.evaluate(daily, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 4), listOf());
        assertEquals(listOf("missing", "pending"), checks.stream().map(CheckRow::state).collect(Collectors.toList()));
    }
}
