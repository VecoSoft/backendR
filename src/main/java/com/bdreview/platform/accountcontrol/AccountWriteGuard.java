package com.bdreview.platform.accountcontrol;

import com.bdreview.platform.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;
import java.util.UUID;

/**
 * Refuses every write (anything but GET/HEAD/OPTIONS) from a suspended or banned account with a
 * 403 carrying the reason and end date (V63). Logging out stays possible. Login and token refresh
 * are checked in AuthService itself, since they carry no access token.
 */
@Configuration
public class AccountWriteGuard implements WebMvcConfigurer, HandlerInterceptor {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> EXEMPT = Set.of("/api/v1/auth/logout", "/api/v1/auth/refresh", "/api/v1/auth/login");

    private final AccountControlService accountControl;

    public AccountWriteGuard(AccountControlService accountControl) {
        this.accountControl = accountControl;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**").order(-90);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READ_METHODS.contains(request.getMethod().toUpperCase())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (EXEMPT.contains(path)) {
            return true;
        }
        UUID userId = CurrentUser.idOrNull();
        if (userId != null) {
            accountControl.assertNotRestricted(userId);
        }
        return true;
    }
}
