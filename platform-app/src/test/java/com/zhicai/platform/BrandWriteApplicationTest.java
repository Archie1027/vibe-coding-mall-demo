package com.zhicai.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.catalog.*;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.write.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrandWriteApplicationTest {
    final BrandWriteService brands = mock(BrandWriteService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<CatalogWriteExecutor> provider = mock(ObjectProvider.class);
    final BrandWriteApplication app = new BrandWriteApplication(brands, provider, new ObjectMapper());
    final AccountView writer = new AccountView("1", "writer", "Writer", AccountType.STAFF, List.of("catalog:write"), List.of(), List.of(), true, 0);
    final String key = UUID.randomUUID().toString(), trace = UUID.randomUUID().toString();

    @Test void invalidOrUnauthorizedRequestCannotClaimAndDisabledWriterIsExplicit503() {
        assertThatThrownBy(() -> app.save(null, null, "品牌", true, null, key, trace)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> app.save(writer, null, " ", true, null, key, trace)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> app.save(writer, null, "品牌", true, null, null, trace)).isInstanceOf(ApiException.class);
        verifyNoInteractions(provider, brands);
        assertThatThrownBy(() -> app.save(writer, null, "品牌", true, null, key, trace))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(503));
        verifyNoInteractions(brands);
    }
}
