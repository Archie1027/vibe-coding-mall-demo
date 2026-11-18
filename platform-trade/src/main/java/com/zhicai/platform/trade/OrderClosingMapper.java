package com.zhicai.platform.trade;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderClosingMapper {
    Locked lockOwned(@Param("owner") String owner,@Param("id") String id);
    Locked lockAny(@Param("id") String id);
    List<Item> items(@Param("orderId") String orderId);
    int markCancelled(@Param("owner") String owner,@Param("id") String id,@Param("version") long version,@Param("now") Instant now,@Param("expiredOnly") boolean expiredOnly);
    int markClosing(@Param("owner") String owner,@Param("id") String id,@Param("version") long version,@Param("now") Instant now,@Param("expiredOnly") boolean expiredOnly);
    int markClosingCancelled(@Param("owner") String owner,@Param("id") String id,@Param("version") long version,@Param("now") Instant now);
    List<OrderClosingService.Candidate> expired(@Param("cutoff") Instant cutoff,@Param("afterExpiresAt") Instant afterExpiresAt,@Param("afterId") String afterId,@Param("limit") int limit);
    record Locked(String id,String owner,String warehouseId,String state,String couponId,Instant expiresAt,Instant cancelledAt,long version) {}
    record Item(String id,String orderId,String skuId,int quantity,int lineNo) {}
}
