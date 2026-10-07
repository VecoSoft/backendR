package com.bdreview.platform.features;

/**
 * Site-wide switches an ADMIN can flip from the admin panel (System → Settings). Each has a
 * deployment default under {@code features.<property>.enabled} in application.yml; a row in
 * {@code platform_setting} overrides it. Turning one off hides the related UI in the Next.js app
 * (via the public settings endpoint) and makes its public endpoints answer 404 "Feature disabled"
 * (see {@link FeatureGateInterceptor}).
 */
public enum PlatformFeature {

    NID_VERIFICATION("nid-verification", false, "NID verification",
            "National-ID uploads and checks. Off: NID uploads are refused and stored NID files are not served."),
    ORDERING("ordering", true, "Ordering",
            "Customers can place food/product orders; owners manage the order queue and delivery zones."),
    BOOKINGS("bookings", true, "Bookings",
            "Appointment and table bookings, availability and the owner booking queue."),
    COMMUNITY("community", true, "Community",
            "The \"Join Community\" feed, posts, comments, profiles and community search."),
    PROMOTIONS("promotions", true, "Promotions",
            "Business posts, the design studio, paid boosts and sponsored placements."),
    OWNER_CHAT("owner-chat", true, "Owner chat",
            "Customers messaging businesses and the owner inbox."),
    NEW_SIGNUPS("new-signups", true, "New sign-ups",
            "Creating new consumer and business accounts. Existing users can still log in."),
    GOOGLE_LOGIN("google-login", true, "Google sign-in",
            "\"Continue with Google\" on the app (sign in, sign up and linking Google to an account)."),
    PASSWORD_LOGIN("password-login", true, "Email + password sign-in",
            "Email/password sign-up, login, e-mail verification and forgot-password on the app."),
    PHONE_OTP("phone-otp", false, "Claim by SMS code",
            "Claiming a business with an SMS code sent to its listed number. Phone login was removed (V70); off: only e-mail and document claims."),
    MAINTENANCE_MODE("maintenance-mode", false, "Maintenance mode",
            "Site-wide: every API call except login and the public settings answers 503 with the message below. Admins are not affected.");

    private final String property;
    private final boolean fallbackDefault;
    private final String label;
    private final String description;

    PlatformFeature(String property, boolean fallbackDefault, String label, String description) {
        this.property = property;
        this.fallbackDefault = fallbackDefault;
        this.label = label;
        this.description = description;
    }

    /** application.yml key holding the deployment default. */
    public String configKey() {
        return "features." + property + ".enabled";
    }

    public boolean fallbackDefault() {
        return fallbackDefault;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
