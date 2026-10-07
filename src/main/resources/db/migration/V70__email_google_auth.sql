-- V70: user sign-in moves from phone + OTP to Google Sign-In and email + password.
--
-- The admin panel login (phone + password + TOTP) is unchanged, so admin accounts keep their phone.
-- Existing phone data is kept as it is; the app just stops collecting and showing it. Phone OTP code
-- stays behind the phone_otp feature flag (off).

ALTER TABLE app_user ALTER COLUMN phone_number DROP NOT NULL;

ALTER TABLE app_user
    ADD COLUMN email               VARCHAR(254),
    ADD COLUMN email_verified_at   TIMESTAMPTZ,
    ADD COLUMN google_sub          VARCHAR(255),
    -- GOOGLE / PASSWORD / BOTH for the new sign-in methods; PHONE for accounts created before V70.
    ADD COLUMN auth_provider       VARCHAR(10) NOT NULL DEFAULT 'PHONE',
    -- ACTIVE, or EMAIL_UNVERIFIED for an email signup whose code hasn't been entered yet (can't log in).
    ADD COLUMN account_status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- Password login lockout: 5 failures lock the account for 15 minutes.
    ADD COLUMN failed_login_count  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN login_locked_until  TIMESTAMPTZ;

ALTER TABLE app_user ADD CONSTRAINT chk_app_user_auth_provider
    CHECK (auth_provider IN ('GOOGLE', 'PASSWORD', 'BOTH', 'PHONE'));
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_account_status
    CHECK (account_status IN ('ACTIVE', 'EMAIL_UNVERIFIED'));

-- One account per email, compared case-insensitively (emails are also stored lowercased).
CREATE UNIQUE INDEX uq_app_user_email_lower ON app_user (lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_app_user_google_sub ON app_user (google_sub) WHERE google_sub IS NOT NULL;
-- Cleanup of email signups never verified (deleted after 7 days).
CREATE INDEX idx_app_user_unverified ON app_user (created_at) WHERE account_status = 'EMAIL_UNVERIFIED';

-- Six-digit codes sent by email: verify a new account, reset a password, add an email to an
-- existing (phone-only) account. Only an HMAC of the code is stored.
CREATE TABLE auth_email_code (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    purpose      VARCHAR(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'ADD_EMAIL')),
    email        VARCHAR(254) NOT NULL,
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash    VARCHAR(100) NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    request_ip   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_email_code_lookup ON auth_email_code (email, purpose, created_at DESC);

-- Login events now also record failed and locked password attempts.
ALTER TABLE user_login_event ALTER COLUMN outcome TYPE VARCHAR(20);

-- Orders and bookings no longer ask the customer for a phone number; the business reaches the
-- customer through in-app chat. Existing snapshots are kept.
ALTER TABLE business_order ALTER COLUMN customer_phone_snapshot DROP NOT NULL;
ALTER TABLE business_booking ALTER COLUMN customer_phone_snapshot DROP NOT NULL;
