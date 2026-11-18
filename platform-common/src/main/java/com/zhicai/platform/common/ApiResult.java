package com.zhicai.platform.common;

public record ApiResult<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResult<T> ok(T data, String traceId) { return new ApiResult<>("OK", "成功", data, traceId); }
}
