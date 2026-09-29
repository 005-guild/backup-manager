package com.example.backupmanager;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface RuleMapper {
    List<RuleRow> findAll();
    int update(@Param("id") long id, @Param("enabled") boolean enabled,
               @Param("graceDays") int graceDays, @Param("retentionDays") Integer retentionDays);
}
