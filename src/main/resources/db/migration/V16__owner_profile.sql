-- =====================================================================
-- V16__owner_profile.sql
-- Dual-identity redesign: an account's "owner persona" (display name +
-- avatar shown when acting as a business owner) is now tracked separately
-- from the personal identity on app_user, and ownership itself is decided
-- purely by business.owner_user_id — never by app_user.role. See the
-- approved architecture plan for full rationale.
-- =====================================================================

CREATE TABLE owner_profile (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id       UUID NOT NULL UNIQUE REFERENCES app_user(id),
    display_name  VARCHAR(120),
    avatar_url    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backfill: accounts that already own a real (non-admin-placeholder)
-- business get an owner_profile immediately, seeded from their current
-- personal name/photo — otherwise they'd see no owner identity/switcher
-- until their next claim/create, despite already being real owners.
INSERT INTO owner_profile (user_id, display_name, avatar_url)
SELECT DISTINCT b.owner_user_id, u.name, u.profile_photo_url
FROM business b
JOIN app_user u ON u.id = b.owner_user_id
WHERE b.deleted_at IS NULL
  AND u.role <> 'ADMIN'
ON CONFLICT (user_id) DO NOTHING;
