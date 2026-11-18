package com.zhicai.platform.catalog;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Checkout-specific SELECT boundary; callers outside catalog use CheckoutCatalogService. */
@Mapper
public interface CheckoutCatalogMapper {
    List<Row> findEnabledSkus(@Param("ids") List<String> ids);
    List<SkuProductRow> findEnabledSkuProducts(@Param("ids") List<String> ids);
    String lockProduct(@Param("id") String id);
    String lockCategoryAnchor();
    List<Row> lockEnabledSkus(@Param("ids") List<String> ids);
    List<ImageRow> lockPublicImages(@Param("productIds") List<String> productIds);
    record SkuProductRow(String skuId,String productId) {}
    record ImageRow(String productId,String assetId,String storageKey,String contentType,long sizeBytes) {}
    record Row(String skuId,String productId,String productName,String productDescription,boolean productFeatured,
               String categoryId,String categoryName,String brandId,String brandName,String skuCode,String specsJson,
               String priceMinor,long skuVersion,long productVersion,long categoryVersion,long brandVersion) {}
}
