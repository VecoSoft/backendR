package com.bdreview.platform.auth;

/** V70. An email signup stays EMAIL_UNVERIFIED (can't log in) until its 6-digit code is entered; removed after 7 days. */
public enum AccountStatus {
    ACTIVE,
    EMAIL_UNVERIFIED
}
