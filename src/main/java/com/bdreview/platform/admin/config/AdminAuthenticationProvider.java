package com.bdreview.platform.admin.config;

import com.bdreview.platform.accountcontrol.AccountControlService;
import com.bdreview.platform.accountcontrol.AccountRestrictedException;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.PhoneNumberUtils;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Backs the admin panel's session/form login (mounted at /admin — see
 * {@link AdminSecurityConfig}). Deliberately separate from the JWT-based
 * {@code auth.AuthService} used by the public API: same {@code app_user}
 * table and the same {@link PasswordEncoder} bean, but only ever succeeds
 * for accounts with {@link UserRole#ADMIN}.
 *
 * <p>On success the returned {@link Authentication}'s principal name is the
 * user's UUID (not their phone number), so that downstream code reusing the
 * existing {@code common.CurrentUser} helper — which every moderation
 * service (reports, claims, flagged reviews) already depends on — keeps
 * working unmodified for admin-panel requests.
 */
@Component
public class AdminAuthenticationProvider implements AuthenticationProvider {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountControlService accountControl;

    public AdminAuthenticationProvider(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                       AccountControlService accountControl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountControl = accountControl;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String rawPhone = String.valueOf(authentication.getName());
        String rawPassword = String.valueOf(authentication.getCredentials());

        String phone;
        try {
            phone = PhoneNumberUtils.normalize(rawPhone);
        } catch (BadRequestException ex) {
            throw new BadCredentialsException("Invalid phone number or password");
        }

        User admin = userRepository.findByPhoneNumberAndRole(phone, UserRole.ADMIN).orElse(null);
        if (admin != null && admin.getPasswordHash() != null && passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            checkRestriction(admin);
            List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
            return new UsernamePasswordAuthenticationToken(admin.getId().toString(), null, authorities);
        }

        // V56: community moderators sign in with their own (consumer/business) account password.
        for (User moderator : userRepository.findAllByPhoneNumberAndStaffRole(phone, User.STAFF_MODERATOR)) {
            if (moderator.getPasswordHash() != null && passwordEncoder.matches(rawPassword, moderator.getPasswordHash())) {
                checkRestriction(moderator);
                List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_MODERATOR"));
                return new UsernamePasswordAuthenticationToken(moderator.getId().toString(), null, authorities);
            }
        }
        throw new BadCredentialsException("Invalid phone number or password");
    }

    /** V63: a suspended/banned staff account can't sign in to the panel either; the login is recorded. */
    private void checkRestriction(User user) {
        try {
            accountControl.checkLogin(user.getId(), "ADMIN");
        } catch (AccountRestrictedException e) {
            throw new LockedException(e.getMessage());
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
