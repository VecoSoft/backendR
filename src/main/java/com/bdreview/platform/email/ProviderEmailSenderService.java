package com.bdreview.platform.email;

import org.springframework.stereotype.Component;

/** The business-claim e-mail code ({@link EmailVerificationService}) goes out through the configured provider. */
@Component
public class ProviderEmailSenderService implements EmailSenderService {

    private final EmailService emailService;

    public ProviderEmailSenderService(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        emailService.send(email, code + " is your Jachai business verification code",
                "Your Jachai business verification code is " + code + ".\n\n"
                        + "Enter it in the app to confirm you own this business's e-mail address. "
                        + "If you didn't ask for this, ignore this email.");
    }
}
