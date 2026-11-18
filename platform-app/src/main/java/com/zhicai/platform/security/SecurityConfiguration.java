package com.zhicai.platform.security;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AuthService;
import com.zhicai.platform.identity.AccountView;
import com.zhicai.platform.identity.AccountType;
import com.zhicai.platform.web.ErrorResponder;
import com.zhicai.platform.web.TraceFilter;
import com.zhicai.platform.write.MockPaymentApplication;
import com.zhicai.platform.write.MockPaymentConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.authorization.AuthorizationDecision;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnWebApplication SecurityFilterChain security(HttpSecurity http, AuthService auth, ErrorResponder errors, Environment environment) throws Exception {
        http.csrf(csrf -> csrf.disable()).cors(cors -> cors.disable())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(handler -> handler
                .authenticationEntryPoint((request, response, error) -> errors.write(request, response, ApiException.unauthenticated()))
                .accessDeniedHandler((request, response, error) -> errors.write(request, response,
                    new ApiException(403, "FORBIDDEN", "没有当前接口权限"))))
            .authorizeHttpRequests(authorize -> {
                if (MockPaymentConfiguration.enabled(environment)) {
                    authorize.requestMatchers(HttpMethod.POST, MockPaymentApplication.START_ROUTE)
                        .access((authentication, context) -> new AuthorizationDecision(
                            authentication.get().getPrincipal() instanceof AccountView account && account.accountType() == AccountType.CUSTOMER));
                    authorize.requestMatchers(HttpMethod.POST, MockPaymentApplication.SUCCESS_ROUTE)
                        .access((authentication, context) -> new AuthorizationDecision(
                            authentication.get().getPrincipal() instanceof AccountView account && account.accountType() == AccountType.STAFF
                                && account.permissions().contains(MockPaymentApplication.MOCK_PERMISSION)));
                }
                authorize
                // Only exact implemented auth paths enter MVC; controller method mappings return 405.
                // Private me/logout paths still require a current bearer account for every method.
                .requestMatchers("/api/v1/auth/customer/login", "/api/v1/auth/staff/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/catalog/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/assets/products/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/assets/products/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/categories", "/api/v1/admin/brands",
                    "/api/v1/admin/products", "/api/v1/admin/products/{id}").hasAuthority("catalog:read")
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/assets/images", "/api/v1/admin/brands", "/api/v1/admin/categories", "/api/v1/admin/products",
                    "/api/v1/admin/products/{id}/state", "/api/v1/admin/products/{id}/skus").access((authentication, context) ->
                    new AuthorizationDecision(authentication.get().getPrincipal() instanceof AccountView account
                        && account.accountType() == AccountType.STAFF && account.permissions().contains("catalog:write")))
                .requestMatchers(HttpMethod.PUT, "/api/v1/admin/brands/{id}", "/api/v1/admin/categories/{id}",
                    "/api/v1/admin/products/{id}", "/api/v1/admin/skus/{id}").access((authentication, context) ->
                    new AuthorizationDecision(authentication.get().getPrincipal() instanceof AccountView account
                        && account.accountType() == AccountType.STAFF && account.permissions().contains("catalog:write")))
                .requestMatchers("/api/v1/me/addresses", "/api/v1/me/addresses/{id}", "/api/v1/me/cart", "/api/v1/me/cart/items", "/api/v1/me/cart/items/{id}")
                    .access((authentication, context) -> new AuthorizationDecision(authentication.get().getPrincipal() instanceof AccountView account && account.accountType()==AccountType.CUSTOMER))
                .requestMatchers(HttpMethod.POST, "/api/v1/checkout/quotes")
                    .access((authentication, context) -> new AuthorizationDecision(authentication.get().getPrincipal() instanceof AccountView account && account.accountType()==AccountType.CUSTOMER))
                .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/{id}")
                    .access((authentication, context) -> new AuthorizationDecision(authentication.get().getPrincipal() instanceof AccountView account && account.accountType()==AccountType.CUSTOMER))
                .requestMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/orders/{id}/cancel")
                    .access((authentication, context) -> new AuthorizationDecision(authentication.get().getPrincipal() instanceof AccountView account && account.accountType()==AccountType.CUSTOMER))
                .requestMatchers("/api/v1/me", "/api/v1/auth/logout").authenticated()
                .anyRequest().denyAll();
            })
            .addFilterBefore(new TraceFilter(), SecurityContextHolderFilter.class)
            .addFilterAfter(new BearerFilter(auth, errors), SecurityContextHolderFilter.class);
        return http.build();
    }
}
