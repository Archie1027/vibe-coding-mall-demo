package com.zhicai.platform.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckoutCatalogServiceTest {
    final CheckoutCatalogMapper mapper=mock(CheckoutCatalogMapper.class);
    final CheckoutCatalogService service=new CheckoutCatalogService(mapper,new ObjectMapper());
    @AfterEach void clearTransaction(){TransactionSynchronizationManager.clear();}
    private void writableTransaction(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    private CheckoutCatalogMapper.Row row(String sku,String product){
        return new CheckoutCatalogMapper.Row(sku,product,"商品","说明",true,"7","分类","8","品牌","CODE","[]","100",11,12,13,14);
    }
    private void initial(String sku,String product){
        when(mapper.findEnabledSkuProducts(anyList())).thenReturn(List.of(new CheckoutCatalogMapper.SkuProductRow(sku,product)));
        when(mapper.lockProduct(product)).thenReturn(product);
        when(mapper.lockCategoryAnchor()).thenReturn("1");
    }
    @Test void orderEvidenceRequiresExplicitWritableReadCommittedBeforeAnyMapperIo(){
        assertThatThrownBy(()->service.lockOrderEvidence(List.of())).isInstanceOf(IllegalTransactionStateException.class);
        writableTransaction();TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalTransactionStateException.class);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        for(Integer isolation:Arrays.asList(null,TransactionDefinition.ISOLATION_DEFAULT,TransactionDefinition.ISOLATION_READ_UNCOMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_SERIALIZABLE)){
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
            assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalTransactionStateException.class);
        }
        verifyNoInteractions(mapper);
    }
    @Test void orderIdsAreValidatedAndEmptyBatchDoesNotQuery(){
        writableTransaction();assertThat(service.lockOrderEvidence(List.of()).skus()).isEmpty();
        for(List<String> ids:Arrays.<List<String>>asList(null,List.of("0"),List.of("01"),List.of("-1"),List.of("10000000000000000000"),Collections.singletonList(null),Collections.nCopies(101,"3")))
            assertThatThrownBy(()->service.lockOrderEvidence(ids)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void orderLocksNumericProductRootsBeforeSkuAndImageRowsAndKeepsFirstImage(){
        writableTransaction();var ids=List.of("2","10","9007199254740993");
        when(mapper.findEnabledSkuProducts(ids)).thenReturn(List.of(new CheckoutCatalogMapper.SkuProductRow("10","10"),new CheckoutCatalogMapper.SkuProductRow("2","2"),new CheckoutCatalogMapper.SkuProductRow("9007199254740993","10")));
        when(mapper.lockProduct("2")).thenReturn("2");when(mapper.lockProduct("10")).thenReturn("10");
        when(mapper.lockCategoryAnchor()).thenReturn("1");
        when(mapper.lockEnabledSkus(ids)).thenReturn(List.of(row("2","2"),row("10","10"),row("9007199254740993","10")));
        when(mapper.lockPublicImages(List.of("2","10"))).thenReturn(List.of(new CheckoutCatalogMapper.ImageRow("2","20","a.png","image/png",100),new CheckoutCatalogMapper.ImageRow("2","21","b.webp","image/webp",5242880),new CheckoutCatalogMapper.ImageRow("10","22","c.jpg","image/jpeg",200)));
        var evidence=service.lockOrderEvidence(List.of("10","2","9007199254740993","2"));
        var order=inOrder(mapper);order.verify(mapper).findEnabledSkuProducts(ids);order.verify(mapper).lockProduct("2");order.verify(mapper).lockProduct("10");order.verify(mapper).lockCategoryAnchor();order.verify(mapper).lockEnabledSkus(ids);order.verify(mapper).lockPublicImages(List.of("2","10"));order.verifyNoMoreInteractions();
        assertThat(evidence.skus()).containsOnlyKeys("2","10","9007199254740993");
        assertThat(evidence.imagesByProduct().get("2")).isEqualTo(new CheckoutCatalogService.ImageSnapshot("20","a.png","image/png",100));
        assertThatThrownBy(()->evidence.skus().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->evidence.imagesByProduct().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void missingSkuAndImageRemainMissingForCallerVerification(){
        writableTransaction();initial("3","6");when(mapper.lockEnabledSkus(List.of("3","4"))).thenReturn(List.of(row("3","6")));
        var evidence=service.lockOrderEvidence(List.of("4","3"));
        assertThat(evidence.skus()).containsOnlyKeys("3");assertThat(evidence.imagesByProduct()).isEmpty();
    }
    @Test void noInitialSkuStillRechecksButCannotAcceptAnUnlockedProduct(){
        writableTransaction();when(mapper.lockEnabledSkus(List.of("3"))).thenReturn(List.of(row("3","6")));
        assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).lockProduct(anyString());verify(mapper,never()).lockPublicImages(anyList());
        verify(mapper,never()).lockCategoryAnchor();
    }
    @Test void noInitialSkuAndNoCategoriesReturnEmptyEvidenceWithoutAnchorOrImages(){
        writableTransaction();var evidence=service.lockOrderEvidence(List.of("3"));
        assertThat(evidence.skus()).isEmpty();assertThat(evidence.imagesByProduct()).isEmpty();
        verify(mapper,never()).lockCategoryAnchor();verify(mapper,never()).lockPublicImages(anyList());
    }
    @Test void missingOrInvalidCategoryAnchorWithLockedProductsFailsBeforeJoinedRows(){
        writableTransaction();initial("3","6");
        for(String anchor:Arrays.asList(null,"0","01")){
            when(mapper.lockCategoryAnchor()).thenReturn(anchor);
            assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        }
        verify(mapper,never()).lockEnabledSkus(anyList());verify(mapper,never()).lockPublicImages(anyList());
    }
    @Test void missingOrMismatchingRootStopsBeforeSkuLock(){
        writableTransaction();initial("3","6");
        for(String root:Arrays.asList(null,"7")){
            when(mapper.lockProduct("6")).thenReturn(root);
            assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        }
        verify(mapper,never()).lockEnabledSkus(anyList());verify(mapper,never()).lockPublicImages(anyList());
    }
    @Test void initialUnexpectedDuplicateOrInvalidRowsAreRejectedBeforeRootLocks(){
        writableTransaction();
        for(var rows:List.of(List.of(new CheckoutCatalogMapper.SkuProductRow("4","6")),List.of(new CheckoutCatalogMapper.SkuProductRow("3","06")),List.of(new CheckoutCatalogMapper.SkuProductRow("3","6"),new CheckoutCatalogMapper.SkuProductRow("3","6")))){
            when(mapper.findEnabledSkuProducts(anyList())).thenReturn(rows);
            assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        }
        verify(mapper,never()).lockProduct(anyString());
    }
    @Test void changedProductUnexpectedOrDuplicateFinalRowsFailClosed(){
        writableTransaction();initial("3","6");
        for(var rows:List.of(List.of(row("3","9")),List.of(row("4","6")),List.of(row("3","6"),row("3","6")))){
            when(mapper.lockEnabledSkus(anyList())).thenReturn(rows);
            assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        }
        verify(mapper,never()).lockPublicImages(anyList());
    }
    @Test void invalidPublicImageEvidenceFailsClosed(){
        writableTransaction();initial("3","6");when(mapper.lockEnabledSkus(anyList())).thenReturn(List.of(row("3","6")));
        for(var image:List.of(new CheckoutCatalogMapper.ImageRow("9","20","a.png","image/png",1),new CheckoutCatalogMapper.ImageRow("6","020","a.png","image/png",1),new CheckoutCatalogMapper.ImageRow("6","20"," ","image/png",1),new CheckoutCatalogMapper.ImageRow("6","20","a.svg","image/svg+xml",1),new CheckoutCatalogMapper.ImageRow("6","20","a.png","image/png",5242881),new CheckoutCatalogMapper.ImageRow("6","20","a.png","image/png",-1))){
            when(mapper.lockPublicImages(anyList())).thenReturn(List.of(image));
            assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void nullIdentifiersInReturnedEvidenceFailAsInvalidEvidence(){
        writableTransaction();
        when(mapper.findEnabledSkuProducts(anyList())).thenReturn(List.of(new CheckoutCatalogMapper.SkuProductRow(null,"6")));
        assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        initial("3","6");
        for(var rows:List.of(List.of(row(null,"6")),List.of(row("3",null)))){
            when(mapper.lockEnabledSkus(anyList())).thenReturn(rows);
            assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
        }
        when(mapper.lockEnabledSkus(anyList())).thenReturn(List.of(row("3","6")));
        when(mapper.lockPublicImages(anyList())).thenReturn(List.of(new CheckoutCatalogMapper.ImageRow(null,"20","a.png","image/png",1)));
        assertThatThrownBy(()->service.lockOrderEvidence(List.of("3"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void orderSnapshotCopiesCallerMaps(){
        var skus=new HashMap<String,CheckoutCatalogService.CheckoutSku>();var images=new HashMap<String,CheckoutCatalogService.ImageSnapshot>();
        images.put("6",new CheckoutCatalogService.ImageSnapshot("20","a.png","image/png",1));
        var evidence=new CheckoutCatalogService.OrderEvidence(skus,images);images.clear();
        assertThat(evidence.imagesByProduct()).containsOnlyKeys("6");
    }
    @Test void batchReadsCurrentEnabledCommerceSnapshotAndAllRevalidationVersions(){
        when(mapper.findEnabledSkus(List.of("3"))).thenReturn(List.of(new CheckoutCatalogMapper.Row("3","6","商品","说明",true,"7","分类","8","品牌","CODE","[{\"name\":\"颜色\",\"value\":\"蓝\"}]","100",11,12,13,14)));
        var result=service.skus(List.of("3","3"));var sku=result.get("3");
        assertThat(sku.productName()).isEqualTo("商品");assertThat(sku.specs()).containsExactly(new CatalogModels.Spec("颜色","蓝"));
        assertThat(sku.priceMinor()).isEqualTo("100");assertThat(sku.skuVersion()).isEqualTo(11);assertThat(sku.productVersion()).isEqualTo(12);assertThat(sku.categoryVersion()).isEqualTo(13);assertThat(sku.brandVersion()).isEqualTo(14);
        assertThatThrownBy(()->sku.specs().clear()).isInstanceOf(UnsupportedOperationException.class);assertThatThrownBy(()->result.clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(mapper,times(1)).findEnabledSkus(List.of("3"));
    }
    @Test void invalidIdsNeverReachMapperAndEmptyBatchDoesNotIssueInvalidInSql(){
        assertThat(service.skus(List.of())).isEmpty();
        for(List<String> ids:Arrays.<List<String>>asList(null,List.of("0"),List.of("01"),Collections.singletonList(null),Collections.nCopies(101,"3")))assertThatThrownBy(()->service.skus(ids)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void corruptStoredSpecsFailRatherThanInventingSnapshot(){
        for(String specs:List.of("bad","null","[null]")){
            when(mapper.findEnabledSkus(anyList())).thenReturn(List.of(new CheckoutCatalogMapper.Row("3","6","商品","说明",true,"7","分类","8","品牌","CODE",specs,"100",11,12,13,14)));
            assertThatThrownBy(()->service.skus(List.of("3"))).isInstanceOf(IllegalStateException.class);
        }
    }
}
