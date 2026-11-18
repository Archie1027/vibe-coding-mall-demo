package com.zhicai.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.identity.*;
import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.catalog.CatalogQueryService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.time.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "spring.flyway.enabled=false", "spring.data.redis.password=unused", "app.catalog.writes-enabled=false"
})
@AutoConfigureMockMvc
class AuthHttpTest {
    @MockitoBean com.zhicai.platform.trade.AddressService addresses;
    @MockitoBean com.zhicai.platform.trade.CartService cart;
    @MockitoBean com.zhicai.platform.trade.OrderReadService orders;
    @MockitoBean com.zhicai.platform.identity.CustomerLockService customerLocks;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.zhicai.platform.catalog.BrandWriteService brandWrites;
    @MockitoBean com.zhicai.platform.catalog.CategoryWriteService categoryWrites;
    @MockitoBean com.zhicai.platform.catalog.ProductWriteService productWrites;
    @MockitoBean com.zhicai.platform.catalog.AssetWriteMapper assetWrites;
    @MockitoBean CatalogQueryService catalog;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestAccounts accounts;
    @Autowired TestSessions sessions;
    @Autowired PasswordEncoder encoder;

    @BeforeEach void setup() {
        accounts.rows.clear(); sessions.rows.clear(); sessions.unavailable = false;
        accounts.rows.put("1", new AccountRow("1", "customer", encoder.encode("validPassword123"), "顾客", AccountType.CUSTOMER, true, 0));
        accounts.rows.put("2", new AccountRow("2", "admin", encoder.encode("validPassword123"), "员工", AccountType.STAFF, true, 0));
        accounts.grants = List.of("catalog:read"); accounts.scope = List.of("10");
    }

    private String login(String type, String username, String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/" + type + "/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/accessToken").asText();
    }

