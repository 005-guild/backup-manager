package com.example.backupmanager;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface UserMapper {
    UserAccount findAccount(@Param("username") String username);
    long countAll();
    List<Map<String, Object>> findAll();
    int insert(@Param("username") String username,
               @Param("passwordHash") String passwordHash,
               @Param("role") String role);
}
