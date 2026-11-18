package com.zhicai.platform.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import java.util.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public catalog boundary for current enabled checkout evidence. Explicitly wired by its caller. */
public class CheckoutCatalogService {
    public record ImageSnapshot(String assetId,String storageKey,String contentType,long sizeBytes) {}
    public record OrderEvidence(Map<String,CheckoutSku> skus,Map<String,ImageSnapshot> imagesByProduct) {
        public OrderEvidence { skus=Map.copyOf(skus);imagesByProduct=Map.copyOf(imagesByProduct); }
    }
    public record CheckoutSku(String skuId,String productId,String productName,String productDescription,boolean productFeatured,
                              String categoryId,String categoryName,String brandId,String brandName,String skuCode,
                              List<CatalogModels.Spec> specs,String priceMinor,long skuVersion,long productVersion,
                              long categoryVersion,long brandVersion) {
        public CheckoutSku { specs=List.copyOf(specs); }
    }
    private static final TypeReference<List<CatalogModels.Spec>> SPECS=new TypeReference<>() {};
    private final CheckoutCatalogMapper mapper;
    private final ObjectMapper json;
    public CheckoutCatalogService(CheckoutCatalogMapper mapper,ObjectMapper json){this.mapper=Objects.requireNonNull(mapper);this.json=Objects.requireNonNull(json);}

    /** Locks product roots before dependent rows, matching ProductWriteService's write order. */
    public OrderEvidence lockOrderEvidence(List<String> skuIds){
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED)
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Order catalog evidence requires an active, writable, explicitly READ_COMMITTED transaction");
        if(skuIds==null || skuIds.size()>100)bad();
        for(var id:skuIds)if(!canonicalId(id))bad();
        if(skuIds.isEmpty())return new OrderEvidence(Map.of(),Map.of());
        var ids=skuIds.stream().distinct().sorted(NUMERIC_ID).toList();
        var initialProducts=new HashMap<String,String>();
        var productIds=new TreeSet<String>(NUMERIC_ID);
        for(var row:mapper.findEnabledSkuProducts(ids)){
            if(row==null || !canonicalId(row.skuId()) || !ids.contains(row.skuId()) || !canonicalId(row.productId())
                || initialProducts.putIfAbsent(row.skuId(),row.productId())!=null)invalidOrderEvidence();
            productIds.add(row.productId());
        }
        for(var productId:productIds)if(!productId.equals(mapper.lockProduct(productId)))invalidOrderEvidence();
        // Category writes lock the minimum category before their target. Take that same anchor
        // before the join can lock any other category, avoiding an inverted category lock order.
        if(!productIds.isEmpty() && !canonicalId(mapper.lockCategoryAnchor()))invalidOrderEvidence();
        var skus=new LinkedHashMap<String,CheckoutSku>();
        var finalProducts=new TreeSet<String>(NUMERIC_ID);
        for(var row:mapper.lockEnabledSkus(ids)){
            if(row==null || !canonicalId(row.skuId()) || !ids.contains(row.skuId()) || !canonicalId(row.productId()) || !productIds.contains(row.productId())
                || !Objects.equals(initialProducts.get(row.skuId()),row.productId())
                || row.skuVersion()<0 || row.productVersion()<0 || row.categoryVersion()<0 || row.brandVersion()<0)invalidOrderEvidence();
            var sku=new CheckoutSku(row.skuId(),row.productId(),row.productName(),row.productDescription(),row.productFeatured(),row.categoryId(),row.categoryName(),row.brandId(),row.brandName(),row.skuCode(),specs(row.specsJson()),row.priceMinor(),row.skuVersion(),row.productVersion(),row.categoryVersion(),row.brandVersion());
            if(skus.putIfAbsent(row.skuId(),sku)!=null)invalidOrderEvidence();
            finalProducts.add(row.productId());
        }
        var images=new LinkedHashMap<String,ImageSnapshot>();
        if(!finalProducts.isEmpty())for(var row:mapper.lockPublicImages(List.copyOf(finalProducts))){
            if(row==null || !canonicalId(row.productId()) || !finalProducts.contains(row.productId()) || !canonicalId(row.assetId())
                || row.storageKey()==null || row.storageKey().isBlank() || !IMAGE_TYPES.contains(row.contentType()==null?"":row.contentType())
                || row.sizeBytes()<0 || row.sizeBytes()>5242880)invalidOrderEvidence();
            images.putIfAbsent(row.productId(),new ImageSnapshot(row.assetId(),row.storageKey(),row.contentType(),row.sizeBytes()));
        }
        return new OrderEvidence(skus,images);
    }
    private static final Comparator<String> NUMERIC_ID=Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());
    private static final Set<String> IMAGE_TYPES=Set.of("image/jpeg","image/png","image/webp");
    private static boolean canonicalId(String value){return value!=null && value.matches("[1-9][0-9]{0,18}");}
    private static void invalidOrderEvidence(){throw new IllegalStateException("Invalid locked order catalog evidence");}

    public Map<String,CheckoutSku> skus(List<String> ids){
        if(ids==null || ids.size()>100)bad();
        for(var id:ids)if(id==null || !id.matches("[1-9][0-9]{0,18}"))bad();
        if(ids.isEmpty())return Map.of();
        var distinct=ids.stream().distinct().toList();var result=new LinkedHashMap<String,CheckoutSku>();
        for(var row:mapper.findEnabledSkus(distinct)){
            if(!distinct.contains(row.skuId()) || row.skuVersion()<0 || row.productVersion()<0 || row.categoryVersion()<0 || row.brandVersion()<0)throw new IllegalStateException("Invalid checkout catalog evidence");
            var sku=new CheckoutSku(row.skuId(),row.productId(),row.productName(),row.productDescription(),row.productFeatured(),row.categoryId(),row.categoryName(),row.brandId(),row.brandName(),row.skuCode(),specs(row.specsJson()),row.priceMinor(),row.skuVersion(),row.productVersion(),row.categoryVersion(),row.brandVersion());
            if(result.putIfAbsent(row.skuId(),sku)!=null)throw new IllegalStateException("Duplicate checkout catalog evidence");
        }
        return Collections.unmodifiableMap(result);
    }
    private List<CatalogModels.Spec> specs(String value){
        try{return List.copyOf(json.readValue(value,SPECS));}
        catch(Exception e){throw new IllegalStateException("Invalid stored checkout SKU specs JSON",e);}
    }
    private static void bad(){throw new ApiException(400,"VALIDATION_ERROR","SKU查询参数不合法");}
}
