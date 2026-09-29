package com.example.backupmanager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface BackupMapper {
    List<BackupRow> find(@Param("databaseId") Long databaseId,
                         @Param("from") LocalDate from,
                         @Param("to") LocalDate to,
                         @Param("kind") String kind,
                         @Param("status") String status,
                         @Param("limit") int limit,
                         @Param("offset") int offset);
    List<BackupRow> findForChecks(@Param("databaseId") long databaseId,
                                  @Param("kind") String kind,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);
    List<Map<String, Object>> calendar(@Param("from") LocalDate from, @Param("to") LocalDate to);
    long countAll();
    long countBySource(@Param("kind") String kind, @Param("externalId") String externalId);
    int update(BackupWrite record);
    int insert(BackupWrite record);
}
