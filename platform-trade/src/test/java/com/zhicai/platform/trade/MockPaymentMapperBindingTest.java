package com.zhicai.platform.trade;

import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockPaymentMapperBindingTest {
    @Test void mockPaymentSqlBindsAllMapperMethodsAndKeepsInventoryOutsideThePaymentTransaction() throws Exception {
        var config = new Configuration();
        var xml = "mapper/MockPaymentMapper.xml";
        try (var stream = getClass().getClassLoader().getResourceAsStream(xml)) {
            assertThat(stream).isNotNull();
            new XMLMapperBuilder(stream, config, xml, config.getSqlFragments()).parse();
        }
        var mapper = MockPaymentMapper.class.getName() + ".";
        for (var method : MockPaymentMapper.class.getDeclaredMethods()) {
            assertThat(config.hasStatement(mapper + method.getName())).as(method.getName()).isTrue();
        }
        assertThat(sql(config, mapper + "findOrderId")).doesNotContain("FOR UPDATE");
        assertThat(sql(config, mapper + "lockOrder")).contains("FROM sales_order").endsWith("FOR UPDATE");
        assertThat(sql(config, mapper + "lockPayment")).contains("AND order_id=CAST(? AS UNSIGNED)").endsWith("FOR UPDATE");
        assertThat(sql(config, mapper + "markPaid"))
            .contains("state IN ('UNPAID','CLOSING')", "paid_at IS NULL", "cancelled_at IS NULL", "version=?");
        for (var mutation : List.of("insertPayment", "insertSuccessEvent", "markSucceeded", "markPaid")) {
            assertThat(sql(config, mapper + mutation)).doesNotContain("stock_balance", "stock_reservation", "stock_ledger");
        }
    }

    private static String sql(Configuration config, String id) {
        return config.getMappedStatement(id).getBoundSql(null).getSql().replaceAll("\\s+", " ").trim();
    }
}
