package com.example.backupmanager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface DatabaseMapper {
    List<DatabaseRow> findAll(@Param("pattern") String pattern);
    DatabaseRow findById(@Param("id") long id);
    Long findIdByName(@Param("name") String name);
    int insert(Map<String, Object> values);
    int moveAutomaticMonitorStart(@Param("name") String name, @Param("firstDate") LocalDate firstDate);
    int updateMetadata(@Param("id") long id, @Param("metadata") DatabaseMetadata metadata);
    int setDemoMonitoring(@Param("id") long id, @Param("name") String name,
                          @Param("monitorFrom") LocalDate monitorFrom);
}
