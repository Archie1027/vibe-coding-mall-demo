package com.zhicai.platform;

import com.zhicai.platform.identity.*;
import com.zhicai.platform.bootstrap.DevBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

/** Read-only checks against the explicitly approved, already migrated development schema. */
@EnabledIfEnvironmentVariable(named="M0_MYSQL_INTEGRATION", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={"spring.flyway.enabled=false", "debug=false"})
class MySqlIdentityIntegrationTest {
    @Autowired IdentityMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test void realXmlMapperReadsAccountTypesCurrentGrantsAndScopes() {
        var admin = mapper.findByUsername(AccountType.STAFF, "admin");
        assertThat(admin).isNotNull(); assertThat(admin.id()).matches("[1-9][0-9]*");
        assertThat(admin.enabled()).isTrue(); assertThat(admin.accountType()).isEqualTo(AccountType.STAFF);
        assertThat(mapper.findById(admin.id()).username()).isEqualTo("admin");
        assertThat(mapper.permissions(admin.id())).hasSize(25).contains("system:write", "purchase:approve");
        assertThat(mapper.warehouses(admin.id())).hasSize(2).allMatch(id -> id.matches("[1-9][0-9]*"));
        assertThat(mapper.roles(admin.id())).hasSize(1);
        assertThat(mapper.findByUsername(AccountType.CUSTOMER, "admin")).isNull();
        var customer = mapper.findByUsername(AccountType.CUSTOMER, "customer");
        assertThat(customer).isNotNull(); assertThat(mapper.permissions(customer.id())).isEmpty();
        assertThat(mapper.warehouses(customer.id())).isEmpty(); assertThat(mapper.roles(customer.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1", Integer.class)).isEqualTo(1);
    }

    @Test void bootstrapRefusesInitializedDatabaseBeforeAnyWrite() {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM account", Long.class);
        var runner = new DevBootstrap(jdbc, encoder, new MockEnvironment());
        assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class)
            .hasMessage("Development bootstrap refuses nonempty identity tables");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account", Long.class)).isEqualTo(before).isEqualTo(4);
    }
}
