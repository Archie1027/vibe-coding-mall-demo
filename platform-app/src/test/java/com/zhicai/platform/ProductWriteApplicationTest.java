package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.ProductWriteService;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductWriteApplicationTest {
    final ProductWriteService products = mock(ProductWriteService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<CatalogWriteExecutor> executors = mock(ObjectProvider.class);
    final ProductWriteApplication app = new ProductWriteApplication(products, executors, new ObjectMapper());
    final ProductWriteService.Input input = new ProductWriteService.Input("商品", "1", "2", List.of("3"), "", false);
    AccountView actor(AccountType type, List<String> permissions) { return new AccountView("1", "actor", "Actor", type, permissions, List.of(), List.of(), true, 0); }
    @Test void authorizationPrecedesDependencyResolution() {
        for (AccountView actor : java.util.Arrays.asList(null, actor(AccountType.CUSTOMER, List.of("catalog:write")), actor(AccountType.STAFF,List.of("catalog:read"))))
            assertThatThrownBy(() -> app.saveProduct(actor,null,input,null,"test-idempotency-key", "trace")).isInstanceOf(ApiException.class);
        verifyNoInteractions(products, executors);
    }
    @Test void invalidInputAndMissingKeyCannotClaimARequest() {
        var actor = actor(AccountType.STAFF,List.of("catalog:write"));
        assertThatThrownBy(() -> app.saveProduct(actor,null,input,null,null,"trace")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> app.saveSku(actor,"1",null,new ProductWriteService.SkuInput("C",List.of(),"0",true),null,"test-idempotency-key","trace")).isInstanceOf(ApiException.class);
        verifyNoInteractions(products, executors);
    }
    @Test void disabledWritesFailClosedForAllOperations() {
        var actor = actor(AccountType.STAFF,List.of("catalog:write"));
        assertThatThrownBy(() -> app.saveProduct(actor,null,input,null,"test-idempotency-key","trace"))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(503));
        assertThatThrownBy(() -> app.state(actor,"1","ON_SALE",0L,"test-idempotency-key","trace"))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(503));
        verifyNoInteractions(products);
    }
}
