package com.zhicai.platform.catalog;

import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrandWriteServiceTest {
    final BrandWriteMapper mapper = mock(BrandWriteMapper.class);
    final BrandWriteService service = new BrandWriteService(mapper);
    @Test void createsUsingGeneratedIdentityAndReturnsAuditAfterState() {
        when(mapper.insert(any())).thenAnswer(call -> { ((BrandWriteMapper.NewBrand) call.getArgument(0)).id = 9007199254740993L; return 1; });
        var change = service.create("演示品牌", true);
        assertThat(change.before()).isNull();
        assertThat(change.after()).isEqualTo(new CatalogModels.Brand("9007199254740993", "演示品牌", true, 0));
    }
    @Test void updateIncludesVersionAndReturnsExactBeforeAfter() {
        var before = new CatalogModels.Brand("1", "原名", true, 3);
        when(mapper.find("1")).thenReturn(before);
        when(mapper.update("1", "新名", false, 3)).thenReturn(1);
        var change = service.update("1", "新名", false, 3L);
        assertThat(change.before()).isEqualTo(before);
        assertThat(change.after()).isEqualTo(new CatalogModels.Brand("1", "新名", false, 4));
    }
    @Test void missingResourceDoesNotAttemptUpdate() {
        assertThatThrownBy(() -> service.update("1", "品牌", true, 0L))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        verify(mapper, never()).update(any(), any(), anyBoolean(), anyLong());
    }
    @Test void staleVersionAndConcurrentLostUpdateReturnConflict() {
        when(mapper.find("1")).thenReturn(new CatalogModels.Brand("1", "原名", true, 3));
        for (long version : new long[]{2, 3})
            assertThatThrownBy(() -> service.update("1", "新名", true, version)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
            });
    }
    @Test void invalidInputNeverReachesMapper() {
        for (String name : new String[]{null, "", " ", "a".repeat(121)})
            assertThatThrownBy(() -> service.create(name, true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create("品牌", null)).isInstanceOf(ApiException.class);
        for (String id : new String[]{null, "0", "01", "1 OR 1", "1".repeat(20)})
            assertThatThrownBy(() -> service.update(id, "品牌", true, 0L)).isInstanceOf(ApiException.class);
        for (Long version : new Long[]{null, -1L, 9007199254740991L})
            assertThatThrownBy(() -> service.update("1", "品牌", true, version)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
    @Test void nameLimitCountsUnicodeCodePointsWithoutTrimmingOriginalValue() {
        String name = "😀".repeat(120);
        assertThatCode(() -> BrandWriteService.validate(null, name, true, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BrandWriteService.validate(null, name + "x", true, null)).isInstanceOf(ApiException.class);
        assertThatCode(() -> BrandWriteService.validate(null, " 品牌 ", true, null)).doesNotThrowAnyException();
    }
}
