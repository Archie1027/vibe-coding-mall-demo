package com.zhicai.platform.trade;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {
    LockedQuote lockQuote(@Param("owner") String owner,@Param("quoteId") String quoteId);
    int insertOrder(NewOrder order);
    int insertItem(NewItem item);
    int markQuoteUsed(@Param("owner") String owner,@Param("quoteId") String quoteId,@Param("orderId") String orderId,@Param("now") Instant now);
    record LockedQuote(String id,String owner,String warehouseId,String snapshotJson,Instant expiresAt,String usedOrderId,Instant createdAt) {}
    final class NewOrder {
        public String id;
        public final String orderNo,quoteId,owner,warehouseId,addressSnapshotJson,remark,grossMinor,discountMinor,shippingMinor,payableMinor;
        public final Instant expiresAt,createdAt;
        public NewOrder(String orderNo,String quoteId,String owner,String warehouseId,String addressSnapshotJson,String remark,String grossMinor,String discountMinor,String shippingMinor,String payableMinor,Instant expiresAt,Instant createdAt){
            this.orderNo=orderNo;this.quoteId=quoteId;this.owner=owner;this.warehouseId=warehouseId;this.addressSnapshotJson=addressSnapshotJson;this.remark=remark;this.grossMinor=grossMinor;this.discountMinor=discountMinor;this.shippingMinor=shippingMinor;this.payableMinor=payableMinor;this.expiresAt=expiresAt;this.createdAt=createdAt;
        }
    }
    final class NewItem {
        public String id;
        public final String orderId,skuId,productId,productName,specsSnapshotJson,imageSnapshotJson,unitPriceMinor,grossMinor,discountMinor,payableMinor;
        public final int lineNo,quantity;
        public final Instant createdAt;
        public NewItem(String orderId,int lineNo,String skuId,String productId,String productName,String specsSnapshotJson,String imageSnapshotJson,int quantity,String unitPriceMinor,String grossMinor,String discountMinor,String payableMinor,Instant createdAt){
            this.orderId=orderId;this.lineNo=lineNo;this.skuId=skuId;this.productId=productId;this.productName=productName;this.specsSnapshotJson=specsSnapshotJson;this.imageSnapshotJson=imageSnapshotJson;this.quantity=quantity;this.unitPriceMinor=unitPriceMinor;this.grossMinor=grossMinor;this.discountMinor=discountMinor;this.payableMinor=payableMinor;this.createdAt=createdAt;
        }
    }
}
