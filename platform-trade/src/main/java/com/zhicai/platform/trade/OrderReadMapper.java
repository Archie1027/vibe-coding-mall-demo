package com.zhicai.platform.trade;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderReadMapper {
    long count(@Param("owner") String owner, @Param("state") String state);
    List<OrderRow> list(@Param("owner") String owner, @Param("state") String state,
                        @Param("offset") long offset, @Param("size") int size);
    OrderRow find(@Param("owner") String owner, @Param("id") String id);
    List<LineRow> lines(@Param("orderIds") List<String> orderIds);

    record OrderRow(String id,String orderNo,String customerId,String warehouseId,String state,
                    String addressSnapshotJson,String grossMinor,String discountMinor,String shippingMinor,
                    String payableMinor,String refundedMinor,Instant expiresAt,Instant createdAt,long version) {}
    record LineRow(String orderId,String id,int lineNo,String skuId,String productName,String specsSnapshotJson,
                   int quantity,String unitPriceMinor,String grossMinor,String discountMinor,String payableMinor,
                   int returnedQuantity,String refundedMinor) {}
}
