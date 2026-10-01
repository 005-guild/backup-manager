package com.example.backupmanager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface DatabaseMapper {
    List<DatabaseRow> findAll(@Param("pattern") String pattern);
    List<DatabaseRow> findPage(@Param("pattern") String pattern,
                               @Param("backupState") String backupState,
                               @Param("monitorState") String monitorState,
                               @Param("framework") String framework,
                               @Param("limit") int limit, @Param("offset") int offset);
    long countFiltered(@Param("pattern") String pattern,
                       @Param("backupState") String backupState,
                       @Param("monitorState") String monitorState,
                       @Param("framework") String framework);
    List<String> findFrameworks();
    List<DatabaseRow> findDashboardActive();
    DatabaseRow findById(@Param("id") long id);
    Long findIdByName(@Param("name") String name);
    Long findDemoIdByName(@Param("name") String name);
    int insert(Map<String, Object> values);
    int insertDemo(@Param("name") String name, @Param("monitorFrom") LocalDate monitorFrom);
    int moveAutomaticMonitorStart(@Param("name") String name, @Param("firstDate") LocalDate firstDate);
    int updateMetadata(@Param("id") long id, @Param("metadata") DatabaseMetadata metadata);
    int bindAsset(@Param("asset") DbaasAsset asset);
    int setDemoMonitoring(@Param("id") long id, @Param("name") String name,
                          @Param("monitorFrom") LocalDate monitorFrom);
}
