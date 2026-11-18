package com.zhicai.platform.trade;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhicai.platform.catalog.CatalogModels.Spec;
import java.util.List;

public final class TradeModels {
    private TradeModels() {}
    public record AddressInput(String recipient, String phone, String province, String city, String district,
                               String detail, Boolean isDefault) {}
    public record Address(String id, String recipient, String phone, String province, String city, String district,
                          String detail, boolean isDefault, long version) {}
    public record AddressChange(Address before, Address after) {}
    public record CartLine(String id, String skuId, String productName, List<Spec> specs, String priceMinor,
                           int quantity, boolean selected, boolean valid,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String invalidReason,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String imageUrl) {}
    public record CartChange(CartLine before, CartLine after) {}
}
