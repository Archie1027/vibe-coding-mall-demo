package com.zhicai.platform.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogQueryServiceTest {
    CatalogMapper mapper = mock(CatalogMapper.class);
    CatalogQueryService service = new CatalogQueryService(mapper, new ObjectMapper(), "http://127.0.0.1:8080/assets/products/");

    @Test void cartSkuLookupBatchesCurrentDataAndRetainsUnavailableReasons() {
        when(mapper.findCartSkus(List.of("1","2"))).thenReturn(List.of(
            new CatalogMapper.CartSkuRow("1","商品","[{\"name\":\"颜色\",\"value\":\"黑\"}]","9007199254740993",true,"ON_SALE",true,true,"item.jpg"),
            new CatalogMapper.CartSkuRow("2","已下架商品","[]","100",true,"OFF_SALE",true,true,null)));
        var result=service.cartSkus(List.of("1","2"));assertThat(result.get("1").priceMinor()).isEqualTo("9007199254740993");
        assertThat(result.get("1").valid()).isTrue();assertThat(result.get("1").invalidReason()).isNull();assertThat(result.get("1").imageUrl()).isEqualTo("http://127.0.0.1:8080/assets/products/item.jpg");assertThat(result.get("2").valid()).isFalse();
        assertThat(result.get("2").invalidReason()).isEqualTo("商品已下架");verify(mapper).findCartSkus(List.of("1","2"));
        assertThat(service.cartSkus(List.of())).isEmpty();assertThatThrownBy(()->service.cartSkus(List.of("0"))).isInstanceOf(ApiException.class);
    }

    @Test void adminReadsDraftsDisabledSkusAndEscapesSearchWithoutPublicFiltering() {
        var row = new CatalogMapper.ProductRow("2001", "草稿", "1", "2", "", false, "DRAFT", 0, 0, 3, Instant.now());
        when(mapper.findAdminProduct("2001")).thenReturn(row);
        when(mapper.findAdminSkus(List.of("2001"))).thenReturn(List.of(new CatalogMapper.SkuRow("3001", "2001", "DRAFT", "[]", "100", false, 1)));
        var result = service.adminProduct("2001");
        assertThat(result.state()).isEqualTo("DRAFT");
        assertThat(result.skus().getFirst().enabled()).isFalse();
        service.adminProducts(2, 20, "50%_");
        verify(mapper).findAdminProducts(20, 20, "%50!%!_%");
        verify(mapper, never()).findProduct(any());
        assertThatThrownBy(() -> service.adminProducts(0, 20, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.adminProduct("0")).isInstanceOf(ApiException.class);
    }

    @Test void rejectsInvalidFiltersBeforeQuery() {
        assertThatThrownBy(() -> service.products(0, 20, null, null, null, null, null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.products(1, 101, null, null, null, null, null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.products(1, 20, null, null, null, null, null, null, "price asc")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.products(1, 20, null, null, null, "200", "100", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.product("0")).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    @Test void escapesLiteralLikeAndBatchesChildren() {
        var row = new CatalogMapper.ProductRow("2001", "GPU", "1", "2", "", true, "ON_SALE", 0L, 0L, 0L, Instant.parse("2026-10-20T00:00:00Z"));
        when(mapper.countProducts(any())).thenReturn(1L);
        when(mapper.findProducts(any())).thenReturn(List.of(row));
        when(mapper.findSkus(List.of("2001"))).thenReturn(List.of(new CatalogMapper.SkuRow("3001", "2001", "GPU-16G", "[{\"name\":\"显存\",\"value\":\"16GB\"}]", "899900", true, 0L)));
        when(mapper.findAssets(List.of("2001"))).thenReturn(List.of(new CatalogMapper.AssetRow("4001", "2001", "rtx-4080-super.jpg", "image/jpeg", 613454L)));
        var result = service.products(1, 20, "50%_\\", null, null, null, null, null, "NEWEST");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().skus().getFirst().specs().getFirst().value()).isEqualTo("16GB");
        assertThat(result.items().getFirst().images().getFirst().url()).isEqualTo("http://127.0.0.1:8080/assets/products/rtx-4080-super.jpg");
        assertThat(result.items().getFirst().ratingAverage()).isEqualByComparingTo(BigDecimal.ZERO);
        var filter = org.mockito.ArgumentCaptor.forClass(CatalogMapper.ProductFilter.class);
        verify(mapper).countProducts(filter.capture());
        assertThat(filter.getValue().keyword()).isEqualTo("%50!%!_\\%");
        verify(mapper).findSkus(List.of("2001"));
        verify(mapper).findAssets(List.of("2001"));
    }

    @Test void keepsLargeIdsAndMoneyAsExactDecimalStrings() {
        String aboveDoublePrecision = "9007199254740993";
        when(mapper.findProducts(any())).thenReturn(List.of());
        service.products(1, 20, null, aboveDoublePrecision, aboveDoublePrecision,
            aboveDoublePrecision, aboveDoublePrecision, null, "PRICE_ASC");
        var filter = org.mockito.ArgumentCaptor.forClass(CatalogMapper.ProductFilter.class);
        verify(mapper).countProducts(filter.capture());
        assertThat(filter.getValue().categoryId()).isEqualTo(aboveDoublePrecision);
        assertThat(filter.getValue().brandId()).isEqualTo(aboveDoublePrecision);
        assertThat(filter.getValue().minPriceMinor()).isEqualTo(aboveDoublePrecision);
        assertThat(filter.getValue().maxPriceMinor()).isEqualTo(aboveDoublePrecision);
        assertThatThrownBy(() -> service.product(aboveDoublePrecision)).isInstanceOf(ApiException.class);
        verify(mapper).findProduct(aboveDoublePrecision);
    }
}
