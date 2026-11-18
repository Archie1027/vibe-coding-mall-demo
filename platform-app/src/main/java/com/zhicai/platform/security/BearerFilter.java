package com.zhicai.platform.security;

import com.zhicai.platform.common.ApiException;
import com.zhicai.platform.identity.AuthService;
import com.zhicai.platform.web.ErrorResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public final class BearerFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ErrorResponder errors;
    public BearerFilter(AuthService auth, ErrorResponder errors) { this.auth = auth; this.errors = errors; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        try {
            if (header != null) {
                if (!header.startsWith("Bearer ")) throw ApiException.unauthenticated();
                var account = auth.authenticate(header.substring(7));
                var granted = account.permissions().stream().map(SimpleGrantedAuthority::new).toList();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(account, null, granted));
                SecurityContextHolder.setContext(context);
            }
        } catch (ApiException error) { errors.write(request, response, error); return; }
        catch (DataAccessException error) { errors.write(request, response, ApiException.unavailable()); return; }
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
