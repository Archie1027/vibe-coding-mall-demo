package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.CustomerLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.zhicai.platform.trade.TradeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AddressServiceTest {
    final AddressMapper mapper = mock(AddressMapper.class);
    final CustomerLockService locks = mock(CustomerLockService.class);
    final AddressService service = new AddressService(mapper, locks);
    final AddressInput input = input(false);
    @BeforeEach void defaults() {
        when(mapper.insert(any())).thenAnswer(call -> { ((AddressMapper.NewAddress)call.getArgument(0)).id = 31L; return 1; });
    }
    @Test void firstAddressCanRemainNonDefaultAndReadsOnlyAfterOwnerLock() {
        var result = service.create("7", input);
        assertThat(result.before()).isNull(); assertThat(result.after()).isEqualTo(address("31", false, 0));
        var order = inOrder(locks, mapper); order.verify(locks).lock("7"); order.verify(mapper).count("7"); order.verify(mapper).insert(any());
        verify(mapper, never()).clearDefault(any(), any());
    }
    @Test void twentiethAddressAllowedButTwentyFirstRejectedWithoutWrite() {
        when(mapper.count("7")).thenReturn(19L, 20L);
        assertThat(service.create("7", input).after().id()).isEqualTo("31");
        code(() -> service.create("7", input), 409, "ADDRESS_LIMIT_EXCEEDED"); verify(mapper, times(1)).insert(any());
    }
    @Test void createDefaultClearsPreviousDefaultBeforeInsert() {
        assertThat(service.create("7", input(true)).after().isDefault()).isTrue();
        var order = inOrder(locks, mapper); order.verify(locks).lock("7"); order.verify(mapper).count("7");
        order.verify(mapper).clearDefault("7", null); order.verify(mapper).insert(any());
    }
    @Test void updateChecksCurrentOwnerAndVersionBeforeChangingDefault() {
        when(mapper.find("7", "31")).thenReturn(address("31", false, 3));
        when(mapper.update("7", "31", input(true), 3)).thenReturn(1);
        var result = service.update("7", "31", input(true), 3L);
        assertThat(result.before()).isEqualTo(address("31", false, 3)); assertThat(result.after()).isEqualTo(address("31", true, 4));
        var order = inOrder(locks, mapper); order.verify(locks).lock("7"); order.verify(mapper).find("7", "31");
        order.verify(mapper).clearDefault("7", "31"); order.verify(mapper).update("7", "31", input(true), 3);
    }
    @Test void missingOrOtherOwnerAddressIs404WithoutDefaultChange() {
        code(() -> service.update("8", "31", input(true), 0L), 404, "NOT_FOUND");
        code(() -> service.delete("8", "31"), 404, "NOT_FOUND");
        verify(mapper, never()).clearDefault(any(), any()); verify(mapper, never()).delete(any(), any());
    }
    @Test void staleVersionDoesNotChangeDefaultsOrOverwrite() {
        when(mapper.find("7", "31")).thenReturn(address("31", false, 4));
        code(() -> service.update("7", "31", input(true), 3L), 409, "VERSION_CONFLICT");
        verify(mapper, never()).clearDefault(any(), any()); verify(mapper, never()).update(any(), any(), any(), anyLong());
    }
    @Test void updateCompareAndSetFailureIs409() {
        when(mapper.find("7", "31")).thenReturn(address("31", false, 3));
        code(() -> service.update("7", "31", input, 3L), 409, "VERSION_CONFLICT");
    }
    @Test void deleteReturnsOriginalOnlyAndNeverPromotesAnotherAddress() {
        when(mapper.find("7", "31")).thenReturn(address("31", true, 3)); when(mapper.delete("7", "31")).thenReturn(1);
        var result = service.delete("7", "31"); assertThat(result.before()).isEqualTo(address("31", true, 3)); assertThat(result.after()).isNull();
        var order = inOrder(locks, mapper); order.verify(locks).lock("7"); order.verify(mapper).find("7", "31"); order.verify(mapper).delete("7", "31");
        verify(mapper, never()).clearDefault(any(), any());
    }
    @Test void listPassesOwnerAndActualOffsetWithIndependentTotal() {
        when(mapper.list("7", 20, 20)).thenReturn(List.of(address("31", false, 0))); when(mapper.count("7")).thenReturn(21L);
        var page = service.list("7", 2, 20); assertThat(page.page()).isEqualTo(2); assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(21); assertThat(page.items()).hasSize(1); verifyNoInteractions(locks);
        code(() -> service.list("7", 0, 20), 400, "VALIDATION_ERROR"); code(() -> service.list("7", 1, 101), 400, "VALIDATION_ERROR");
    }
    @Test void validatesRequiredFieldsUnicodeLengthsPhoneIdAndSafeVersionBeforeLock() {
        for (AddressInput bad : new AddressInput[]{null, new AddressInput(" ", "10000000008", "省", "市", "区", "路", false),
            new AddressInput("😀".repeat(51), "10000000008", "省", "市", "区", "路", false),
            new AddressInput("人", "23800138000", "省", "市", "区", "路", false), new AddressInput("人", "10000000008", "省", "市", "区", "路", null),
            new AddressInput("人", "10000000008", "省".repeat(121), "市", "区", "路", false),
            new AddressInput("人", "10000000008", "省", "市", "区", "路".repeat(201), false)})
            code(() -> service.create("7", bad), 400, "VALIDATION_ERROR");
        assertThatCode(() -> AddressService.validate(new AddressInput("😀".repeat(50), "10000000008", "省", "市", "区", "路", false))).doesNotThrowAnyException();
        for (String id : new String[]{null, "01", "-1", "10000000000000000000"})
            code(() -> AddressService.validateUpdate(id, input, 0L), 400, "VALIDATION_ERROR");
        for (Long version : new Long[]{null, -1L, 9007199254740991L})
            code(() -> AddressService.validateUpdate("31", input, version), 400, "VALIDATION_ERROR");
        verifyNoInteractions(locks, mapper);
    }
    @Test void exhaustedVersionCannotBeAdvancedByDefaultSwitchOrUpdate() {
        when(mapper.find("7", "31")).thenReturn(address("31", false, 9007199254740991L));
        code(() -> service.update("7", "31", input, 9007199254740990L), 409, "VERSION_CONFLICT");
    }
    @Test void createDefaultCannotOverflowPreviousDefaultsSafeVersion() {
        when(mapper.findDefault("7")).thenReturn(address("30", true, 9007199254740991L));
        code(() -> service.create("7", input(true)), 409, "VERSION_CONFLICT");
        verify(mapper, never()).clearDefault(any(), any()); verify(mapper, never()).insert(any());
    }
    @Test void updateDefaultCannotOverflowPreviousDefaultsSafeVersion() {
        when(mapper.find("7", "31")).thenReturn(address("31", false, 3));
        when(mapper.findDefault("7")).thenReturn(address("30", true, 9007199254740991L));
        code(() -> service.update("7", "31", input(true), 3L), 409, "VERSION_CONFLICT");
        verify(mapper, never()).clearDefault(any(), any()); verify(mapper, never()).update(any(), any(), any(), anyLong());
    }
    private static AddressInput input(boolean value) { return new AddressInput("收件人", "10000000008", "江苏", "南京", "鼓楼", "测试路1号", value); }
    private static Address address(String id, boolean value, long version) { return new Address(id, "收件人", "10000000008", "江苏", "南京", "鼓楼", "测试路1号", value, version); }
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(status); assertThat(e.code()).isEqualTo(code); });
    }
}
