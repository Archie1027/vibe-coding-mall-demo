package com.zhicai.platform.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import static com.zhicai.platform.catalog.CatalogModels.*;

@Service
@Transactional(readOnly = true)
public class CatalogQueryService {
    private static final TypeReference<List<Spec>> SPECS = new TypeReference<>() {};
    private final CatalogMapper mapper;
    private final ObjectMapper json;
    private final String assetBaseUrl;

    public CatalogQueryService(CatalogMapper mapper, ObjectMapper json,
                               @Value("${app.catalog.asset-base-url:http://127.0.0.1:8080/assets/products/}") String assetBaseUrl) {
        this.mapper = mapper;
        this.json = json;
        if (!assetBaseUrl.matches("https?://[^?#]+/")) throw new IllegalArgumentException("asset base URL must be an absolute HTTP(S) directory URL");
        this.assetBaseUrl = assetBaseUrl;
    }

    public PageData<Category> categories(int page, int size) {
        long offset = offset(page, size);
        return new PageData<>(mapper.findCategories(offset, size), page, size, mapper.countCategories());
    }
    public Map<String,CartSku> cartSkus(List<String> ids) {
        if(ids==null || ids.size()>100)bad("SKU查询参数不合法");
        ids.forEach(CatalogQueryService::id);if(ids.isEmpty())return Map.of();
        Map<String,CartSku> result=new LinkedHashMap<>();
        for(var row:mapper.findCartSkus(ids.stream().distinct().toList())) {
            String reason=!row.skuEnabled()?"规格已停用":!row.productState().equals("ON_SALE")?"商品已下架":!row.categoryEnabled()?"分类已停用":!row.brandEnabled()?"品牌已停用":null;
            result.put(row.skuId(),new CartSku(row.skuId(),row.productName(),specs(row.specsJson()),row.priceMinor(),reason==null,reason,row.imageKey()==null?null:assetUrl(row.imageKey())));
        }
        return result;
    }
    public PageData<Category> adminCategories(int page, int size, String keyword) {
        long offset = offset(page, size); String pattern = escape(keyword);
        return new PageData<>(mapper.findAdminCategories(offset, size, pattern), page, size, mapper.countAdminCategories(pattern));
    }
    public PageData<Brand> adminBrands(int page, int size, String keyword) {
        long offset = offset(page, size); String pattern = escape(keyword);
        return new PageData<>(mapper.findAdminBrands(offset, size, pattern), page, size, mapper.countAdminBrands(pattern));
    }
    public PageData<Product> adminProducts(int page, int size, String keyword) {
        long offset = offset(page, size); String pattern = escape(keyword);
        long total = mapper.countAdminProducts(pattern);
        return new PageData<>(hydrate(mapper.findAdminProducts(offset, size, pattern), true), page, size, total);
    }
    public Product adminProduct(String id) {
        var row = mapper.findAdminProduct(id(id));
        if (row == null) throw new ApiException(404, "NOT_FOUND", "商品不存在");
        return hydrate(List.of(row), true).getFirst();
    }
    public PageData<Brand> brands(int page, int size) {
        long offset = offset(page, size);
        return new PageData<>(mapper.findBrands(offset, size), page, size, mapper.countBrands());
    }
    public PageData<Product> products(int page, int size, String keyword, String categoryId, String brandId,
                                      String minPriceMinor, String maxPriceMinor, Boolean featured, String sort) {
        long offset = offset(page, size);
        String min = money(minPriceMinor), max = money(maxPriceMinor);
        if (min != null && max != null && new BigDecimal(min).compareTo(new BigDecimal(max)) > 0) bad("价格区间不合法");
        String order = sort == null ? "NEWEST" : sort;
        if (!Set.of("NEWEST", "PRICE_ASC", "PRICE_DESC").contains(order)) bad("排序方式不合法");
        var filter = new CatalogMapper.ProductFilter(offset, size, escape(keyword), idOrNull(categoryId), idOrNull(brandId), min, max, featured, order);
        long total = mapper.countProducts(filter);
        List<CatalogMapper.ProductRow> rows = mapper.findProducts(filter);
        return new PageData<>(hydrate(rows), page, size, total);
    }
    public Product product(String id) {
        String validatedId = id(id);
        var row = mapper.findProduct(validatedId);
        if (row == null) throw new ApiException(404, "NOT_FOUND", "商品不存在");
        return hydrate(List.of(row)).getFirst();
    }

    private List<Product> hydrate(List<CatalogMapper.ProductRow> rows) {
        return hydrate(rows, false);
    }
    private List<Product> hydrate(List<CatalogMapper.ProductRow> rows, boolean admin) {
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(CatalogMapper.ProductRow::id).toList();
        var skus = (admin ? mapper.findAdminSkus(ids) : mapper.findSkus(ids)).stream().collect(Collectors.groupingBy(CatalogMapper.SkuRow::productId));
        var assets = mapper.findAssets(ids).stream().collect(Collectors.groupingBy(CatalogMapper.AssetRow::productId));
        return rows.stream().map(row -> {
            var publicSkus = skus.getOrDefault(row.id(), List.of()).stream().map(s -> new Sku(s.id(), s.productId(), s.code(), specs(s.specsJson()), s.priceMinor(), s.enabled(), s.version())).toList();
            var publicAssets = assets.getOrDefault(row.id(), List.of()).stream().map(a -> new Asset(a.id(), assetUrl(a.storageKey()), a.contentType(), a.sizeBytes())).toList();
            var imageIds = publicAssets.stream().map(Asset::id).toList();
            BigDecimal average = row.ratingCount() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(row.ratingSum()).divide(BigDecimal.valueOf(row.ratingCount()), 2, RoundingMode.HALF_UP);
            return new Product(row.id(), row.name(), row.categoryId(), row.brandId(), imageIds, row.description(), row.featured(), average, row.ratingCount(), row.state(), publicAssets, publicSkus, row.version(), row.createdAt());
        }).toList();
    }
    private List<Spec> specs(String value) {
        try { return json.readValue(value, SPECS); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored SKU specs JSON", e); }
    }
    private String assetUrl(String key) {
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || key.contains("..")) throw new IllegalStateException("Invalid stored asset key");
        return assetBaseUrl + key;
    }
    private static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) bad("分页参数不合法");
        return (long) (page - 1) * size;
    }
    private static String idOrNull(String id) { return id == null ? null : id(id); }
    private static String id(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) bad("ID格式不合法");
        return id;
    }
    private static String money(String value) {
        if (value != null && !value.matches("0|[1-9][0-9]{0,17}")) bad("金额格式不合法");
        return value;
    }
    private static String escape(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        if (keyword.length() > 120) bad("关键词过长");
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    private static void bad(String message) { throw new ApiException(400, "VALIDATION_ERROR", message); }
}
