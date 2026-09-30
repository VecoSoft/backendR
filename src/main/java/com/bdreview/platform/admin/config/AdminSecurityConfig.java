package com.bdreview.platform.admin.config;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Session/form-login security for the server-rendered Thymeleaf admin panel
 * (mounted at /admin — spec §12 "moderation staff" surface).
 *
 * <p>This is intentionally a completely separate {@link SecurityFilterChain}
 * from the one in {@code auth.SecurityConfig}: the rest of the application is
 * a stateless JWT API (bearer tokens, no session, no CSRF) and stays exactly
 * as it was. This chain is scoped with {@code securityMatcher("/admin/**")} and
 * given {@code @Order(1)} so it is evaluated first for admin-panel URLs.
 *
 * <p>V56 roles: ADMIN can reach everything; a community MODERATOR (staff flag on a normal
 * account, see AdminAuthenticationProvider) can reach only the Community section, and never its
 * settings / topics / announcements / roles / audit / reveal-identity pages. Every admin
 * controller also carries its own {@code @PreAuthorize}. A session's role is re-checked against
 * the database on every request, so removing a moderator takes effect immediately.
 */
@Configuration
public class AdminSecurityConfig {

    private final AdminAuthenticationProvider adminAuthenticationProvider;
    private final UserRepository userRepository;

    public AdminSecurityConfig(AdminAuthenticationProvider adminAuthenticationProvider, UserRepository userRepository) {
        this.adminAuthenticationProvider = adminAuthenticationProvider;
        this.userRepository = userRepository;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/admin/**")
                .authenticationProvider(adminAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login", "/admin/css/**", "/admin/js/**").permitAll()
                        .requestMatchers("/admin").hasAnyRole("ADMIN", "MODERATOR")
                        // ADMIN-only corners of the Community section
                        .requestMatchers("/admin/community/settings/**", "/admin/community/settings",
                                "/admin/community/topics/**", "/admin/community/topics",
                                "/admin/community/roles/**", "/admin/community/roles",
                                "/admin/community/announcements/**", "/admin/community/announcements",
                                "/admin/community/audit/**", "/admin/community/audit",
                                "/admin/community/users/*/reveal",
                                "/admin/community/posts/*/hard-delete").hasRole("ADMIN")
                        .requestMatchers("/admin/community/**", "/admin/community").hasAnyRole("ADMIN", "MODERATOR")
                        // V58 Promotions: moderators review posts/boosts and pause/end live boosts;
                        // money, settings, templates, packages, restrictions and revenue are ADMIN.
                        .requestMatchers("/admin/promotions/settings/**", "/admin/promotions/settings",
                                "/admin/promotions/templates/**", "/admin/promotions/templates",
                                "/admin/promotions/packages/**", "/admin/promotions/packages",
                                "/admin/promotions/restrictions/**", "/admin/promotions/restrictions",
                                "/admin/promotions/revenue/**", "/admin/promotions/revenue",
                                "/admin/promotions/boosts/*/verify-payment", "/admin/promotions/boosts/*/reject-payment",
                                "/admin/promotions/boosts/*/targeting").hasRole("ADMIN")
                        .requestMatchers("/admin/promotions/**", "/admin/promotions").hasAnyRole("ADMIN", "MODERATOR")
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(staffRevalidationFilter(), AnonymousAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .usernameParameter("phoneNumber")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/admin", true)
                        .failureUrl("/admin/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout")
                        .permitAll())
                .exceptionHandling(ex -> ex.accessDeniedPage("/admin/login?denied"));
        return http.build();
    }

    /**
     * Drops a session whose account no longer holds the role it logged in with (moderator removed,
     * admin demoted) — the next request lands on the login page.
     */
    private OncePerRequestFilter staffRevalidationFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                boolean claimsAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                boolean claimsModerator = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_MODERATOR"));
                if (claimsAdmin || claimsModerator) {
                    boolean stillValid;
                    try {
                        User user = userRepository.findById(UUID.fromString(auth.getName())).orElse(null);
                        stillValid = user != null && (claimsAdmin ? user.getRole() == UserRole.ADMIN : user.isModerator());
                    } catch (IllegalArgumentException e) {
                        stillValid = false;
                    }
                    if (!stillValid) {
                        SecurityContextHolder.clearContext();
                        if (request.getSession(false) != null) {
                            request.getSession(false).invalidate();
                        }
                        response.sendRedirect(request.getContextPath() + "/admin/login?denied");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }
}
