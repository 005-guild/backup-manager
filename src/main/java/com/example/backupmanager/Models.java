package com.example.backupmanager;

import java.time.Instant;
import java.time.LocalDate;

final class DatabaseRow {
    private final long id;
    private final String name;
    private final LocalDate monitorFrom;
    private final boolean active;
    private final long backupCount;
    private final Instant latestEvent;
    private final String dbid;
    private final String tag;
    private final String lifecycleStatus;
    private final String securityTier;
    private final String framework;
    private final String dbVersion;
    private final String subsystem;
    private final String developer;
    private final String dba;
    private final String serviceUnit;
    private final LocalDate createdOn;

    DatabaseRow(long id, String name, LocalDate monitorFrom, boolean active, long backupCount,
                Instant latestEvent, String dbid, String tag, String lifecycleStatus,
                String securityTier, String framework, String dbVersion, String subsystem,
                String developer, String dba, String serviceUnit, LocalDate createdOn) {
        this.id = id;
        this.name = name;
        this.monitorFrom = monitorFrom;
        this.active = active;
        this.backupCount = backupCount;
        this.latestEvent = latestEvent;
        this.dbid = dbid;
        this.tag = tag;
        this.lifecycleStatus = lifecycleStatus;
        this.securityTier = securityTier;
        this.framework = framework;
        this.dbVersion = dbVersion;
        this.subsystem = subsystem;
        this.developer = developer;
        this.dba = dba;
        this.serviceUnit = serviceUnit;
        this.createdOn = createdOn;
    }

    public long id() { return id; }
    public String name() { return name; }
    public LocalDate monitorFrom() { return monitorFrom; }
    public boolean active() { return active; }
    public long backupCount() { return backupCount; }
    public Instant latestEvent() { return latestEvent; }
    public String dbid() { return dbid; }
    public String tag() { return tag; }
    public String lifecycleStatus() { return lifecycleStatus; }
    public String securityTier() { return securityTier; }
    public String framework() { return framework; }
    public String dbVersion() { return dbVersion; }
    public String subsystem() { return subsystem; }
    public String developer() { return developer; }
    public String dba() { return dba; }
    public String serviceUnit() { return serviceUnit; }
    public LocalDate createdOn() { return createdOn; }
    public long getId() { return id; }
    public String getName() { return name; }
    public LocalDate getMonitorFrom() { return monitorFrom; }
    public boolean isActive() { return active; }
    public long getBackupCount() { return backupCount; }
    public Instant getLatestEvent() { return latestEvent; }
    public String getDbid() { return dbid; }
    public String getTag() { return tag; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public String getSecurityTier() { return securityTier; }
    public String getFramework() { return framework; }
    public String getDbVersion() { return dbVersion; }
    public String getSubsystem() { return subsystem; }
    public String getDeveloper() { return developer; }
    public String getDba() { return dba; }
    public String getServiceUnit() { return serviceUnit; }
    public LocalDate getCreatedOn() { return createdOn; }
}

final class DatabaseMetadata {
    private String dbid;
    private String tag;
    private String lifecycleStatus;
    private String securityTier;
    private String framework;
    private String dbVersion;
    private String subsystem;
    private String developer;
    private String dba;
    private String serviceUnit;
    private LocalDate createdOn;

    DatabaseMetadata() {}
    DatabaseMetadata(String dbid, String tag, String lifecycleStatus, String securityTier,
                     String framework, String dbVersion, String subsystem, String developer,
                     String dba, String serviceUnit, LocalDate createdOn) {
        this.dbid = dbid;
        this.tag = tag;
        this.lifecycleStatus = lifecycleStatus;
        this.securityTier = securityTier;
        this.framework = framework;
        this.dbVersion = dbVersion;
        this.subsystem = subsystem;
        this.developer = developer;
        this.dba = dba;
        this.serviceUnit = serviceUnit;
        this.createdOn = createdOn;
    }

