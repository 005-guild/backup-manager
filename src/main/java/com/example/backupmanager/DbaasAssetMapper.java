package com.example.backupmanager;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface DbaasAssetMapper {
    int update(@Param("asset") DbaasAsset asset, @Param("runId") long runId);
    int insert(@Param("asset") DbaasAsset asset, @Param("runId") long runId);
    long countAll();
    DbaasAssetRecord findByExternalId(String externalId);
    List<DbaasAssetRecord> findRebindCandidates(long runId);
    int markUnseen(long runId);
    int rebindCatalog(long runId);
    int deactivateMissingCatalog(long runId);
}
