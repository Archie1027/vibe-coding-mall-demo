package com.zhicai.platform.web;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.common.ApiResult;
import com.zhicai.platform.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/auth/customer/login")
    ApiResult<AuthService.Token> customer(@RequestBody @Valid LoginRequest body, HttpServletRequest request) {
        return login(AccountType.CUSTOMER, body, request);
    }
    @PostMapping("/auth/staff/login")
    ApiResult<AuthService.Token> staff(@RequestBody @Valid LoginRequest body, HttpServletRequest request) {
        return login(AccountType.STAFF, body, request);
    }
    private ApiResult<AuthService.Token> login(AccountType type, LoginRequest body, HttpServletRequest request) {
        String username = body.username();
        if (username.matches("[\\s\\p{Z}\\uFEFF]*") || username.codePointCount(0, username.length()) > 50)
            throw new ApiException(400, "VALIDATION_ERROR", "请输入用户名（最多50个字符，支持中文、英文或混合使用）");
        if (!body.password().matches("[\\x20-\\x7E]{1,72}"))
            throw new ApiException(400, "VALIDATION_ERROR", "密码须为1–72位英文字母、数字或英文符号，不支持中文");
        return ApiResult.ok(auth.login(type, body.username(), body.password(), request.getRemoteAddr()), TraceFilter.id(request));
    }
    @GetMapping("/me")
    ApiResult<AccountView> me(@AuthenticationPrincipal AccountView account, HttpServletRequest request) {
        return ApiResult.ok(account, TraceFilter.id(request));
    }
    @PostMapping("/auth/logout")
    ApiResult<Ack> logout(HttpServletRequest request) {
        auth.logout(request.getHeader("Authorization").substring(7));
        return ApiResult.ok(new Ack(true), TraceFilter.id(request));
    }
    public record LoginRequest(@NotNull String username,
                               @NotNull String password) {
        @Override public String toString() { return "LoginRequest[REDACTED]"; }
    }
    public record Ack(boolean accepted) {}
}
