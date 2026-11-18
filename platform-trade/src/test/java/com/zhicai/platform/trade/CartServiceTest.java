package com.zhicai.platform.trade;

import com.zhicai.platform.catalog.CatalogModels.CartSku;
import com.zhicai.platform.catalog.CatalogModels.Spec;
import com.zhicai.platform.catalog.CatalogQueryService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.CustomerLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartServiceTest {
    final CartMapper mapper = mock(CartMapper.class);
    final CustomerLockService locks = mock(CustomerLockService.class);
    final CatalogQueryService catalog = mock(CatalogQueryService.class);
    final CartService service = new CartService(mapper, locks, catalog);
    final CartSku sku = new CartSku("51", "商品", List.of(new Spec("颜色", "黑")), "19900", true, null, "https://example.test/item.jpg");
    @BeforeEach void defaults() {
        when(catalog.cartSkus(List.of("51"))).thenReturn(Map.of("51", sku));
        when(mapper.insert(any())).thenAnswer(call -> { ((CartMapper.NewItem)call.getArgument(0)).id = 31L; return 1; });
        when(mapper.update(any(), any(), anyInt(), anyBoolean())).thenReturn(1);
    }
    @Test void newSkuLocksBeforeReadAndDefaultsToSelected() {
        var result = service.add("7", "51", 2); assertThat(result.before()).isNull();
        assertThat(result.after().id()).isEqualTo("31"); assertThat(result.after().quantity()).isEqualTo(2);
        assertThat(result.after().selected()).isTrue(); assertThat(result.after().priceMinor()).isEqualTo("19900");
        assertThat(result.after().imageUrl()).isEqualTo("https://example.test/item.jpg");
        var order = inOrder(locks, mapper); order.verify(locks).lock("7"); order.verify(mapper).findBySku("7", "51"); order.verify(mapper).insert(any());
    }
    @Test void duplicateSkuMergesAtMost99WithoutReselectingExistingRow() {
        when(mapper.findBySku("7", "51")).thenReturn(new CartMapper.Row("31", "51", 97, false));
        var result = service.add("7", "51", 2);
        assertThat(result.before().quantity()).isEqualTo(97); assertThat(result.after().quantity()).isEqualTo(99); assertThat(result.after().selected()).isFalse();
        verify(mapper).update("7", "31", 99, false); verify(mapper, never()).insert(any());
    }
    @Test void mergeBeyond99Is409AndNeverSilentlyClamps() {
        when(mapper.findBySku("7", "51")).thenReturn(new CartMapper.Row("31", "51", 98, true));
        code(() -> service.add("7", "51", 2), 409, "CART_QUANTITY_EXCEEDED");
        verify(mapper, never()).insert(any()); verify(mapper, never()).update(any(), any(), anyInt(), anyBoolean());
    }
    @Test void invalidOrMissingSkuCannotBeAdded() {
        when(catalog.cartSkus(List.of("51"))).thenReturn(Map.of("51", invalidSku()));
        code(() -> service.add("7", "51", 1), 409, "SKU_NOT_AVAILABLE");
        when(catalog.cartSkus(List.of("51"))).thenReturn(Map.of());
        code(() -> service.add("7", "51", 1), 409, "SKU_NOT_AVAILABLE"); verify(mapper, never()).insert(any());
    }
    @Test void listingUsesOwnerOffsetAndOneBatchToKeepInvalidRowsVisible() {
        when(mapper.list("7", 20, 20)).thenReturn(List.of(new CartMapper.Row("31", "51", 2, true), new CartMapper.Row("32", "52", 3, false)));
        when(mapper.count("7")).thenReturn(22L); when(catalog.cartSkus(List.of("51", "52"))).thenReturn(Map.of("51", sku, "52", new CartSku("52", "下架商品", List.of(), "20900", false, "商品已下架", null)));
        var result = service.list("7", 2, 20); assertThat(result.total()).isEqualTo(22); assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).valid()).isTrue(); assertThat(result.items().get(1).valid()).isFalse();
        assertThat(result.items().get(1).invalidReason()).isEqualTo("商品已下架"); verify(catalog, times(1)).cartSkus(anyList()); verifyNoInteractions(locks);
    }
    @Test void emptyPageDoesNotQueryCatalogAndRetainsTotal() {
        when(mapper.list("7", 100, 20)).thenReturn(List.of()); when(mapper.count("7")).thenReturn(5L);
        var result = service.list("7", 6, 20); assertThat(result.items()).isEmpty(); assertThat(result.total()).isEqualTo(5); verifyNoInteractions(catalog);
    }
    @Test void invalidRowCanBeUncheckedAndQuantityChangedButCannotBeSelected() {
        when(mapper.find("7", "31")).thenReturn(new CartMapper.Row("31", "51", 2, true));
        when(catalog.cartSkus(List.of("51"))).thenReturn(Map.of("51", invalidSku()));
        var change = service.update("7", "31", 3, false); assertThat(change.after().quantity()).isEqualTo(3); assertThat(change.after().selected()).isFalse();
        assertThat(change.after().valid()).isFalse();
        code(() -> service.update("7", "31", 3, true), 409, "SKU_NOT_AVAILABLE"); verify(mapper, times(1)).update("7", "31", 3, false);
    }
    @Test void anotherOwnersRowIs404WithoutReadingCatalogOrWriting() {
        code(() -> service.update("8", "31", 1, false), 404, "NOT_FOUND"); code(() -> service.delete("8", "31"), 404, "NOT_FOUND");
        verifyNoInteractions(catalog); verify(mapper, never()).delete(any(), any()); verify(mapper, never()).update(any(), any(), anyInt(), anyBoolean());
    }
    @Test void invalidSkuCanStillBeDeletedAndReturnsOriginalAuditState() {
        when(mapper.find("7", "31")).thenReturn(new CartMapper.Row("31", "51", 2, true)); when(mapper.delete("7", "31")).thenReturn(1);
        when(catalog.cartSkus(List.of("51"))).thenReturn(Map.of("51", invalidSku()));
        var result = service.delete("7", "31"); assertThat(result.before().valid()).isFalse(); assertThat(result.after()).isNull();
        var order = inOrder(locks, mapper); order.verify(locks).lock("7"); order.verify(mapper).find("7", "31"); order.verify(mapper).delete("7", "31");
    }
    @Test void validatesAllRequiredFieldsBeforeOwnerLockOrCatalogRead() {
        for (Integer qty : new Integer[]{null, 0, -1, 100}) { code(() -> service.add("7", "51", qty), 400, "VALIDATION_ERROR"); code(() -> service.update("7", "31", qty, true), 400, "VALIDATION_ERROR"); }
        for (String id : new String[]{null, "01", "0", "-1", "10000000000000000000"}) code(() -> service.add("7", id, 1), 400, "VALIDATION_ERROR");
        code(() -> service.update("7", "31", 1, null), 400, "VALIDATION_ERROR");
        code(() -> service.list("7", 1, 101), 400, "VALIDATION_ERROR"); code(() -> service.list("7", 0, 20), 400, "VALIDATION_ERROR");
        verifyNoInteractions(locks, mapper, catalog);
    }
    @Test void writeMethodsRequireCallersTransaction() throws Exception {
        for (var method : CartService.class.getDeclaredMethods()) if (Set.of("add", "update", "delete").contains(method.getName()))
            assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        for (var method : AddressService.class.getDeclaredMethods()) if (Set.of("create", "update", "delete").contains(method.getName()))
            assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
    private CartSku invalidSku() { return new CartSku("51", "商品", List.of(), "19900", false, "商品已下架", null); }
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(status); assertThat(e.code()).isEqualTo(code); });
    }
}
