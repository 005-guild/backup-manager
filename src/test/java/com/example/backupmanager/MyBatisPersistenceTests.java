package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:mybatis_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-user=test_admin",
    "app.admin-password=test-password-123",
    "app.demo-seed=false"
})
@ActiveProfiles("local")
class MyBatisPersistenceTests {
    @Autowired CatalogRepository catalog;
    @Autowired UserMapper users;

    @Test
    void mapperXmlSupportsTheCompletePersistenceFlow() {
        LocalDate monitorFrom = LocalDate.of(2026, 1, 1);
        DatabaseRow created = catalog.addDatabase("mybatis_test_db", monitorFrom);
        assertEquals(monitorFrom, created.monitorFrom());
        assertEquals(0, created.backupCount());
        assertNull(created.latestEvent());

        DatabaseRow metadata = catalog.updateMetadata(created.id(), new DatabaseMetadata(
            "DBID-MYBATIS-1", null, "已上线", "A", "ZA24", "8.0.24",
            "TEST", null, "DBA-TEST", "UNIT-TEST", LocalDate.of(2025, 12, 1)));
        assertEquals("DBID-MYBATIS-1", metadata.dbid());
        assertNull(metadata.tag());
        assertNull(metadata.developer());

        Instant firstEvent = Instant.parse("2026-09-01T02:00:00Z");
        catalog.upsertBackup(created.id(), "daily", "MYBATIS:1", LocalDate.of(2026, 9, 1),
            false, firstEvent, "successed", "{\"source\":\"test\"}");
        assertTrue(catalog.hasBackup("daily", "MYBATIS:1"));
        assertEquals(1, catalog.backupCount());

        List<BackupRow> filtered = catalog.backups(created.id(), LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 1), "daily", "SUCCESSed", 10, 0);
        assertEquals(1, filtered.size());
        assertEquals("successed", filtered.getFirst().status());

        catalog.upsertBackup(created.id(), "daily", "MYBATIS:1", LocalDate.of(2026, 9, 1),
            false, firstEvent.plusSeconds(60), "cancel", "{\"source\":\"updated\"}");
        assertEquals(1, catalog.backupCount());
        assertEquals("cancel", catalog.backups(created.id(), null, null, "", "", 10, 0).getFirst().status());

        DatabaseRow summarized = catalog.database(created.id());
        assertEquals(1, summarized.backupCount());
        assertNotNull(summarized.latestEvent());
        List<Map<String, Object>> calendar = catalog.calendar(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertEquals(1, calendar.size());
        assertEquals(LocalDate.of(2026, 9, 1), calendar.getFirst().get("backup_date"));
        assertEquals(1L, ((Number) calendar.getFirst().get("backup_count")).longValue());

        long runId = catalog.startSync("daily");
        assertTrue(runId > 0);
        catalog.finishSync(runId, "success", 1, 1, "");
        assertEquals("success", catalog.syncRuns(1).getFirst().status());

        catalog.addUser("mybatis_viewer", "{noop}test-password", "VIEWER");
        UserAccount account = users.findAccount("mybatis_viewer");
        assertNotNull(account);
        assertEquals("VIEWER", account.role());
        assertTrue(account.enabled());
        assertFalse(catalog.users().isEmpty());

        assertEquals(List.of("daily", "monthly", "yearly"),
            catalog.rules().stream().map(RuleRow::kind).toList());
    }
}
