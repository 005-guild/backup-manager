package com.example.backupmanager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final DatabaseMapper databases;
    private final BackupMapper backups;
    private final RuleMapper rules;
    private final SyncRunMapper syncRuns;
    private final UserMapper users;

    CatalogRepository(DatabaseMapper databases, BackupMapper backups, RuleMapper rules,
                      SyncRunMapper syncRuns, UserMapper users) {
        this.databases = databases;
        this.backups = backups;
        this.rules = rules;
        this.syncRuns = syncRuns;
        this.users = users;
    }

    List<DatabaseRow> databases(String search) {
        return databases.findAll("%" + search + "%");
    }
    DatabaseRow database(long id) {
        return databases.findById(id);
    }
    DatabaseRow addDatabase(String name, LocalDate monitorFrom) {
        if (databaseIdByName(name) == null) {
            Map<String, Object> values = new HashMap<>();
            values.put("name", name);
            values.put("monitorFrom", monitorFrom);
            values.put("monitorFromAuto", false);
            try { databases.insert(values); }
            catch (DuplicateKeyException ignored) { /* another request added it */ }
        }
        Long id = databaseIdByName(name);
        if (id == null) throw new IllegalStateException("数据库登记失败");
        return database(id);
    }
    long getOrCreateDatabase(String name, LocalDate firstDate) {
        if (databaseIdByName(name) == null) {
            Map<String, Object> values = new HashMap<>();
            values.put("name", name);
            values.put("monitorFrom", firstDate);
            values.put("monitorFromAuto", true);
            try { databases.insert(values); }
            catch (DuplicateKeyException ignored) { /* another request added it */ }
        }
        databases.moveAutomaticMonitorStart(name, firstDate);
        Long id = databaseIdByName(name);
        if (id == null) throw new IllegalStateException("数据库自动登记失败");
        return id;
    }
    private Long databaseIdByName(String name) {
        return databases.findIdByName(name);
    }
    DatabaseRow updateMetadata(long id, DatabaseMetadata metadata) {
        databases.updateMetadata(id, metadata);
        return database(id);
    }

    void setDemoMonitoring(long id, String name, LocalDate monitorFrom) {
        databases.setDemoMonitoring(id, name, monitorFrom);
    }

    List<BackupRow> backups(Long databaseId, LocalDate from, LocalDate to, String kind, String status, int limit, int offset) {
        return backups.find(databaseId, from, to, kind, status, limit, offset);
    }
    List<BackupRow> backupsForChecks(long databaseId, String kind, LocalDate from, LocalDate to) {
        return backups.findForChecks(databaseId, kind, from, to);
    }
    List<Map<String,Object>> calendar(LocalDate first, LocalDate last) {
        return backups.calendar(first, last);
    }
    long backupCount() { return backups.countAll(); }
    List<RuleRow> rules() { return rules.findAll(); }
    void updateRule(long id, boolean enabled, int graceDays, Integer retentionDays) {
        rules.update(id, enabled, graceDays, retentionDays);
    }
    List<SyncRow> syncRuns(int limit) { return syncRuns.findRecent(limit); }
    long startSync(String kind) {
        SyncRunInsert command = new SyncRunInsert(kind);
        syncRuns.insert(command);
        if (command.getId() == null) throw new IllegalStateException("无法取得同步记录主键");
        return command.getId();
    }
    void finishSync(long id, String status, int fetched, int saved, String error) {
        syncRuns.finish(id, status, fetched, saved, error);
    }
    boolean hasBackup(String kind, String externalId) {
        return backups.countBySource(kind, externalId) > 0;
    }
    void upsertBackup(long databaseId, String kind, String externalId, LocalDate backupDate, boolean inferred,
                      Instant eventTime, String status, String raw) {
        BackupWrite record = new BackupWrite(databaseId, kind, externalId, backupDate, inferred, eventTime, status, raw);
        if (backups.update(record) > 0) return;
        try {
            backups.insert(record);
        } catch (DuplicateKeyException ignored) {
            backups.update(record);
        }
    }
    List<Map<String,Object>> users() { return users.findAll(); }
    void addUser(String username, String passwordHash, String role) {
        users.insert(username, passwordHash, role);
    }
}
