package com.zhicai.platform.common;

public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiException unauthenticated() { return new ApiException(401, "UNAUTHENTICATED", "账户或凭据无效"); }
    public static ApiException unavailable() { return new ApiException(503, "DEPENDENCY_UNAVAILABLE", "认证依赖暂不可用"); }
}
