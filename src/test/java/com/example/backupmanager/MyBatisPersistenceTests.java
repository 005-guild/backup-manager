package com.example.backupmanager;

import static com.example.backupmanager.Compat.listOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
    @Autowired DbaasAssetRepository assetRepository;
    @Autowired DbaasAssetMapper assetMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void snapshotRebindsSameNameToSurvivingAssetAndProjectsItsMetadata() {
        String name = "dbaas_rebind_same_name_test";
        LocalDate monitorFrom = LocalDate.of(2026, 9, 30);
        DbaasAsset first = snapshotAsset("rebind-asset-a", name, "4.4");
        DbaasAsset second = snapshotAsset("rebind-asset-b", name, "5.0");

        assetRepository.upsert(first, monitorFrom, 8101L);
        assetRepository.upsert(second, monitorFrom, 8101L);
        assetRepository.reconcileSnapshot(8101L);
        DatabaseRow row = catalog.databases(name).stream()
            .filter(item -> name.equals(item.name())).findFirst().get();
        assertEquals("rebind-asset-a", jdbc.queryForObject(
            "select asset_external_id from database_catalog where id = ?", String.class, row.id()));
        assertEquals("4.4", catalog.database(row.id()).dbVersion());

        assetRepository.upsert(second, monitorFrom, 8102L);
        assetRepository.reconcileSnapshot(8102L);

        DatabaseRow rebound = catalog.database(row.id());
        assertTrue(rebound.active());
        assertEquals("rebind-asset-b", jdbc.queryForObject(
            "select asset_external_id from database_catalog where id = ?", String.class, row.id()));
        assertEquals("5.0", rebound.dbVersion());
        assertEquals("N", jdbc.queryForObject(
            "select valid from dbaas_asset where external_id = ?", String.class, "rebind-asset-a"));
        assertEquals("Y", jdbc.queryForObject(
            "select valid from dbaas_asset where external_id = ?", String.class, "rebind-asset-b"));
        assertEquals(1L, jdbc.queryForObject(
            "select count(*) from database_catalog where name = ?", Long.class, name).longValue());
    }

    @Test
    @Transactional
    void assetRenameDeactivatesOldCatalogNameAndActivatesNewName() {
        String externalId = "renamed-asset-test";
        String oldName = "dbaas_rename_old_test";
        String newName = "dbaas_rename_new_test";
        LocalDate monitorFrom = LocalDate.of(2026, 9, 30);

        assetRepository.upsert(snapshotAsset(externalId, oldName, "4.4"), monitorFrom, 8201L);
        assetRepository.reconcileSnapshot(8201L);
        DatabaseRow oldRow = catalog.databases(oldName).stream()
            .filter(item -> oldName.equals(item.name())).findFirst().get();
        assertTrue(oldRow.active());

        assetRepository.upsert(snapshotAsset(externalId, newName, "5.0"), monitorFrom, 8202L);
        assetRepository.reconcileSnapshot(8202L);

        DatabaseRow newRow = catalog.databases(newName).stream()
            .filter(item -> newName.equals(item.name())).findFirst().get();
        assertFalse(catalog.database(oldRow.id()).active());
        assertTrue(catalog.database(newRow.id()).active());
        assertEquals("5.0", catalog.database(newRow.id()).dbVersion());
        assertEquals(newName, assetMapper.findByExternalId(externalId).getDbName());
    }

    private static DbaasAsset snapshotAsset(String externalId, String name, String version) {
        String raw = "{\"ID\":\"" + externalId + "\",\"DBNAME\":\"" + name
            + "\",\"VALID\":\"Y\",\"DBVERSION\":\"" + version + "\"}";
        return new DbaasAsset(externalId, name, name + "_13578", 13578L, "Y", raw,
            new DatabaseMetadata("DBID-" + name, null, "Y", "D", "ZA21", version,
                "LX05.01", null, null, null, LocalDate.of(2026, 3, 3)));
    }

    @Test
    @Transactional
    void fullAssetSnapshotDeactivatesMissingAssetsAndReappearanceRestoresThem() {
        String externalId = "external-dbaas-snapshot-test";
        String name = "dbaas_snapshot_test_db";
        DbaasAsset asset = new DbaasAsset(externalId, name, name + "_13578", 13578L,
            "Y", "{\"VALID\":\"Y\"}", new DatabaseMetadata("DBID-SNAPSHOT", null, "Y",
                "D", "ZA21", "4.4", "LX05.01", null, null, null, LocalDate.of(2026, 3, 3)));

        assertTrue(assetRepository.upsert(asset, LocalDate.of(2026, 9, 30), 7001L));
        DatabaseRow row = catalog.databases(name).stream()
            .filter(item -> name.equals(item.name())).findFirst().get();
        assetRepository.reconcileSnapshot(7001L);
        assertTrue(catalog.database(row.id()).active());
        assertEquals("Y", jdbc.queryForObject(
            "select valid from dbaas_asset where external_id = ?", String.class, externalId));

        assetRepository.reconcileSnapshot(7002L);
        assertFalse(catalog.database(row.id()).active());
        assertEquals("N", jdbc.queryForObject(
            "select valid from dbaas_asset where external_id = ?", String.class, externalId));

        assertFalse(assetRepository.upsert(asset, LocalDate.of(2026, 9, 30), 7003L));
        assertTrue(catalog.database(row.id()).active());
        assertEquals("Y", jdbc.queryForObject(
            "select valid from dbaas_asset where external_id = ?", String.class, externalId));
    }

    @Test
    void dbaasAssetsPersistRawDataAndProjectMetadataIdempotently() {
        String name = "dbaas_asset_projection_test";
        LocalDate monitorFrom = LocalDate.of(2026, 9, 30);
        long before = assetMapper.countAll();
        DbaasAsset first = new DbaasAsset("external-dbaas-asset-test", name,
            "dbaas_asset_projection_test_13578", 13578L, "Y", "{\"DBVERSION\":\"4.4\"}",
            new DatabaseMetadata("DBID-ASSET-TEST", null, "Y", "D", "ZA21", "4.4",
                "LX05.01", "test developer", "test dba", "test unit", LocalDate.of(2026, 3, 3)));

        assertTrue(assetRepository.upsert(first, monitorFrom));
        assertEquals(before + 1, assetMapper.countAll());
        DbaasAssetRecord persisted = assetMapper.findByExternalId("external-dbaas-asset-test");
        assertNotNull(persisted);
        assertEquals(name, persisted.getDbName());
        assertEquals("{\"DBVERSION\":\"4.4\"}", persisted.getRawData());

        DatabaseRow catalogRow = catalog.databases(name).stream()
            .filter(row -> name.equals(row.name())).findFirst().get();
        assertEquals(monitorFrom, catalogRow.monitorFrom());
        assertEquals("DBID-ASSET-TEST", catalogRow.dbid());
        assertEquals("4.4", catalogRow.dbVersion());
        assertEquals("test dba", catalogRow.dba());

        DbaasAsset updated = new DbaasAsset("external-dbaas-asset-test", name,
            "dbaas_asset_projection_test_13578", 13578L, "Y", "{\"DBVERSION\":\"4.5\"}",
            new DatabaseMetadata("DBID-ASSET-TEST", null, "Y", "D", "ZA21", "4.5",
                "LX05.01", "test developer", "test dba", "test unit", LocalDate.of(2026, 3, 3)));

        assertFalse(assetRepository.upsert(updated, monitorFrom));
        assertEquals(before + 1, assetMapper.countAll());
        assertEquals("{\"DBVERSION\":\"4.5\"}",
            assetMapper.findByExternalId("external-dbaas-asset-test").getRawData());
        assertEquals("4.5", catalog.database(catalogRow.id()).dbVersion());
    }

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
        assertEquals("successed", filtered.get(0).status());

        catalog.upsertBackup(created.id(), "daily", "MYBATIS:1", LocalDate.of(2026, 9, 1),
            false, firstEvent.plusSeconds(60), "cancel", "{\"source\":\"updated\"}");
        assertEquals(1, catalog.backupCount());
        assertEquals("cancel", catalog.backups(created.id(), null, null, "", "", 10, 0).get(0).status());

        DatabaseRow summarized = catalog.database(created.id());
        assertEquals(1, summarized.backupCount());
        assertNotNull(summarized.latestEvent());
        List<Map<String, Object>> calendar = catalog.calendar(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertEquals(1, calendar.size());
        assertEquals(LocalDate.of(2026, 9, 1), calendar.get(0).get("backup_date"));
        assertEquals(1L, ((Number) calendar.get(0).get("backup_count")).longValue());

        long runId = catalog.startSync("daily");
        assertTrue(runId > 0);
        catalog.finishSync(runId, "success", 1, 1, "");
        assertEquals("success", catalog.syncRuns(1).get(0).status());

        catalog.addUser("mybatis_viewer", "{noop}test-password", "VIEWER");
        UserAccount account = users.findAccount("mybatis_viewer");
        assertNotNull(account);
        assertEquals("VIEWER", account.role());
        assertTrue(account.enabled());
        assertFalse(catalog.users().isEmpty());

        assertEquals(listOf("daily", "monthly", "yearly"),
            catalog.rules().stream().map(RuleRow::kind).collect(Collectors.toList()));
    }
}
