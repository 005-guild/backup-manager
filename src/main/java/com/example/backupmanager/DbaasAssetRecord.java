package com.example.backupmanager;

final class DbaasAssetRecord {
    private final String externalId;
    private final String dbName;
    private final String rawData;

    DbaasAssetRecord(String externalId, String dbName, String rawData) {
        this.externalId = externalId;
        this.dbName = dbName;
        this.rawData = rawData;
    }

    public String getExternalId() { return externalId; }
    public String getDbName() { return dbName; }
    public String getRawData() { return rawData; }
}
