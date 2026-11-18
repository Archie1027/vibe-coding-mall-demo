package com.zhicai.platform.trade;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhicai.platform.catalog.CatalogModels.Spec;
import com.zhicai.platform.catalog.CheckoutCatalogService.CheckoutSku;
import java.time.Instant;
import java.util.*;

public final class QuoteModels {
    private QuoteModels() {}
    public record Item(String skuId,Integer quantity) {}
    public record Request(String addressId,List<Item> items,@JsonInclude(JsonInclude.Include.NON_NULL) String couponId) {
        // Preserve null rows for the service's normal validation error; freeze the caller's collection.
        public Request { if(items!=null)items=Collections.unmodifiableList(new ArrayList<>(items)); }
    }
    public record Line(String skuId,String productName,List<Spec> specs,int quantity,String unitPriceMinor,
                       String grossMinor,String discountMinor,String payableMinor) {
        public Line { specs=List.copyOf(specs); }
    }
    public record Quote(String id,String warehouseId,List<Line> items,String grossMinor,String discountMinor,
                        String shippingMinor,String payableMinor,@JsonInclude(JsonInclude.Include.NON_NULL) String couponId,
                        Instant expiresAt) {
        public Quote { items=List.copyOf(items); }
    }
    public record SnapshotLine(CheckoutSku catalog,Line amounts) {}
    /** Versioned internal persistence format, deliberately separate from the op020 response. */
    public record Snapshot(int formatVersion,String owner,String addressId,TradeModels.Address address,String warehouseId,
                           List<SnapshotLine> items,String grossMinor,String discountMinor,String shippingMinor,String payableMinor,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String couponId,Instant createdAt,Instant expiresAt) {
        public Snapshot { items=List.copyOf(items); }
    }
}
