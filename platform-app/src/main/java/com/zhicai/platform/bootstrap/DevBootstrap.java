package com.zhicai.platform.bootstrap;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit, one-shot local fixture initializer. Requires reviewed migration and private generated passwords. */
@Component
@Profile("dev-bootstrap")
@ConditionalOnProperty(name="app.dev-bootstrap.enabled", havingValue="true")
public class DevBootstrap implements CommandLineRunner {
    private static final List<String> PERMISSIONS = List.of("catalog:read", "catalog:write", "review:read", "review:moderate",
        "order:read", "order:ship", "purchase:write", "purchase:read", "purchase:approve", "stock:read", "stock:receive",
        "stock:ship", "stock:adjust", "stock:approve", "transfer:write", "transfer:approve", "after-sale:read", "after-sale:review",
        "refund:execute", "finance:read", "finance:write", "promotion:write", "report:read", "system:write", "audit:read");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Environment environment;
    public DevBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder, Environment environment) {
        this.jdbc = jdbc; this.encoder = encoder; this.environment = environment;
    }
    @Override @Transactional
    public void run(String... args) {
        if (!"zhicai_dev".equals(jdbc.queryForObject("SELECT DATABASE()", String.class))
            || Arrays.asList(environment.getActiveProfiles()).contains("prod"))
            throw new IllegalStateException("Development bootstrap requires zh icai development database and no prod profile");
        for (String table : List.of("account", "role", "permission", "account_role", "role_permission", "warehouse", "account_warehouse")) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class) != 0)
                throw new IllegalStateException("Development bootstrap refuses nonempty identity tables");
        }
        Map<String, String> passwords = new LinkedHashMap<>();
        for (String name : List.of("admin", "purchaser", "approver", "customer")) {
            String password = environment.getRequiredProperty("M0_" + name.toUpperCase(Locale.ROOT) + "_PASSWORD");
            int bytes = password.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 12 || bytes > 72) throw new IllegalStateException("Development password does not meet byte requirements");
            passwords.put(name, password);
        }
        for (String code : PERMISSIONS) jdbc.update("INSERT INTO permission(code,name) VALUES (?,?)", code, code);
        for (String code : List.of("ADMIN", "PURCHASER", "APPROVER")) jdbc.update("INSERT INTO role(code,name) VALUES (?,?)", code, code);
        grant("ADMIN", PERMISSIONS);
        grant("PURCHASER", List.of("purchase:read", "purchase:write"));
        grant("APPROVER", List.of("purchase:read", "purchase:approve", "stock:approve", "transfer:approve", "report:read"));
        jdbc.update("INSERT INTO warehouse(code,name,priority) VALUES ('M0_A','演示仓A',1),('M0_B','演示仓B',2)");
        for (var entry : passwords.entrySet()) {
            boolean customer = entry.getKey().equals("customer");
            jdbc.update("INSERT INTO account(account_type,username,password_hash,display_name) VALUES (?,?,?,?)",
                customer ? "CUSTOMER" : "STAFF", entry.getKey(), encoder.encode(entry.getValue()), entry.getKey());
            if (!customer) {
                jdbc.update("INSERT INTO account_role(account_id,role_id) SELECT a.id,r.id FROM account a CROSS JOIN role r WHERE a.account_type='STAFF' AND a.username=? AND r.code=?",
                    entry.getKey(), entry.getKey().toUpperCase(Locale.ROOT));
                jdbc.update("INSERT INTO account_warehouse(account_id,warehouse_id) SELECT a.id,w.id FROM account a CROSS JOIN warehouse w WHERE a.account_type='STAFF' AND a.username=?", entry.getKey());
            }
        }
        // Let the caller choose a non-web process; shutdown follows a successful committed runner.
        System.out.println("M0 development fixtures created; passwords are available only in the caller's private environment file.");
    }
    private void grant(String role, List<String> codes) {
        for (String code : codes) jdbc.update("INSERT INTO role_permission(role_id,permission_code) SELECT id,? FROM role WHERE role.code=?", code, role);
    }
}
