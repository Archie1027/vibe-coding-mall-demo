package com.zhicai.platform.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface CatalogMapper {
    long countAdminCategories(@Param("keyword") String keyword);
    List<CatalogModels.Category> findAdminCategories(@Param("offset") long offset, @Param("size") int size, @Param("keyword") String keyword);
    long countAdminBrands(@Param("keyword") String keyword);
    List<CatalogModels.Brand> findAdminBrands(@Param("offset") long offset, @Param("size") int size, @Param("keyword") String keyword);
    long countAdminProducts(@Param("keyword") String keyword);
    List<ProductRow> findAdminProducts(@Param("offset") long offset, @Param("size") int size, @Param("keyword") String keyword);
    ProductRow findAdminProduct(@Param("id") String id);
    List<SkuRow> findAdminSkus(@Param("ids") List<String> ids);
    long countCategories();
    List<CatalogModels.Category> findCategories(@Param("offset") long offset, @Param("size") int size);
    long countBrands();
    List<CatalogModels.Brand> findBrands(@Param("offset") long offset, @Param("size") int size);
    long countProducts(@Param("filter") ProductFilter filter);
    List<ProductRow> findProducts(@Param("filter") ProductFilter filter);
    ProductRow findProduct(@Param("id") String id);
    List<SkuRow> findSkus(@Param("ids") List<String> ids);
    List<AssetRow> findAssets(@Param("ids") List<String> ids);
    List<CartSkuRow> findCartSkus(@Param("ids") List<String> ids);
    record CartSkuRow(String skuId,String productName,String specsJson,String priceMinor,boolean skuEnabled,String productState,boolean categoryEnabled,boolean brandEnabled,String imageKey) {}

    record ProductFilter(long offset, int size, String keyword, String categoryId, String brandId,
                         String minPriceMinor, String maxPriceMinor, Boolean featured, String sort) {}
    record ProductRow(String id, String name, String categoryId, String brandId, String description,
                      boolean featured, String state, long ratingSum, long ratingCount, long version, Instant createdAt) {}
    record SkuRow(String id, String productId, String code, String specsJson, String priceMinor, boolean enabled, long version) {}
    record AssetRow(String id, String productId, String storageKey, String contentType, long sizeBytes) {}
}
