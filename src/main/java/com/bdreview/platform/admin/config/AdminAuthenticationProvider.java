package com.bdreview.platform.admin.config;

import com.bdreview.platform.accountcontrol.AccountControlService;
import com.bdreview.platform.accountcontrol.AccountRestrictedException;
import com.bdreview.platform.admin.security.AdminAuthorities;
import com.bdreview.platform.admin.security.AdminSecurityService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.PhoneNumberUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;


/**
 * Backs the admin panel's session/form login (mounted at /admin — see
 * {@link AdminSecurityConfig}). Deliberately separate from the JWT-based
 * {@code auth.AuthService} used by the public API: same {@code app_user}
 * table and the same {@link PasswordEncoder} bean.
 *
 * <p>On success the returned {@link Authentication}'s principal name is the
 * user's UUID (not their phone number), so that downstream code reusing the
 * existing {@code common.CurrentUser} helper keeps working for admin-panel requests.
 *
 * <p>V67: authorities come from the admin permission role ({@link AdminAuthorities}); an account
 * with two-factor authentication on must also send a current authenticator code (or an unused
 * recovery code) in the login form's {@code otp} field.
 */
@Component
public class AdminAuthenticationProvider implements AuthenticationProvider {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountControlService accountControl;
    private final AdminSecurityService adminSecurity;

    public AdminAuthenticationProvider(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                       AccountControlService accountControl, AdminSecurityService adminSecurity) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountControl = accountControl;
        this.adminSecurity = adminSecurity;
    }

    /** The login form's details, carrying the optional 2FA code. */
    public static class AdminLoginDetails extends WebAuthenticationDetails {
        private final String otp;

        public AdminLoginDetails(HttpServletRequest request) {
            super(request);
            this.otp = request.getParameter("otp");
        }

        public String otp() {
            return otp;
        }
    }

    /** Wrong/missing second factor — the login page shows this message instead of "invalid password". */
    public static class TwoFactorRequiredException extends BadCredentialsException {
        public TwoFactorRequiredException(String message) {
            super(message);
        }
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String rawPhone = String.valueOf(authentication.getName());
        String rawPassword = String.valueOf(authentication.getCredentials());
        String otp = authentication.getDetails() instanceof AdminLoginDetails d ? d.otp() : null;

        String phone;
        try {
            phone = PhoneNumberUtils.normalize(rawPhone);
        } catch (BadRequestException ex) {
            throw new BadCredentialsException("Invalid phone number or password");
        }

        User admin = userRepository.findByPhoneNumberAndRole(phone, UserRole.ADMIN).orElse(null);
        if (admin != null && admin.getPasswordHash() != null && passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            return authenticated(admin, otp);
        }

        // V56: community moderators sign in with their own (consumer/business) account password.
        for (User moderator : userRepository.findAllByPhoneNumberAndStaffRole(phone, User.STAFF_MODERATOR)) {
            if (moderator.getPasswordHash() != null && passwordEncoder.matches(rawPassword, moderator.getPasswordHash())) {
                return authenticated(moderator, otp);
            }
        }
        throw new BadCredentialsException("Invalid phone number or password");
    }

    private Authentication authenticated(User user, String otp) {
        if (user.isTotpEnabled()) {
            if (otp == null || otp.isBlank()) {
                throw new TwoFactorRequiredException("Enter the 6-digit code from your authenticator app (or a recovery code).");
            }
            if (!adminSecurity.checkSecondFactor(user, otp)) {
                throw new TwoFactorRequiredException("That authenticator code didn't match — try the current one.");
            }
        }
        checkRestriction(user);
        return new UsernamePasswordAuthenticationToken(user.getId().toString(), null, AdminAuthorities.of(user));
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
