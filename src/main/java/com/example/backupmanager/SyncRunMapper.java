package com.example.backupmanager;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface SyncRunMapper {
    List<SyncRow> findRecent(@Param("limit") int limit);
    int insert(SyncRunInsert command);
    int finish(@Param("id") long id, @Param("status") String status,
               @Param("fetched") int fetched, @Param("saved") int saved,
               @Param("error") String error);
}
