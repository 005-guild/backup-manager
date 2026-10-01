package com.example.backupmanager;

final class DbaasAsset {
    private final String externalId;
    private final String dbName;
    private final String logicdbCode;
    private final Long ldbid;
    private final String valid;
    private final String rawData;
    private final DatabaseMetadata metadata;

    DbaasAsset(String externalId, String dbName, String logicdbCode, Long ldbid,
               String valid, String rawData, DatabaseMetadata metadata) {
        this.externalId = externalId;
        this.dbName = dbName;
        this.logicdbCode = logicdbCode;
        this.ldbid = ldbid;
        this.valid = valid;
        this.rawData = rawData;
        this.metadata = metadata;
    }

    String externalId() { return externalId; }
    String dbName() { return dbName; }
    DatabaseMetadata metadata() { return metadata; }
    public String getExternalId() { return externalId; }
    public String getDbName() { return dbName; }
    public String getLogicdbCode() { return logicdbCode; }
    public Long getLdbid() { return ldbid; }
    public String getValid() { return valid; }
    public String getRawData() { return rawData; }
    public DatabaseMetadata getMetadata() { return metadata; }
}
