package com.bdreview.platform.admin.service;

import com.bdreview.platform.admin.form.UserForm;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.PhoneNumberUtils;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Admin-panel user management: same {@code app_user} table as the public API, read/managed here instead. */
@Service
public class AdminUserService {

    private static final int PASSWORD_MIN_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Page<User> search(String query, UserRole role, String status, int page) {
        return userRepository.adminSearch(query == null || query.isBlank() ? null : query, role,
                status == null || status.isBlank() ? null : status, java.time.Instant.now(), PageRequest.of(page, 20));
    }

    public User get(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /** Creates a new staff/admin account directly — the public API deliberately never allows this (spec §5). */
    @Transactional
    public User createAdmin(UserForm form) {
        String phone = PhoneNumberUtils.normalize(form.getPhoneNumber());
        if (userRepository.existsByPhoneNumberAndRole(phone, UserRole.ADMIN)) {
            throw new BadRequestException("This phone number is already registered as an admin.");
        }
        validatePassword(form.getPassword());

        return userRepository.save(User.builder()
                .phoneNumber(phone)
                .role(UserRole.ADMIN)
                .adminRole(com.bdreview.platform.admin.security.AdminRole.parse(form.getAdminRole()).name())
                .otpVerified(true)
                .passwordHash(passwordEncoder.encode(form.getPassword()))
                .name(form.getName())
                .preferredLanguage(form.getPreferredLanguage() == null || form.getPreferredLanguage().isBlank()
                        ? "en" : form.getPreferredLanguage())
                .build());
    }

    @Transactional
    public User updateProfile(UUID id, UserForm form) {
        User user = get(id);
        user.setName(form.getName());
        user.setPreferredLanguage(form.getPreferredLanguage());
        // V63: role changes go through AccountControlService.changeRole (reason + audit + session revoke).
        return userRepository.save(user);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        validatePassword(newPassword);
        User user = get(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new BadRequestException("Password must be at least " + PASSWORD_MIN_LENGTH + " characters.");
        }
    }
}
