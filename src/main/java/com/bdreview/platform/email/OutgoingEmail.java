package com.bdreview.platform.email;

/** One transactional e-mail: plain text, with an HTML version derived from it by {@link EmailService}. */
public record OutgoingEmail(String to, String subject, String text, String html) {
}
