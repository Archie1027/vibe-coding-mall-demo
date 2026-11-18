package com.zhicai.platform.web;

import com.zhicai.platform.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import static com.zhicai.platform.web.ErrorResponder.*;

@RestControllerAdvice
public class ApiAdvice {
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> parameter(HttpServletRequest request) { return api(new ApiException(400,"VALIDATION_ERROR","请求参数不合法"),request); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> uploadTooLarge(HttpServletRequest request){return api(new ApiException(413,"PAYLOAD_TOO_LARGE","上传图片不能超过5MiB，请求总大小不能超过6MiB"),request);}
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,org.springframework.web.multipart.MultipartException.class})
    ResponseEntity<ErrorBody> malformedUpload(HttpServletRequest request){return api(new ApiException(400,"VALIDATION_ERROR","图片上传需要且仅允许一个file字段"),request);}
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status())
            .headers(headers -> { if (error.status() == 429) headers.set("Retry-After", "900");
                                  if (error.status() == 401) headers.set("WWW-Authenticate", "Bearer");
                                  if (error.code().equals("REQUEST_IN_PROGRESS")) headers.set("Retry-After", "1"); })
            .body(new ErrorBody(error.code(), error.getMessage(), TraceFilter.id(request), null));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        var fields = error.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldError(e.getField(), "字段格式不符合要求")).toList();
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", "请求字段不合法", TraceFilter.id(request), fields));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> malformed(HttpServletRequest request) {
        return api(new ApiException(400, "VALIDATION_ERROR", "请求JSON不合法或包含未知字段"), request);
    }
    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<ErrorBody> unavailable(HttpServletRequest request) { return api(ApiException.unavailable(), request); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedMedia(HttpServletRequest request) {
        return api(new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "不支持当前请求媒体类型"), request);
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedMethod(HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        return ResponseEntity.status(405).headers(headers -> {
            if (error.getSupportedHttpMethods() != null) headers.setAllow(error.getSupportedHttpMethods());
        }).body(new ErrorBody("METHOD_NOT_ALLOWED", "不支持当前请求方法", TraceFilter.id(request), null));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> missing(HttpServletRequest request) {
        return api(new ApiException(404, "NOT_FOUND", "接口尚未实现或资源不存在"), request);
    }
}
