package com.zhicai.platform.identity;

import com.zhicai.platform.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerLockServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final CustomerLockService service = new CustomerLockService(jdbc);
    final String sql = "SELECT CAST(id AS CHAR) FROM account WHERE id=CAST(? AS UNSIGNED) AND account_type='CUSTOMER' AND enabled=1 FOR UPDATE";
    @Test void locksEnabledCustomerWithinExistingTransactionWithoutUpdatingAccount() throws Exception {
        when(jdbc.queryForList(sql, String.class, "7")).thenReturn(List.of("7"));
        assertThatCode(() -> service.lock("7")).doesNotThrowAnyException();
        verify(jdbc).queryForList(sql, String.class, "7"); verifyNoMoreInteractions(jdbc);
        assertThat(CustomerLockService.class.getMethod("lock", String.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
    @Test void disabledStaffOrMissingAccountCannotBecomeCustomerOwner() {
        when(jdbc.queryForList(sql, String.class, "7")).thenReturn(List.of());
        assertThatThrownBy(() -> service.lock("7")).isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status()).isEqualTo(401); assertThat(e.code()).isEqualTo("UNAUTHENTICATED"); });
    }
}