    public String dbid() { return dbid; }
    public String tag() { return tag; }
    public String lifecycleStatus() { return lifecycleStatus; }
    public String securityTier() { return securityTier; }
    public String framework() { return framework; }
    public String dbVersion() { return dbVersion; }
    public String subsystem() { return subsystem; }
    public String developer() { return developer; }
    public String dba() { return dba; }
    public String serviceUnit() { return serviceUnit; }
    public LocalDate createdOn() { return createdOn; }
    public String getDbid() { return dbid; }
    public String getTag() { return tag; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public String getSecurityTier() { return securityTier; }
    public String getFramework() { return framework; }
    public String getDbVersion() { return dbVersion; }
    public String getSubsystem() { return subsystem; }
    public String getDeveloper() { return developer; }
    public String getDba() { return dba; }
    public String getServiceUnit() { return serviceUnit; }
    public LocalDate getCreatedOn() { return createdOn; }
    public void setDbid(String dbid) { this.dbid = dbid; }
    public void setTag(String tag) { this.tag = tag; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public void setSecurityTier(String securityTier) { this.securityTier = securityTier; }
    public void setFramework(String framework) { this.framework = framework; }
    public void setDbVersion(String dbVersion) { this.dbVersion = dbVersion; }
    public void setSubsystem(String subsystem) { this.subsystem = subsystem; }
    public void setDeveloper(String developer) { this.developer = developer; }
    public void setDba(String dba) { this.dba = dba; }
    public void setServiceUnit(String serviceUnit) { this.serviceUnit = serviceUnit; }
    public void setCreatedOn(LocalDate createdOn) { this.createdOn = createdOn; }
}

final class BackupRow {
    private final long id;
    private final long databaseId;
    private final String databaseName;
    private final String kind;
    private final String externalId;
    private final LocalDate backupDate;
    private final boolean dateInferred;
    private final Instant eventTime;
    private final String status;

    BackupRow(long id, long databaseId, String databaseName, String kind, String externalId,
              LocalDate backupDate, boolean dateInferred, Instant eventTime, String status) {
        this.id = id;
        this.databaseId = databaseId;
        this.databaseName = databaseName;
        this.kind = kind;
        this.externalId = externalId;
        this.backupDate = backupDate;
        this.dateInferred = dateInferred;
        this.eventTime = eventTime;
        this.status = status;
    }

    public long id() { return id; }
    public long databaseId() { return databaseId; }
    public String databaseName() { return databaseName; }
    public String kind() { return kind; }
    public String externalId() { return externalId; }
    public LocalDate backupDate() { return backupDate; }
    public boolean dateInferred() { return dateInferred; }
    public Instant eventTime() { return eventTime; }
    public String status() { return status; }
    public long getId() { return id; }
    public long getDatabaseId() { return databaseId; }
    public String getDatabaseName() { return databaseName; }
    public String getKind() { return kind; }
    public String getExternalId() { return externalId; }
    public LocalDate getBackupDate() { return backupDate; }
    public boolean isDateInferred() { return dateInferred; }
    public Instant getEventTime() { return eventTime; }
    public String getStatus() { return status; }
}

final class RuleRow {
    private final long id;
    private final String kind;
    private final boolean enabled;
    private final int graceDays;
    private final Integer retentionDays;
    private final String note;

    RuleRow(long id, String kind, boolean enabled, int graceDays, Integer retentionDays, String note) {
        this.id = id;
        this.kind = kind;
        this.enabled = enabled;
        this.graceDays = graceDays;
        this.retentionDays = retentionDays;
        this.note = note;
    }
    public long id() { return id; }
    public String kind() { return kind; }
    public boolean enabled() { return enabled; }
    public int graceDays() { return graceDays; }
    public Integer retentionDays() { return retentionDays; }
    public String note() { return note; }
    public long getId() { return id; }
    public String getKind() { return kind; }
    public boolean isEnabled() { return enabled; }
    public int getGraceDays() { return graceDays; }
    public Integer getRetentionDays() { return retentionDays; }
    public String getNote() { return note; }
}

final class SyncRow {
    private final long id;
    private final String kind;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final String status;
    private final int fetchedCount;
    private final int savedCount;
    private final String error;

    SyncRow(long id, String kind, Instant startedAt, Instant finishedAt, String status,
            int fetchedCount, int savedCount, String error) {
        this.id = id;
        this.kind = kind;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.status = status;
        this.fetchedCount = fetchedCount;
        this.savedCount = savedCount;
        this.error = error;
    }
    public long id() { return id; }
    public String kind() { return kind; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public String status() { return status; }
    public int fetchedCount() { return fetchedCount; }
    public int savedCount() { return savedCount; }
    public String error() { return error; }
    public long getId() { return id; }
    public String getKind() { return kind; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getStatus() { return status; }
    public int getFetchedCount() { return fetchedCount; }
    public int getSavedCount() { return savedCount; }
    public String getError() { return error; }
}

final class CheckRow {
    private final LocalDate due;
    private final String kind;
    private final String state;
    private final BackupRow record;
    CheckRow(LocalDate due, String kind, String state, BackupRow record) {
        this.due = due; this.kind = kind; this.state = state; this.record = record;
    }
    public LocalDate due() { return due; }
    public String kind() { return kind; }
    public String state() { return state; }
    public BackupRow record() { return record; }
    public LocalDate getDue() { return due; }
    public String getKind() { return kind; }
    public String getState() { return state; }
    public BackupRow getRecord() { return record; }
}

final class CoverageSlot {
    private final LocalDate due;
    private final String state;
    private final String reason;
    private final BackupRow record;
    CoverageSlot(LocalDate due, String state, String reason, BackupRow record) {
        this.due = due; this.state = state; this.reason = reason; this.record = record;
    }
    public LocalDate due() { return due; }
    public String state() { return state; }
    public String reason() { return reason; }
    public BackupRow record() { return record; }
    public LocalDate getDue() { return due; }
    public String getState() { return state; }
    public String getReason() { return reason; }
    public BackupRow getRecord() { return record; }
}

final class CoverageGroup {
    private final String kind;
    private final boolean sourceConnected;
    private final Integer retentionDays;
    private final int graceDays;
    private final int expectedCount;
    private final int presentCount;
    private final int missingCount;
    private final int pendingCount;
    private final int unknownCount;
    private final java.util.List<CoverageSlot> slots;
    CoverageGroup(String kind, boolean sourceConnected, Integer retentionDays, int graceDays,
                  int expectedCount, int presentCount, int missingCount, int pendingCount,
                  int unknownCount, java.util.List<CoverageSlot> slots) {
        this.kind = kind; this.sourceConnected = sourceConnected; this.retentionDays = retentionDays;
        this.graceDays = graceDays; this.expectedCount = expectedCount; this.presentCount = presentCount;
        this.missingCount = missingCount; this.pendingCount = pendingCount; this.unknownCount = unknownCount;
        this.slots = slots;
    }
    public String kind() { return kind; }
    public boolean sourceConnected() { return sourceConnected; }
    public Integer retentionDays() { return retentionDays; }
    public int graceDays() { return graceDays; }
    public int expectedCount() { return expectedCount; }
    public int presentCount() { return presentCount; }
    public int missingCount() { return missingCount; }
    public int pendingCount() { return pendingCount; }
    public int unknownCount() { return unknownCount; }
    public java.util.List<CoverageSlot> slots() { return slots; }
    public String getKind() { return kind; }
    public boolean isSourceConnected() { return sourceConnected; }
    public Integer getRetentionDays() { return retentionDays; }
    public int getGraceDays() { return graceDays; }
    public int getExpectedCount() { return expectedCount; }
    public int getPresentCount() { return presentCount; }
    public int getMissingCount() { return missingCount; }
    public int getPendingCount() { return pendingCount; }
    public int getUnknownCount() { return unknownCount; }
    public java.util.List<CoverageSlot> getSlots() { return slots; }
}

final class UserAccount {
    private final String username;
    private final String passwordHash;
    private final String role;
    private final boolean enabled;
    UserAccount(String username, String passwordHash, String role, boolean enabled) {
        this.username = username; this.passwordHash = passwordHash; this.role = role; this.enabled = enabled;
    }
    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
    public String role() { return role; }
    public boolean enabled() { return enabled; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public boolean isEnabled() { return enabled; }
}

final class BackupWrite {
    private final long databaseId;
    private final String kind;
    private final String externalId;
    private final LocalDate backupDate;
    private final boolean dateInferred;
    private final Instant eventTime;
    private final String status;
    private final String rawData;
    private final boolean demo;
    BackupWrite(long databaseId, String kind, String externalId, LocalDate backupDate,
                boolean dateInferred, Instant eventTime, String status, String rawData) {
        this(databaseId, kind, externalId, backupDate, dateInferred, eventTime, status, rawData, false);
    }
    BackupWrite(long databaseId, String kind, String externalId, LocalDate backupDate,
                boolean dateInferred, Instant eventTime, String status, String rawData, boolean demo) {
        this.databaseId = databaseId; this.kind = kind; this.externalId = externalId;
        this.backupDate = backupDate; this.dateInferred = dateInferred; this.eventTime = eventTime;
        this.status = status; this.rawData = rawData; this.demo = demo;
    }
    public long getDatabaseId() { return databaseId; }
    public String getKind() { return kind; }
    public String getExternalId() { return externalId; }
    public LocalDate getBackupDate() { return backupDate; }
    public boolean isDateInferred() { return dateInferred; }
    public Instant getEventTime() { return eventTime; }
    public String getStatus() { return status; }
    public String getRawData() { return rawData; }
    public boolean isDemo() { return demo; }
}

final class SyncRunInsert {
    private Long id;
    private final String kind;

    SyncRunInsert(String kind) { this.kind = kind; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
}
