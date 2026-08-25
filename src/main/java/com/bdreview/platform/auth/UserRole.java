package com.bdreview.platform.auth;

/**
 * Two-account model (mirrors Yelp's yelp.com vs biz.yelp.com): CONSUMER and
 * BUSINESS_OWNER are genuinely separate {@code app_user} rows, each with its
 * own password/name/photo — one phone number can back at most one of each
 * (V17 migration's {@code UNIQUE(phone_number, role)}), optionally paired
 * via {@code accountlink.AccountLink} for a frictionless switch
 * ({@code AuthService#switchAccount}). Role is a real, load-bearing account-
 * type discriminator again: only a BUSINESS_OWNER account can create/claim a
 * business (see {@code business.BusinessService#create},
 * {@code claim.BusinessClaimService}). ADMIN is a separate,
 * internally-provisioned account type for moderation staff (spec §12) —
 * never created through the public OTP registration flow.
 */
public enum UserRole {
    CONSUMER,
    BUSINESS_OWNER,
    ADMIN
}
