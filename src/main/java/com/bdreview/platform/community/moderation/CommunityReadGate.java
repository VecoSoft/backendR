package com.bdreview.platform.community.moderation;

import com.bdreview.platform.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies the "community on/off (maintenance)" and "logged-out users can read" settings to every
 * community READ (writes are gated inside CommunityPolicyService#assertCanWrite). Exceptions
 * thrown here go through the normal @RestControllerAdvice, so the client gets a 503 with the
 * admin's maintenance message / a 403 "Log in to read the community.".
 *
 * <p>Settings, the caller's own standing and the username setup endpoints stay reachable — the
 * app needs them to render the maintenance/read-only state itself.
 */
@Configuration
public class CommunityReadGate implements WebMvcConfigurer {

    private final CommunityPolicyService policy;

    public CommunityReadGate(CommunityPolicyService policy) {
        this.policy = policy;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if ("GET".equalsIgnoreCase(request.getMethod())) {
                            policy.assertCanRead(CurrentUser.idOrNull());
                        }
                        return true;
                    }
                })
                .addPathPatterns("/api/v1/community/**")
                .excludePathPatterns("/api/v1/community/settings", "/api/v1/community/me/**",
                        "/api/v1/community/username/**");
    }
}
