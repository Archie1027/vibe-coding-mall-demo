package com.zhicai.platform.identity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IdentityMapper {
    AccountRow findByUsername(@Param("type") AccountType type, @Param("username") String username);
    AccountRow findById(String id);
    List<String> permissions(String accountId);
    List<String> warehouses(String accountId);
    List<String> roles(String accountId);
}
