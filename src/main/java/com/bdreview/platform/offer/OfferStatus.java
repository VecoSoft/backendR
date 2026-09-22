package com.bdreview.platform.offer;

/**
 * Stored status only ever tracks the owner/admin-driven lifecycle
 * (DRAFT -> PENDING_APPROVAL -> ACTIVE -> CANCELLED, or REJECTED). EXPIRED
 * is never written by the app — it's derived from validUntil at read/claim
 * time (see OfferService#effectiveStatus) so there's no scheduled job
 * flipping rows. It stays a real enum value because admins/owners still
 * need to see "this WAS active and ran its course" as a distinct outcome
 * from CANCELLED/REJECTED.
 */
public enum OfferStatus {
    DRAFT, PENDING_APPROVAL, ACTIVE, EXPIRED, CANCELLED, REJECTED
}
