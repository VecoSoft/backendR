package com.bdreview.platform.admin.config;

import com.bdreview.platform.accountcontrol.AccountControlService;
import com.bdreview.platform.admin.security.AdminAuthorities;
import com.bdreview.platform.admin.security.AdminSecurityService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * <p>V67 permission roles: every admin account has an admin role (SUPER_ADMIN, MODERATOR, SUPPORT,
 * FINANCE) that grants {@code PERM_*} authorities per section (see admin.security). The URL rules
 * below map each section to its permission; SYSTEM pages and the pre-existing ADMIN-only corners
 * need ROLE_ADMIN, which only SUPER_ADMIN has. Community moderators (staff flag on a normal account)
 * keep ROLE_MODERATOR. Every admin controller also carries its own {@code @PreAuthorize}.
 *
 * <p>Each request re-checks the session against the database: a role change, a suspension, a
 * "sign out all admin sessions" or a 2FA requirement the admin hasn't enrolled in yet takes effect
 * immediately.
 */
@Configuration
public class AdminSecurityConfig {

    private static final String LOGIN_AT = "adminLoginAt";

    private final AdminAuthenticationProvider adminAuthenticationProvider;
    private final UserRepository userRepository;
    private final AccountControlService accountControl;
    private final AdminSecurityService adminSecurity;

