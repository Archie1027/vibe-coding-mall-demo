package com.zhicai.platform.trade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentClosingMapper {
    String lockOrder(@Param("id") String id);
    Facts facts(@Param("id") String id);
    record Facts(long total,long active,long succeeded,long terminal) {}
}
