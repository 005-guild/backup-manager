package com.example.backupmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class DbaasAssetRepository {
    private final DbaasAssetMapper assets;
    private final DatabaseMapper databases;
    private final CatalogRepository catalog;
    private final ObjectMapper mapper;

    DbaasAssetRepository(DbaasAssetMapper assets, DatabaseMapper databases, CatalogRepository catalog,
                         ObjectMapper mapper) {
        this.assets = assets;
        this.databases = databases;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    long countAll() { return assets.countAll(); }

    @Transactional
    public boolean upsert(DbaasAsset asset, LocalDate monitorFrom) {
        return upsert(asset, monitorFrom, 0);
    }

    @Transactional
    public boolean upsert(DbaasAsset asset, LocalDate monitorFrom, long runId) {
        boolean inserted = false;
        if (assets.update(asset, runId) == 0) {
            inserted = assets.insert(asset, runId) > 0;
            if (!inserted) assets.update(asset, runId);
        }
        catalog.getOrCreateDatabase(asset.dbName(), monitorFrom);
        databases.bindAsset(asset);
        return inserted;
    }

    @Transactional
    public int reconcileSnapshot(long runId) {
        List<DbaasAssetRecord> rebound = assets.findRebindCandidates(runId);
        assets.markUnseen(runId);
        assets.rebindCatalog(runId);
        for (DbaasAssetRecord record : rebound) {
            try {
                databases.bindAsset(DbaasAssetParser.parse(mapper.readTree(record.getRawData())));
            } catch (Exception error) {
                throw new IllegalStateException("DBLIST 资产目录重新绑定失败: " + record.getExternalId(), error);
            }
        }
        return assets.deactivateMissingCatalog(runId);
    }
}
