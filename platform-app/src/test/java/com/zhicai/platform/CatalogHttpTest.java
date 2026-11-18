package com.zhicai.platform;

import com.zhicai.platform.catalog.CatalogModels.*;
import com.zhicai.platform.catalog.CatalogQueryService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.web.ApiAdvice;
import com.zhicai.platform.web.CatalogController;
import com.zhicai.platform.web.TraceFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.sql.SQLException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogHttpTest {
    CatalogQueryService catalog = mock(CatalogQueryService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CatalogController(catalog))
            .setControllerAdvice(new ApiAdvice()).addFilters(new TraceFilter()).build();
    }

    @Test void publicPagesUseContractEnvelopeAndStringIds() throws Exception {
        when(catalog.categories(1, 20)).thenReturn(new PageData<>(List.of(new Category("101", "显卡", null, 1, true, 0)), 1, 20, 1));
        when(catalog.brands(1, 20)).thenReturn(new PageData<>(List.of(new Brand("201", "NVIDIA", true, 0)), 1, 20, 1));
        mvc.perform(get("/api/v1/catalog/categories")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value("101")).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/catalog/brands")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value("201"));
    }

    @Test void publicProductCarriesFullSchemaAndActualZeroRating() throws Exception {
        var asset = new Asset("301", "http://127.0.0.1:8080/assets/products/rtx-4080-super.jpg", "image/jpeg", 613454);
        var sku = new Sku("3001", "2001", "RTX4080SUPER-16G", List.of(new Spec("显存", "16GB")), "899900", true, 0);
        var product = new Product("2001", "GeForce RTX 4080 SUPER 16GB 显卡", "101", "201", List.of("301"), "GPU", true,
            BigDecimal.ZERO, 0, "ON_SALE", List.of(asset), List.of(sku), 0, Instant.parse("2026-10-20T00:00:00Z"));
        when(catalog.product("2001")).thenReturn(product);
        mvc.perform(get("/api/v1/catalog/products/2001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("2001"))
            .andExpect(jsonPath("$.data.skus[0].priceMinor").value("899900"))
            .andExpect(jsonPath("$.data.skus[0].specs[0].value").value("16GB"))
            .andExpect(jsonPath("$.data.imageAssetIds[0]").value("301"))
            .andExpect(jsonPath("$.data.ratingAverage").value(0))
            .andExpect(jsonPath("$.data.ratingCount").value(0));
    }

    @Test void invalidQueryAndMissingProductKeepSafeEnvelope() throws Exception {
        mvc.perform(get("/api/v1/catalog/products").param("page", "abc"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/catalog/products").param("featured", "maybe"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        when(catalog.product("9999")).thenThrow(new ApiException(404, "NOT_FOUND", "商品不存在"));
        mvc.perform(get("/api/v1/catalog/products/9999"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void databaseFailureReportsCatalogDependency() throws Exception {
        when(catalog.categories(1, 20)).thenThrow(new DataAccessResourceFailureException("private details"));
        mvc.perform(get("/api/v1/catalog/categories"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("商品目录暂不可用"));
    }

    @Test void transactionConnectionFailureReportsSafeCatalogDependency() throws Exception {
        when(catalog.categories(1, 20)).thenThrow(new CannotCreateTransactionException(
            "private connection details", new SQLException("private database address")));
        mvc.perform(get("/api/v1/catalog/categories"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("商品目录暂不可用"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(content().string(not(containsString("private"))));
    }
}
