package com.bdreview.platform.admin.config;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.PhoneNumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * First-run convenience only: if the {@code app_user} table has no ADMIN
 * account yet (spec §5 — ADMIN cannot be self-registered through the public
 * signup flow), provision one from {@code app.admin.*} config so the panel
 * at /admin is reachable immediately. No-ops on every subsequent boot.
 */
@Component
public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean bootstrapEnabled;
    private final String defaultPhone;
    private final String defaultPassword;
    private final String defaultName;

    public AdminBootstrapRunner(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.admin.bootstrap-enabled:true}") boolean bootstrapEnabled,
                                 @Value("${app.admin.default-phone}") String defaultPhone,
                                 @Value("${app.admin.default-password}") String defaultPassword,
                                 @Value("${app.admin.default-name}") String defaultName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEnabled = bootstrapEnabled;
        this.defaultPhone = defaultPhone;
        this.defaultPassword = defaultPassword;
        this.defaultName = defaultName;
    }

    @Override
    public void run(String... args) {
        if (!bootstrapEnabled) {
            return;
        }
        boolean anyAdminExists = userRepository.findAll().stream().anyMatch(u -> u.getRole() == UserRole.ADMIN);
        if (anyAdminExists) {
            return;
        }

        String phone = PhoneNumberUtils.normalize(defaultPhone);
        if (userRepository.existsByPhoneNumberAndRole(phone, UserRole.ADMIN)) {
            log.warn("Admin bootstrap skipped: phone {} is already registered as an admin.", phone);
            return;
        }

        userRepository.save(User.builder()
                .phoneNumber(phone)
                .role(UserRole.ADMIN)
                .otpVerified(true)
                .passwordHash(passwordEncoder.encode(defaultPassword))
                .name(defaultName)
                .build());

        log.info("=======================================================================");
        log.info(" Admin panel bootstrap: created default admin account");
        log.info("   URL:      /admin/login");
        log.info("   Phone:    {}", phone);
        log.info("   Password: (as configured via app.admin.default-password)");
        log.info("   Please log in and change this password, or set ADMIN_DEFAULT_* env vars.");
        log.info("=======================================================================");
    }
}
