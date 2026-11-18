package com.zhicai.platform.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogModels {
    private CatalogModels() {}
    public record PageData<T>(List<T> items, int page, int size, long total) {}
    public record Category(String id, String name, @JsonInclude(JsonInclude.Include.NON_NULL) String parentId, int sort, boolean enabled, long version) {}
    public record Brand(String id, String name, boolean enabled, long version) {}
    public record Asset(String id, String url, String contentType, long sizeBytes) {}
    public record Spec(String name, String value) {}
    public record Sku(String id, String productId, String code, List<Spec> specs, String priceMinor, boolean enabled, long version) {}
    public record CartSku(String skuId,String productName,List<Spec> specs,String priceMinor,boolean valid,String invalidReason,String imageUrl) {}
    public record Product(String id, String name, String categoryId, String brandId, List<String> imageAssetIds,
                          String description, boolean featured, BigDecimal ratingAverage, long ratingCount,
                          String state, List<Asset> images, List<Sku> skus, long version, Instant createdAt) {}
}
