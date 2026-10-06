package com.bdreview.platform.listing;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An owner's request for the Verified badge (V65), or an admin's manual verify/revoke entry —
 * together they are the business's verification history (who, when, method).
 */
@Entity
@Table(name = "business_verification_request")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessVerificationRequest {

    public enum Method { PHONE, DOCUMENT, MANUAL }

    /** CANCELLED (V66) = the owner withdrew it before review. */
    public enum Status { PENDING, APPROVED, REJECTED, REVOKED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Method method;

    /** Storage key of an uploaded document (claim-document/… — ADMIN-only at the storage layer). */
    @Column(name = "document_ref", columnDefinition = "text")
    private String documentRef;

    @Column(columnDefinition = "text")
    private String note;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
