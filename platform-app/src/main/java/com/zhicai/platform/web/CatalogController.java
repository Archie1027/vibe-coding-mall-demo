package com.zhicai.platform.web;

import com.zhicai.platform.catalog.CatalogModels.*;
import com.zhicai.platform.catalog.CatalogQueryService;
import com.zhicai.platform.common.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import static com.zhicai.platform.web.ErrorResponder.ErrorBody;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final CatalogQueryService catalog;
    public CatalogController(CatalogQueryService catalog) { this.catalog = catalog; }

    @GetMapping("/categories")
    ApiResult<PageData<Category>> categories(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest request) {
        return ApiResult.ok(catalog.categories(page, size), TraceFilter.id(request));
    }
    @GetMapping("/brands")
    ApiResult<PageData<Brand>> brands(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest request) {
        return ApiResult.ok(catalog.brands(page, size), TraceFilter.id(request));
    }
    @GetMapping("/products")
    ApiResult<PageData<Product>> products(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String keyword, @RequestParam(required=false) String categoryId,
        @RequestParam(required=false) String brandId, @RequestParam(required=false) String minPriceMinor,
        @RequestParam(required=false) String maxPriceMinor, @RequestParam(required=false) Boolean featured,
        @RequestParam(required=false) String sort, HttpServletRequest request) {
        return ApiResult.ok(catalog.products(page, size, keyword, categoryId, brandId, minPriceMinor, maxPriceMinor, featured, sort), TraceFilter.id(request));
    }
    @GetMapping("/products/{id}")
    ApiResult<Product> product(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.ok(catalog.product(id), TraceFilter.id(request));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> invalidParameter(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", "请求参数不合法", TraceFilter.id(request), null));
    }
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorBody> catalogUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(503).body(new ErrorBody("DEPENDENCY_UNAVAILABLE", "商品目录暂不可用", TraceFilter.id(request), null));
    }
}