    @Test void loginReturnsOpaqueTwoHourTokenAndCurrentAccountWithoutSecret() throws Exception {
        Instant before = Instant.now();
        String token = login("staff", "admin", "validPassword123");
        org.assertj.core.api.Assertions.assertThat(token).matches("[A-Za-z0-9_-]{43}");
        org.assertj.core.api.Assertions.assertThat(sessions.rows.keySet()).doesNotContain(token).contains(TokenHash.sha256(token));
        org.assertj.core.api.Assertions.assertThat(sessions.rows.get(TokenHash.sha256(token)).expires)
            .isBetween(before.plusSeconds(7199), Instant.now().plusSeconds(7201));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("2"))
            .andExpect(jsonPath("$.data.accountType").value("STAFF"))
            .andExpect(jsonPath("$.data.permissions[0]").value("catalog:read"))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test void logoutRevokesToken() throws Exception {
        String token = login("customer", "customer", "validPassword123");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test void expiredTokenIsUnauthenticated() throws Exception {
        String token = login("customer", "customer", "validPassword123");
        sessions.rows.put(TokenHash.sha256(token), new Stored("1:0", Instant.EPOCH));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test void disabledAccountAndChangedVersionRevokeExistingToken() throws Exception {
        String token = login("staff", "admin", "validPassword123");
        AccountRow old = accounts.rows.get("2");
        accounts.rows.put("2", new AccountRow("2", old.username(), old.passwordHash(), old.displayName(), old.accountType(), false, 1));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        accounts.rows.put("2", new AccountRow("2", old.username(), old.passwordHash(), old.displayName(), old.accountType(), true, 1));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void permissionAndWarehouseChangesTakeEffectOnNextRequest() throws Exception {
        String token = login("staff", "admin", "validPassword123");
        accounts.grants = List.of("purchase:approve"); accounts.scope = List.of("11");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions[0]").value("purchase:approve"))
            .andExpect(jsonPath("$.data.warehouseIds[0]").value("11"));
    }

    @Test void customerCannotInheritStaffPermissionsOrUsePrivateRoutes() throws Exception {
        String token = login("customer", "customer", "validPassword123");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions").isEmpty())
            .andExpect(jsonPath("$.data.warehouseIds").isEmpty()).andExpect(jsonPath("$.data.roleIds").isEmpty());
        mvc.perform(get("/api/v1/admin/accounts").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test void wrongTypeUnknownUserAndWrongPasswordShare401Message() throws Exception {
        for (String[] pair : List.of(new String[]{"customer", "validPassword123"}, new String[]{"missing", "validPassword123"}, new String[]{"admin", "wrongPassword123"})) {
            mvc.perform(post("/api/v1/auth/staff/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", pair[0], "password", pair[1]))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("账户或凭据无效"));
        }
    }

    @Test void redisOutageFailsClosedForTokenLookupAndLogin() throws Exception {
        String token = login("staff", "admin", "validPassword123"); sessions.unavailable = true;
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        mvc.perform(post("/api/v1/auth/staff/login").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"validPassword123\"}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test void usernameCapacityAndPasswordRulesAreEnforced() throws Exception {
        for (Map<String,String> body : List.of(Map.of("username", "中".repeat(51), "password", "validPassword123"),
            Map.of("username", "admin", "password", "中".repeat(25)))) {
            mvc.perform(post("/api/v1/auth/staff/login").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test void chineseEnglishAndMixedUsernamesCanAuthenticate() throws Exception {
        for (String kind : List.of("staff", "customer")) {
            String id = kind.equals("staff") ? "2" : "1";
            for (String username : List.of("张", "张三", "Z", "张三Alice123", "Alice Smith", "甲".repeat(50), "😀".repeat(50))) {
                AccountRow old = accounts.rows.get(id);
                accounts.rows.put(id, new AccountRow(id, username, old.passwordHash(), old.displayName(), old.accountType(), true, 0));
                String token = login(kind, username, "validPassword123");
                mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username));
                mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            }
        }
    }

    @Test void missingBlankAndOverCapacityUsernamesAreRejected() throws Exception {
        for (String kind : List.of("staff", "customer")) {
            for (String username : List.of("", "  ", "\t", "a".repeat(51), "😀".repeat(51))) {
                mvc.perform(post("/api/v1/auth/" + kind + "/login").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username", username, "password", "validPassword123"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
            mvc.perform(post("/api/v1/auth/" + kind + "/login").contentType("application/json")
                .content("{\"password\":\"validPassword123\"}"))
                .andExpect(status().isBadRequest());
        }
    }

    @Test void simpleAsciiPasswordsCompleteLoginMeAndLogout() throws Exception {
        // Change only synthetic in-memory accounts; never reset the local development database.
        for (String kind : List.of("staff", "customer")) {
          String id=kind.equals("staff")?"2":"1", username=kind.equals("staff")?"admin":"customer";
          for (String password : List.of("1", "123456", "abc", "ABC", "!@#$%^&*()_+-=[]{};:'\",.<>/?`~\\|", "a b", " ", "a".repeat(72))) {
            AccountRow old = accounts.rows.get(id);
            accounts.rows.put(id, new AccountRow(id, old.username(), encoder.encode(password),
                old.displayName(), old.accountType(), true, 0));
            String token = login(kind, username, password);
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
            mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
          }
        }
    }

    @Test void emptyChineseAndNonPrintablePasswordsAreRejectedBeforeLogin() throws Exception {
        for (String kind : List.of("staff", "customer")) {
          for (String password : List.of("", "a".repeat(73), "中文密码测试", "abc中123", "１２３", "é", "😀", "abc\n123", "abc\u0000123")) {
            mvc.perform(post("/api/v1/auth/"+kind+"/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", kind.equals("staff")?"admin":"customer", "password", password))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            org.assertj.core.api.Assertions.assertThat(sessions.rows).isEmpty();
          }
        }
    }

    @Test void shortWrongPasswordStillCannotAuthenticate() throws Exception {
        for(String kind:List.of("staff","customer"))
            mvc.perform(post("/api/v1/auth/"+kind+"/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",kind.equals("staff")?"admin":"customer","password","1"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        org.assertj.core.api.Assertions.assertThat(sessions.rows).isEmpty();
    }

    @Test void loginRejectsUnknownFieldsWithContractEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/customer/login").contentType("application/json")
            .content("{\"username\":\"customer\",\"password\":\"validPassword123\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void invalidTokenUses401Envelope() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void unsupportedLoginMediaTypeKeeps415AndEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/customer/login").contentType("text/plain").content("{}"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void wrongPublicLoginMethodKeeps405AndEnvelope() throws Exception {
        mvc.perform(get("/api/v1/auth/customer/login"))
            .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void wrongAuthenticatedLogoutMethodCannotRevokeSession() throws Exception {
        String token = login("staff", "admin", "validPassword123");
        mvc.perform(get("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
            .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test void exactAuthPathsDoNotExposeUnknownSubpathsOrPrivateMethodsAnonymously() throws Exception {
        mvc.perform(get("/api/v1/auth/customer/login/extra")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Dependencies {
        @Bean @Primary TestAccounts testAccounts() { return new TestAccounts(); }
        @Bean @Primary TestSessions testSessions() { return new TestSessions(); }
        @Bean @Primary LoginThrottle testThrottle() { return new LoginThrottle() {
            public Reservation reserve(String identity, String address) { return new Reservation(identity, address, "test"); }
            public void failed(Reservation reservation) {}
            public void succeeded(Reservation reservation) {}
        }; }
        @Bean @Primary PasswordEncoder testPasswordEncoder() { return new BCryptPasswordEncoder(4); }
    }
    static class TestAccounts implements IdentityMapper {
        final Map<String, AccountRow> rows = new HashMap<>();
        List<String> grants = List.of(); List<String> scope = List.of();
        public AccountRow findByUsername(AccountType type, String username) {
            return rows.values().stream().filter(r -> r.accountType() == type && r.username().equals(username)).findFirst().orElse(null);
        }
        public AccountRow findById(String id) { return rows.get(id); }
        public List<String> permissions(String id) { return grants; }
        public List<String> warehouses(String id) { return scope; }
        public List<String> roles(String id) { return List.of("20"); }
    }
    record Stored(String subject, Instant expires) {}
    static class TestSessions implements SessionStore {
        final Map<String, Stored> rows = new HashMap<>(); boolean unavailable;
        public void put(String hash, String subject, Duration ttl) {
            if (unavailable) throw ApiException.unavailable(); rows.put(hash, new Stored(subject, Instant.now().plus(ttl)));
        }
        public String find(String hash) {
            if (unavailable) throw ApiException.unavailable();
            Stored found = rows.get(hash); return found == null || !found.expires.isAfter(Instant.now()) ? null : found.subject;
        }
        public void remove(String hash) { if (unavailable) throw ApiException.unavailable(); rows.remove(hash); }
    }
}
