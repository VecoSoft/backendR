package com.bdreview.platform.listing;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A protected edit (V65): the owner of a VERIFIED business changed its name, phone, address or
 * category. The public listing keeps {@link #beforeJson} until an admin approves {@link #afterJson}.
 * Both maps only hold the protected fields (name, contactNumber, categoryId, cityId, areaId,
 * latitude, longitude).
 */
@Entity
@Table(name = "business_pending_change")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessPendingChange {

    public enum Status { PENDING, APPROVED, REJECTED, SUPERSEDED, CANCELLED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> afterJson;

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
