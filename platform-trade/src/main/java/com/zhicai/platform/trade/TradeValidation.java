package com.zhicai.platform.trade;

import com.zhicai.platform.common.ApiException;

final class TradeValidation {
    static final long MAX_SAFE_VERSION = 9007199254740991L;
    private TradeValidation() {}
    static void id(String id) { if (id == null || !id.matches("[1-9][0-9]{0,18}")) bad("ID不合法"); }
    static void quantity(Integer quantity) { if (quantity == null || quantity < 1 || quantity > 99) bad("数量必须是1至99的整数"); }
    static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) bad("分页参数不合法");
        return ((long) page - 1) * size;
    }
    static boolean text(String value, int max) { return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max; }
    static void bad(String message) { throw new ApiException(400, "VALIDATION_ERROR", message); }
    static ApiException notFound(String type) { return new ApiException(404, "NOT_FOUND", type + "不存在"); }
    static ApiException versionConflict() { return new ApiException(409, "VERSION_CONFLICT", "地址已被更新，请重新加载后编辑"); }
}
