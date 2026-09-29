package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoverageServiceTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    private static final RuleRow DAILY = new RuleRow(1, "daily", true, 2, 7, "");

    @Test void defaultPoliciesShowSevenDaysTwelveMonthsAndEveryElapsedYearEnd() {
        DatabaseRow database = database(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 1, 4));
        assertEquals(7, CoverageService.dueDates(DAILY, database, TODAY).size());
        assertEquals(12, CoverageService.dueDates(new RuleRow(2, "monthly", false, 2, 365, ""), database, TODAY).size());
        assertEquals(List.of(LocalDate.of(2022, 12, 31), LocalDate.of(2023, 12, 31),
            LocalDate.of(2024, 12, 31), LocalDate.of(2025, 12, 31)),
            CoverageService.dueDates(new RuleRow(3, "yearly", false, 2, null, ""), database, TODAY));
    }

    @Test void changedRetentionIsNotSilentlyCappedAndPermanentStartsAtDatabaseCreation() {
        DatabaseRow database = database(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1));
        assertEquals(400, CoverageService.dueDates(new RuleRow(1, "daily", true, 2, 400, ""), database, TODAY).size());
        assertEquals(48, CoverageService.dueDates(new RuleRow(2, "monthly", false, 2, 1461, ""), database, TODAY).size());
        assertEquals(LocalDate.of(2022, 1, 1), CoverageService.dueDates(new RuleRow(1, "daily", true, 2, null, ""), database, TODAY).getFirst());
        assertEquals(LocalDate.of(2022, 12, 31), CoverageService.dueDates(new RuleRow(3, "yearly", false, 2, null, ""), database, TODAY).getFirst());
        assertEquals(1, CoverageService.dueDates(new RuleRow(3, "yearly", false, 2, 365, ""), database, TODAY).size());
    }

    @Test void recentDatabaseHasNoBoxesBeforeCreationAndFutureDatabaseHasNone() {
        DatabaseRow recent = database(TODAY.minusYears(1), TODAY.minusDays(1));
        assertEquals(List.of(TODAY.minusDays(1), TODAY), CoverageService.dueDates(DAILY, recent, TODAY));
        assertEquals(List.of(), CoverageService.dueDates(DAILY, database(TODAY.plusDays(1), null), TODAY));
    }

    @Test void nextDaySuccessDoesNotHideThePreviousDaysGap() {
        LocalDate first = LocalDate.of(2026, 9, 1);
        BackupRow secondDay = backup(1, "daily", first.plusDays(1), "successed", false);
        List<CoverageSlot> slots = CoverageService.evaluate(DAILY, List.of(first, first.plusDays(1)), TODAY, true, List.of(secondDay));
        assertEquals(List.of("missing", "ok"), slots.stream().map(CoverageSlot::state).toList());
        assertEquals(secondDay.id(), slots.get(1).record().id());
    }

    @Test void runningIsPendingThroughTheDeadlineAndMissingAfterIt() {
        LocalDate due = LocalDate.of(2026, 9, 1);
        BackupRow running = backup(1, "daily", due, "DISPATCHING", false);
        assertEquals("running", CoverageService.evaluate(DAILY, List.of(due), due.plusDays(2), true, List.of(running)).getFirst().state());
        assertEquals("missing", CoverageService.evaluate(DAILY, List.of(due), due.plusDays(3), true, List.of(running)).getFirst().state());
    }

    @Test void inferredDateIsUnknownForOnlyOneDayAndUnavailableSourceIsUnverified() {
        LocalDate first = LocalDate.of(2026, 9, 1);
        BackupRow inferred = backup(1, "daily", first.plusDays(1), "successed", true);
        assertEquals(List.of("missing", "unknown"), CoverageService.evaluate(DAILY,
            List.of(first, first.plusDays(1)), TODAY, true, List.of(inferred)).stream().map(CoverageSlot::state).toList());
        assertEquals("unverified", CoverageService.evaluate(DAILY, List.of(first), TODAY, false, List.of()).getFirst().state());
    }

    @Test void monthlyAndYearlyLateBackupsAreAcceptedOnlyWithinGrace() {
        RuleRow monthly = new RuleRow(2, "monthly", true, 2, 365, "");
        LocalDate monthDue = LocalDate.of(2026, 9, 1);
        assertEquals("ok", CoverageService.evaluate(monthly, List.of(monthDue), TODAY, true,
            List.of(backup(1, "monthly", monthDue.plusDays(2), "successed", false))).getFirst().state());
        assertEquals("missing", CoverageService.evaluate(monthly, List.of(monthDue), TODAY, true,
            List.of(backup(1, "monthly", monthDue.plusDays(3), "successed", false))).getFirst().state());
        RuleRow yearly = new RuleRow(3, "yearly", true, 2, null, "");
        LocalDate yearDue = LocalDate.of(2025, 12, 31);
        assertEquals("ok", CoverageService.evaluate(yearly, List.of(yearDue), TODAY, true,
            List.of(backup(2, "yearly", yearDue.plusDays(2), "successed", false))).getFirst().state());
    }

    @Test void aggregateCountsAndPolicyFieldsMatchTheActualSlots() {
        CatalogRepository repository = mock(CatalogRepository.class);
        when(repository.rules()).thenReturn(List.of(DAILY));
        when(repository.backupsForChecks(anyLong(), eq("daily"), any(), any())).thenReturn(List.of(
            backup(1, "daily", TODAY.minusDays(6), "successed", false),
            backup(2, "daily", TODAY.minusDays(5), "cancel", false),
            backup(3, "daily", TODAY.minusDays(4), "successed", true),
            backup(4, "daily", TODAY.minusDays(3), "DISPATCHING", false),
            backup(5, "daily", TODAY, "DISPATCHING", false)));
        CoverageGroup group = new CoverageService(repository).coverage(database(TODAY.minusYears(1), null), TODAY).getFirst();
        assertEquals(7, group.expectedCount());
        assertEquals(1, group.presentCount());
        assertEquals(2, group.missingCount());
        assertEquals(3, group.pendingCount());
        assertEquals(1, group.unknownCount());
        assertEquals(group.expectedCount(), group.presentCount() + group.missingCount() + group.pendingCount() + group.unknownCount());
        assertEquals(7, group.retentionDays());
        assertEquals(2, group.graceDays());
        assertFalse(group.slots().isEmpty());
    }

    private static DatabaseRow database(LocalDate monitorFrom, LocalDate createdOn) {
        return new DatabaseRow(1, "DEMO_test", monitorFrom, true, 0, null,
            null, null, null, null, null, null, null, null, null, null, createdOn);
    }

    private static BackupRow backup(long id, String kind, LocalDate date, String status, boolean inferred) {
        return new BackupRow(id, 1, "DEMO_test", kind, "test:" + id, date, inferred,
            Instant.parse("2026-09-24T00:00:00Z").plusSeconds(id), status);
    }
}
