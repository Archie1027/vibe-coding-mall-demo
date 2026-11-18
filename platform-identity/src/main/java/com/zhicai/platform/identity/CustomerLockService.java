package com.zhicai.platform.identity;

import com.zhicai.platform.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerLockService {
    private final JdbcTemplate jdbc;
    public CustomerLockService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String owner) {
        // A stable owner row also serializes writes when the customer's address/cart tables are empty.
        var rows = jdbc.queryForList("SELECT CAST(id AS CHAR) FROM account WHERE id=CAST(? AS UNSIGNED) AND account_type='CUSTOMER' AND enabled=1 FOR UPDATE",
                                    String.class, owner);
        if (rows.isEmpty()) throw ApiException.unauthenticated();
    }
}
