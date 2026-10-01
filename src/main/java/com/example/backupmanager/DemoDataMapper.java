package com.example.backupmanager;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface DemoDataMapper {
    boolean isVisible();
    int setVisible(@Param("visible") boolean visible);
    long countDatabases();
    long countBackups();
}
