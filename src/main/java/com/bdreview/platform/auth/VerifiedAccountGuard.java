package com.bdreview.platform.auth;

import com.bdreview.platform.accountlink.AccountLinkService;
import com.bdreview.platform.common.CodedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * V70: orders, bookings and business claims need an account with a verified e-mail (the app no
 * longer asks for a phone number; the business reaches the customer through in-app chat). A
 * business account counts as verified through the personal account it is linked to. Phone-only
 * accounts from before V70 get EMAIL_VERIFICATION_REQUIRED, and the app offers "Add email".
 */
@Component
public class VerifiedAccountGuard {

    private final UserRepository userRepository;
    private final AccountLinkService accountLinkService;

    public VerifiedAccountGuard(UserRepository userRepository, AccountLinkService accountLinkService) {
        this.userRepository = userRepository;
        this.accountLinkService = accountLinkService;
    }

    public void requireVerifiedEmail(UUID userId) {
        if (isVerified(userId)) {
            return;
        }
        boolean viaPartner = accountLinkService.partnerOf(userId).map(this::isVerified).orElse(false);
        if (!viaPartner) {
            throw new CodedException(HttpStatus.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED",
                    "Add and verify an e-mail address on your account first.");
        }
    }

    private boolean isVerified(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getEmail() != null && u.getEmailVerifiedAt() != null && u.getAccountStatus() == AccountStatus.ACTIVE)
                .orElse(false);
    }
}