    public AdminSecurityConfig(AdminAuthenticationProvider adminAuthenticationProvider, UserRepository userRepository,
                               AccountControlService accountControl, AdminSecurityService adminSecurity) {
        this.adminAuthenticationProvider = adminAuthenticationProvider;
        this.userRepository = userRepository;
        this.accountControl = accountControl;
        this.adminSecurity = adminSecurity;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/admin/**")
                .authenticationProvider(adminAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login", "/admin/css/**", "/admin/js/**").permitAll()
                        // Any staff: the landing redirect and their own 2FA settings.
                        .requestMatchers("/admin", "/admin/account/**").hasAnyRole("ADMIN", "ADMIN_STAFF", "MODERATOR")
                        // ---- Community (V56): ADMIN-only corners, the rest ADMIN + MODERATOR
                        .requestMatchers("/admin/community/settings/**", "/admin/community/settings",
                                "/admin/community/topics/**", "/admin/community/topics",
                                "/admin/community/roles/**", "/admin/community/roles",
                                "/admin/community/announcements/**", "/admin/community/announcements",
                                "/admin/community/audit/**", "/admin/community/audit",
                                "/admin/community/users/*/reveal",
                                "/admin/community/posts/*/hard-delete").hasRole("ADMIN")
                        .requestMatchers("/admin/community/**", "/admin/community").hasAnyRole("ADMIN", "MODERATOR")
                        // ---- Promotions (V58): settings & catalogue ADMIN, money FINANCE, review queues MODERATOR
                        .requestMatchers("/admin/promotions/settings/**", "/admin/promotions/settings",
                                "/admin/promotions/templates/**", "/admin/promotions/templates",
                                "/admin/promotions/packages/**", "/admin/promotions/packages",
                                "/admin/promotions/restrictions/**", "/admin/promotions/restrictions",
                                "/admin/promotions/boosts/*/targeting").hasRole("ADMIN")
                        .requestMatchers("/admin/promotions/revenue/**", "/admin/promotions/revenue",
                                "/admin/promotions/boosts/*/verify-payment", "/admin/promotions/boosts/*/reject-payment")
                        .hasAnyAuthority("ROLE_ADMIN", "PERM_FINANCE")
                        .requestMatchers(HttpMethod.GET, "/admin/promotions", "/admin/promotions/boosts")
                        .hasAnyAuthority("ROLE_ADMIN", "ROLE_MODERATOR", "PERM_FINANCE")
                        .requestMatchers("/admin/promotions/boosts/*/action").hasAnyAuthority("ROLE_ADMIN", "ROLE_MODERATOR", "PERM_FINANCE")
                        .requestMatchers("/admin/promotions/**", "/admin/promotions").hasAnyRole("ADMIN", "MODERATOR")
                        // ---- V67 sections
                        .requestMatchers("/admin/dashboard/**", "/admin/dashboard").hasAnyAuthority("ROLE_ADMIN", "PERM_DASHBOARD")
                        .requestMatchers("/admin/reviews/settings/**", "/admin/reviews/settings").hasRole("ADMIN")
                        .requestMatchers("/admin/photos/settings").hasRole("ADMIN")
                        .requestMatchers("/admin/reviews/**", "/admin/reviews", "/admin/reports/**", "/admin/reports",
                                "/admin/photos/**", "/admin/photos", "/admin/claims/**", "/admin/claims",
                                "/admin/chat-reports/**", "/admin/chat-reports").hasAnyAuthority("ROLE_ADMIN", "PERM_CONTENT")
                        .requestMatchers(HttpMethod.GET, "/admin/users/new", "/admin/users/*/edit").hasAnyAuthority("ROLE_ADMIN", "PERM_USERS_MANAGE")
                        .requestMatchers(HttpMethod.GET, "/admin/users", "/admin/users/*").hasAnyAuthority("ROLE_ADMIN", "PERM_USERS_READ")
                        .requestMatchers("/admin/users/**", "/admin/users").hasAnyAuthority("ROLE_ADMIN", "PERM_USERS_MANAGE")
                        .requestMatchers("/admin/commerce/settings").hasRole("ADMIN")
                        .requestMatchers("/admin/commerce/**", "/admin/commerce").hasAnyAuthority("ROLE_ADMIN", "PERM_COMMERCE")
                        .requestMatchers("/admin/businesses/**", "/admin/businesses", "/admin/reference-data/**", "/admin/reference-data",
                                "/admin/verification/**", "/admin/verification", "/admin/pending-changes/**", "/admin/pending-changes",
                                "/admin/duplicates/**", "/admin/duplicates", "/admin/data-checks/**", "/admin/data-checks")
                        .hasAnyAuthority("ROLE_ADMIN", "PERM_CATALOG")
                        .requestMatchers("/admin/analytics/**", "/admin/analytics").hasAnyAuthority("ROLE_ADMIN", "PERM_ANALYTICS")
                        .requestMatchers("/admin/support/**", "/admin/support").hasAnyAuthority("ROLE_ADMIN", "PERM_SUPPORT_INBOX")
                        // SYSTEM: settings, homepage, notifications, health, content, audit log, admin security
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(staffRevalidationFilter(), AnonymousAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .usernameParameter("phoneNumber")
                        .passwordParameter("password")
                        .authenticationDetailsSource(AdminAuthenticationProvider.AdminLoginDetails::new)
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
     * Re-validates a staff session on every request against the database: the account still exists
     * with every authority the session was granted (role change, moderator removed), isn't
     * suspended/banned, and the session started after the last "sign out all admin sessions". An
     * admin whose role now requires 2FA but who hasn't enrolled is sent to the enrolment page.
     */
    private OncePerRequestFilter staffRevalidationFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                boolean staff = auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .anyMatch(a -> a.equals("ROLE_ADMIN_STAFF") || a.equals("ROLE_MODERATOR") || a.equals("ROLE_ADMIN"));
                if (staff) {
                    User user;
                    try {
                        user = userRepository.findById(UUID.fromString(auth.getName())).orElse(null);
                    } catch (IllegalArgumentException e) {
                        user = null;
                    }
                    // The session may never hold an authority the account no longer has (role changed,
                    // moderator removed, demoted) — then it is dropped.
                    boolean stillValid = user != null && AdminAuthorities.stillGranted(auth.getAuthorities(), user)
                            && accountControl.inEffect(user.getId()).isEmpty();
                    HttpSession session = request.getSession();
                    Object loginAt = session.getAttribute(LOGIN_AT);
                    if (loginAt == null) {
                        session.setAttribute(LOGIN_AT, System.currentTimeMillis());
                    } else if ((Long) loginAt < adminSecurity.sessionEpochMillis()) {
                        stillValid = false;
                    }
                    if (!stillValid) {
                        SecurityContextHolder.clearContext();
                        session.invalidate();
                        response.sendRedirect(request.getContextPath() + "/admin/login?denied");
                        return;
                    }
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    if (adminSecurity.mustEnrol(user) && !path.startsWith("/admin/account/2fa")
                            && !path.equals("/admin/logout") && !path.startsWith("/admin/css") && !path.startsWith("/admin/js")) {
                        response.sendRedirect(request.getContextPath() + "/admin/account/2fa?required");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }
}
