package com.zhicai.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhicai.platform.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;

@Component
public class ErrorResponder {
    private final ObjectMapper json;
    public ErrorResponder(ObjectMapper json) { this.json = json; }
    public void write(HttpServletRequest request, HttpServletResponse response, ApiException error) throws IOException {
        response.setStatus(error.status()); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        if (error.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
        if (error.status() == 429) response.setHeader("Retry-After", "900");
        json.writeValue(response.getOutputStream(), new ErrorBody(error.code(), error.getMessage(), TraceFilter.id(request), null));
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, String traceId, List<FieldError> fieldErrors) {}
    public record FieldError(String field, String message) {}
}
