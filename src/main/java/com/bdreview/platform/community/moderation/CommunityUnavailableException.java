package com.bdreview.platform.community.moderation;

/** Community switched off by an admin (maintenance) — mapped to 503 with the admin's maintenance message. */
public class CommunityUnavailableException extends RuntimeException {
    public CommunityUnavailableException(String message) {
        super(message);
    }
}
