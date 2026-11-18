package com.zhicai.platform.trade;

import com.zhicai.platform.catalog.CatalogModels.Spec;
import java.time.Instant;
import java.util.List;

/** Internal op021 contracts. This core creates UNPAID orders only. */
public final class OrderModels {
    private OrderModels() {}
    public record Create(String quoteId,String remark) {}
    public record Line(String id,String skuId,String productName,List<Spec> specs,int quantity,String unitPriceMinor,
                       String grossMinor,String discountMinor,String payableMinor,int returnedQuantity,String refundedMinor) {
        public Line { specs=List.copyOf(specs); }
    }
    public record Order(String id,String orderNo,String customerId,String warehouseId,String state,List<Line> items,
                        TradeModels.AddressInput addressSnapshot,String grossMinor,String discountMinor,String shippingMinor,
                        String payableMinor,String refundedMinor,Instant expiresAt,Instant createdAt,long version) {
        public Order { items=List.copyOf(items); }
    }
}
