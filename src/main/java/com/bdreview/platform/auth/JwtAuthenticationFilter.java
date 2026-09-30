package com.bdreview.platform.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ObjectProvider<UserRepository> userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectProvider<UserRepository> userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                String userId = claims.getSubject();
                String role = claims.get("role", String.class);
                List<SimpleGrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                // Community MODERATOR is a staff flag on a normal account (V56), not a JWT role —
                // resolved from the database on admin API calls only, so revoking it is immediate.
                if (!"ADMIN".equals(role) && request.getRequestURI().startsWith("/api/v1/admin/")) {
                    UserRepository users = userRepository.getIfAvailable();
                    if (users != null && users.findById(UUID.fromString(userId)).map(User::isModerator).orElse(false)) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_MODERATOR"));
                    }
                }
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                // leave unauthenticated -> downstream authorization rules reject as needed
            }
        }
        filterChain.doFilter(request, response);
    }
}
