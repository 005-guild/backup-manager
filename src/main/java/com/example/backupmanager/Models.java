package com.example.backupmanager;

import java.time.Instant;
import java.time.LocalDate;

record DatabaseRow(long id, String name, LocalDate monitorFrom, boolean active, long backupCount, Instant latestEvent,
                   String dbid, String tag, String lifecycleStatus, String securityTier, String framework,
                   String dbVersion, String subsystem, String developer, String dba, String serviceUnit,
                   LocalDate createdOn) {}
record DatabaseMetadata(String dbid, String tag, String lifecycleStatus, String securityTier, String framework,
                        String dbVersion, String subsystem, String developer, String dba, String serviceUnit,
                        LocalDate createdOn) {}
record BackupRow(long id, long databaseId, String databaseName, String kind, String externalId,
                 LocalDate backupDate, boolean dateInferred, Instant eventTime, String status) {}
record RuleRow(long id, String kind, boolean enabled, int graceDays, Integer retentionDays, String note) {}
record SyncRow(long id, String kind, Instant startedAt, Instant finishedAt, String status,
               int fetchedCount, int savedCount, String error) {}
record CheckRow(LocalDate due, String kind, String state, BackupRow record) {}
record CoverageSlot(LocalDate due, String state, String reason, BackupRow record) {}
record CoverageGroup(String kind, boolean sourceConnected, Integer retentionDays, int graceDays, int expectedCount, int presentCount,
                     int missingCount, int pendingCount, int unknownCount, java.util.List<CoverageSlot> slots) {}
record UserAccount(String username, String passwordHash, String role, boolean enabled) {}
record BackupWrite(long databaseId, String kind, String externalId, LocalDate backupDate,
                   boolean dateInferred, Instant eventTime, String status, String rawData) {}

final class SyncRunInsert {
    private Long id;
    private final String kind;

    SyncRunInsert(String kind) { this.kind = kind; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
}
