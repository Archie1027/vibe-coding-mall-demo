package com.zhicai.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

public final class TraceFilter extends OncePerRequestFilter {
    public static String id(HttpServletRequest request) { return (String) request.getAttribute("traceId"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        chain.doFilter(request, response);
    }
}
