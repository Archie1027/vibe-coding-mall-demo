package com.zhicai.platform.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.time.Instant;
import java.util.List;
import java.math.BigDecimal;
import static com.zhicai.platform.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductWriteServiceTest {
    final ProductWriteMapper mapper = mock(ProductWriteMapper.class);
    final CatalogQueryService queries = mock(CatalogQueryService.class);
    final ProductWriteService service = new ProductWriteService(mapper, queries, new ObjectMapper());
    final ProductWriteService.Input input = new ProductWriteService.Input("测试商品", "1", "2", List.of("3"), "<script>plain</script>", true);
    final ProductWriteService.SkuInput skuInput = new ProductWriteService.SkuInput("TEST-1", List.of(new Spec("颜色", "黑")), "9007199254740993", true);
    @BeforeEach void ready() {
        when(mapper.categoryEnabled("1")).thenReturn(true); when(mapper.brandEnabled("2")).thenReturn(true);
        when(mapper.publicAssets(List.of("3"))).thenReturn(List.of("3"));
        when(mapper.lockProduct("10")).thenReturn("10");
        when(queries.adminProduct("10")).thenReturn(product("DRAFT", List.of(sku(true))));
        when(mapper.findSku("20")).thenReturn(row());
        when(mapper.skus("10")).thenReturn(List.of(row()));
        when(mapper.insertProduct(any())).thenAnswer(call -> { ((ProductWriteMapper.NewProduct)call.getArgument(0)).id = 10L; return 1; });
        when(mapper.insertSku(any())).thenAnswer(call -> { ((ProductWriteMapper.NewSku)call.getArgument(0)).id = 20L; return 1; });
        when(mapper.insertImage(any(), any(), anyInt())).thenReturn(1);
    }
    @Test void createDraftKeepsPlainTextAndOrderedControlledAssets() {
        var change = service.create(input);
        assertThat(change.before()).isNull(); assertThat(change.after().state()).isEqualTo("DRAFT");
        var order = inOrder(mapper); order.verify(mapper).categoryEnabled("1"); order.verify(mapper).brandEnabled("2");
        order.verify(mapper).publicAssets(List.of("3")); order.verify(mapper).insertProduct(any()); order.verify(mapper).insertImage("10", "3", 0);
    }
    @Test void rejectsInvalidProductFieldsBeforeDatabaseAccess() {
        for (var bad : List.of(new ProductWriteService.Input(" ", "1", "2", List.of("3"), "", false),
            new ProductWriteService.Input("商品", "01", "2", List.of("3"), "", false),
            new ProductWriteService.Input("商品", "1", "2", List.of("3", "3"), "", false),
            new ProductWriteService.Input("商品", "1", "2", List.of(), "", false),
            new ProductWriteService.Input("商品", "1", "2", List.of("3"), "x".repeat(10001), false)))
            code(() -> service.create(bad), 400, "VALIDATION_ERROR");
        verifyNoInteractions(mapper, queries);
    }
    @Test void missingDictionariesOrNonPublicImagesCannotBeAttached() {
        when(mapper.categoryEnabled("1")).thenReturn(null); code(() -> service.create(input), 404, "NOT_FOUND");
        when(mapper.categoryEnabled("1")).thenReturn(true); when(mapper.publicAssets(anyList())).thenReturn(List.of());
        code(() -> service.create(input), 400, "INVALID_IMAGE_ASSET"); verify(mapper, never()).insertProduct(any());
    }
    @Test void staleVersionCannotReplaceImages() {
        code(() -> service.update("10", input, 1L), 409, "VERSION_CONFLICT"); verify(mapper, never()).deleteImages(any());
    }
    @Test void updateLocksRootAndUsesOptimisticVersionBeforeReplacingImages() {
        when(mapper.updateProduct("10", input, 0)).thenReturn(1);
        service.update("10", input, 0L);
        var order = inOrder(mapper, queries); order.verify(mapper).lockProduct("10"); order.verify(queries).adminProduct("10");
        order.verify(mapper).categoryEnabled("1"); order.verify(mapper).brandEnabled("2"); order.verify(mapper).publicAssets(anyList());
        order.verify(mapper).updateProduct("10", input, 0); order.verify(mapper).deleteImages("10"); order.verify(mapper).insertImage("10", "3", 0);
    }
    @Test void publicationRequiresEnabledDictionariesAndAnEnabledSku() {
        when(queries.adminProduct("10")).thenReturn(product("DRAFT", List.of(sku(false))));
        code(() -> service.state("10", "ON_SALE", 0L), 409, "PRODUCT_NOT_PUBLISHABLE");
        when(queries.adminProduct("10")).thenReturn(product("DRAFT", List.of(sku(true)))); when(mapper.brandEnabled("2")).thenReturn(false);
        code(() -> service.state("10", "ON_SALE", 0L), 409, "PRODUCT_NOT_PUBLISHABLE");
        verify(mapper, never()).updateState(any(), any(), anyLong());
    }
    @Test void publicationChecksCurrentImagesAndAllowsOffSaleWithDisabledDictionaries() {
        when(mapper.publicAssets(anyList())).thenReturn(List.of());
        code(() -> service.state("10", "ON_SALE", 0L), 400, "INVALID_IMAGE_ASSET");
        when(mapper.updateState("10", "OFF_SALE", 0)).thenReturn(1);
        service.state("10", "OFF_SALE", 0L); verify(mapper).updateState("10", "OFF_SALE", 0);
    }
    @Test void cannotChangeStateToDraftOrUseUnsafeVersions() {
        code(() -> service.state("10", "DRAFT", 0L), 400, "VALIDATION_ERROR");
        code(() -> service.update("10", input, 9007199254740991L), 400, "VALIDATION_ERROR"); verifyNoInteractions(mapper, queries);
    }
    @Test void skuPriceRemainsAnExactStringAndParentIsLockedBeforeRead() {
        var change = service.createSku("10", skuInput);
        assertThat(change.after().priceMinor()).isEqualTo("9007199254740993");
        var order = inOrder(mapper); order.verify(mapper).lockProduct("10"); order.verify(mapper).skus("10"); order.verify(mapper).insertSku(any());
    }
    @Test void rejectsDuplicateCombinationEvenWhenLegacyStoredHashDiffersOrOrderChanges() {
        when(mapper.skus("10")).thenReturn(List.of(new CatalogMapper.SkuRow("21", "10", "OTHER", "[{\"value\":\"黑\",\"name\":\"颜色\"},{\"name\":\"容量\",\"value\":\"1TB\"}]", "100", true, 0)));
        var reordered = new ProductWriteService.SkuInput("NEW", List.of(new Spec("容量", "1TB"), new Spec("颜色", "黑")), "100", true);
        code(() -> service.createSku("10", reordered), 409, "SKU_SPECS_CONFLICT"); verify(mapper, never()).insertSku(any());
    }
    @Test void rejectsInvalidSkuCodePriceAndDuplicateSpecNamesWithoutQueries() {
        for (var bad : List.of(new ProductWriteService.SkuInput("中文", skuInput.specs(), "100", true),
            new ProductWriteService.SkuInput("C", skuInput.specs(), "0", true),
            new ProductWriteService.SkuInput("C", skuInput.specs(), "1.5", true),
            new ProductWriteService.SkuInput("C", skuInput.specs(), "1000000000000000000", true),
            new ProductWriteService.SkuInput("C", List.of(new Spec("颜色", "黑"), new Spec("颜色", "白")), "100", true)))
            code(() -> service.createSku("10", bad), 400, "VALIDATION_ERROR");
        verifyNoInteractions(mapper, queries);
    }
    @Test void uniqueCodeRaceIsReportedAsAConflict() {
        doThrow(new DuplicateKeyException("must not expose SQL")).when(mapper).insertSku(any());
        code(() -> service.createSku("10", skuInput), 409, "SKU_CONFLICT");
    }
    @Test void cannotDisableLastEnabledSkuOfOnSaleProduct() {
        when(queries.adminProduct("10")).thenReturn(product("ON_SALE", List.of(sku(true))));
        var disabled = new ProductWriteService.SkuInput("TEST-1", skuInput.specs(), "100", false);
        code(() -> service.updateSku("20", disabled, 0L), 409, "PRODUCT_NOT_PUBLISHABLE");
        verify(mapper, never()).updateSku(any(), any(), any(), any(), anyLong());
    }
    @Test void missingRootOrSkuAndStaleSkuVersionAreSafeFailures() {
        when(mapper.lockProduct("10")).thenReturn(null); code(() -> service.createSku("10", skuInput), 404, "NOT_FOUND");
        when(mapper.findSku("20")).thenReturn(null); code(() -> service.updateSku("20", skuInput, 0L), 404, "NOT_FOUND");
        when(mapper.lockProduct("10")).thenReturn("10"); when(mapper.findSku("20")).thenReturn(row());
        code(() -> service.updateSku("20", skuInput, 1L), 409, "VERSION_CONFLICT");
    }
    @Test void successfulSkuEditPreservesBeforeStateAndIncrementsOnlyItsOwnVersion() {
        when(mapper.updateSku(eq("20"), eq(skuInput), anyString(), anyString(), eq(0L))).thenReturn(1);
        var change = service.updateSku("20", skuInput, 0L);
        assertThat(change.before().specs()).containsExactly(new Spec("颜色", "白"));
        assertThat(change.after().specs()).containsExactly(new Spec("颜色", "黑"));
        assertThat(change.after().version()).isEqualTo(1); assertThat(change.after().priceMinor()).isEqualTo("9007199254740993");
        var order = inOrder(mapper); order.verify(mapper).findSku("20"); order.verify(mapper).lockProduct("10"); order.verify(mapper).findSku("20");
        verify(mapper, never()).updateProduct(any(), any(), anyLong());
    }
    @Test void editingAnOnSaleProductCannotSwitchToADisabledBrand() {
        when(queries.adminProduct("10")).thenReturn(product("ON_SALE", List.of(sku(true))));
        when(mapper.brandEnabled("2")).thenReturn(false);
        code(() -> service.update("10", input, 0L), 409, "PRODUCT_NOT_PUBLISHABLE");
        verify(mapper, never()).updateProduct(any(), any(), anyLong()); verify(mapper, never()).deleteImages(any());
    }
    Product product(String state, List<Sku> skus) { return new Product("10", input.name(), "1", "2", List.of("3"), input.description(), true, BigDecimal.ZERO, 0, state, List.of(new Asset("3", "http://localhost/image.jpg", "image/jpeg", 200)), skus, 0, Instant.EPOCH); }
    Sku sku(boolean enabled) { return new Sku("20", "10", "TEST-1", List.of(new Spec("颜色", "黑")), "100", enabled, 0); }
    CatalogMapper.SkuRow row() { return new CatalogMapper.SkuRow("20", "10", "OLD", "[{\"name\":\"颜色\",\"value\":\"白\"}]", "100", true, 0); }
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(status); assertThat(e.code()).isEqualTo(code); });
    }
}
