package com.zhicai.platform;

import com.zhicai.platform.catalog.CatalogQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;

/** Read-only verification against the approved 1,000-product development catalog. */
@EnabledIfEnvironmentVariable(named="M1_CATALOG_MYSQL_INTEGRATION", matches="true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.flyway.enabled=false")
class MySqlCatalogIntegrationTest {
    @Autowired CatalogQueryService catalog;
    @Autowired JdbcTemplate jdbc;

    @Test void adminCatalogReadsActualTablesWithSearchAndPaging() {
        assertThat(catalog.adminCategories(1, 100, null).total()).isEqualTo(15);
        assertThat(catalog.adminBrands(1, 100, null).total()).isEqualTo(26);
        var first = catalog.adminProducts(1, 20, null);
        var second = catalog.adminProducts(2, 20, null);
        assertThat(first.total()).isEqualTo(1000);
        assertThat(first.items()).hasSize(20);
        assertThat(second.items()).hasSize(20).noneMatch(p -> first.items().stream().anyMatch(q -> q.id().equals(p.id())));
        assertThat(catalog.adminProducts(1, 20, "手机").total()).isEqualTo(100);
        assertThat(catalog.adminProducts(1, 20, "%_").total()).isZero();
        assertThat(catalog.adminProduct("2001").skus().getFirst().priceMinor()).isEqualTo("899900");
        assertThatThrownBy(() -> catalog.adminProduct("9999999")).isInstanceOf(com.zhicai.platform.common.ApiException.class);
    }

    @Test void approvedSeedAndPublicQueriesHaveExpectedShape() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1", Long.class)).isEqualTo(1);
        assertThat(catalog.categories(1, 100).total()).isEqualTo(15);
        assertThat(catalog.brands(1, 100).total()).isEqualTo(26);
        var all = catalog.products(1, 100, null, null, null, null, null, null, "NEWEST");
        assertThat(all.total()).isEqualTo(1000);
        assertThat(all.items()).hasSize(100).allSatisfy(p -> {
            assertThat(p.images()).hasSize(1);
            assertThat(p.skus()).hasSize(1);
            assertThat(p.skus().getFirst().priceMinor()).matches("[1-9][0-9]{0,17}");
            assertThat(p.skus().getFirst().specs()).isNotEmpty();
            assertThat(p.ratingCount()).isZero();
            assertThat(p.ratingAverage()).isZero();
        });
        String[] originalPrices = {"899900", "289900", "119900", "89900", "529900", "69900"};
        for (int index = 0; index < originalPrices.length; index++) {
            var original = catalog.product(String.valueOf(2001 + index));
            assertThat(original.skus().getFirst().priceMinor()).isEqualTo(originalPrices[index]);
        }
        assertThat(catalog.products(1, 100, null, null, null, "899900", "899900", null, "PRICE_ASC").items())
            .extracting(p -> p.id()).contains("2001");
        assertThat(catalog.product("2006").images().getFirst().url()).endsWith("samsung-980-1tb.png");
        var sample = catalog.product("5001");
        assertThat(sample.name()).contains("开发测试 5001");
        assertThat(sample.images().getFirst().id()).isEqualTo(catalog.product("2001").images().getFirst().id());
        assertThat(sample.skus().getFirst().code()).isEqualTo("PERF-7001");
        assertThat(catalog.cartSkus(List.of("7001")).get("7001").imageUrl()).isEqualTo(sample.images().getFirst().url());
    }

    @Test void searchAndFullCatalogPaginateWithoutDuplicatesOrMissingProducts() {
        var expectedIds = new ArrayList<String>(List.of("2001", "2002", "2003", "2004", "2005", "2006"));
        expectedIds.addAll(LongStream.rangeClosed(2101, 2214).mapToObj(String::valueOf).toList());
        expectedIds.addAll(LongStream.rangeClosed(5001, 5880).mapToObj(String::valueOf).toList());
        var actualIds = new ArrayList<String>();
        for (int page = 1; page <= 84; page++) {
            var result = catalog.products(page, 12, null, null, null, null, null, null, "NEWEST");
            assertThat(result.total()).isEqualTo(1000);
            assertThat(result.page()).isEqualTo(page);
            assertThat(result.items()).hasSize(page < 84 ? 12 : 4);
            actualIds.addAll(result.items().stream().map(p -> p.id()).toList());
        }
        assertThat(actualIds).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(catalog.products(85, 12, null, null, null, null, null, null, "NEWEST").items()).isEmpty();

        var samsungIds = new ArrayList<String>();
        for (int page = 1; page <= 25; page++) {
            var result = catalog.products(page, 12, "三星", null, null, null, null, null, "PRICE_ASC");
            assertThat(result.total()).isEqualTo(300);
            assertThat(result.items()).hasSize(12)
                .allSatisfy(p -> assertThat(p.name()).contains("三星"));
            samsungIds.addAll(result.items().stream().map(p -> p.id()).toList());
        }
        assertThat(samsungIds).hasSize(300).doesNotHaveDuplicates();
        assertThat(catalog.products(1, 12, null, "110", null, null, null, null, "NEWEST").total()).isEqualTo(100);
        assertThat(catalog.products(1, 12, "不存在的型号", null, null, null, null, null, null).total()).isZero();
    }
}
