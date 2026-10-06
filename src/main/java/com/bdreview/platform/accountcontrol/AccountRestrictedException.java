package com.bdreview.platform.accountcontrol;

import java.time.Instant;

/**
 * The caller's account is suspended or banned — answered as 403 with code ACCOUNT_SUSPENDED /
 * ACCOUNT_BANNED, the admin's reason and the end date (see GlobalExceptionHandler), so the app
 * can tell the user why and until when.
 */
public class AccountRestrictedException extends RuntimeException {

    private final UserRestriction.Type type;
    private final String reason;
    private final Instant endsAt;

    public AccountRestrictedException(UserRestriction.Type type, String reason, Instant endsAt, String message) {
        super(message);
        this.type = type;
        this.reason = reason;
        this.endsAt = endsAt;
    }

    public String code() {
        return type == UserRestriction.Type.BAN ? "ACCOUNT_BANNED" : "ACCOUNT_SUSPENDED";
    }

    public UserRestriction.Type type() {
        return type;
    }

    public String reason() {
        return reason;
    }

    public Instant endsAt() {
        return endsAt;
    }
}
