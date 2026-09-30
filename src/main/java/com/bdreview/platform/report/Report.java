package com.bdreview.platform.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "report")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Report {

    /** SLA window an admin has to resolve a report before it's flagged overdue in the queue. */
    private static final int SLA_HOURS = 48;

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "reporter_user_id", nullable = false)
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportReason reason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status = ReportStatus.PENDING;

    /** "R-" + a sequential number (report_reference_seq), assigned once in ReportService#create. */
    @Column(name = "reference_code", nullable = false, unique = true, length = 20)
    private String referenceCode;

    /** Admin's internal note, set on resolve — never sent to the reporter verbatim. */
    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Column(name = "reporter_notified_at")
    private Instant reporterNotifiedAt;

    @Column(name = "target_owner_notified_at")
    private Instant targetOwnerNotifiedAt;

    /** V56: who resolved/dismissed it and when (ADMIN or MODERATOR). */
    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority = Priority.NORMAL;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.dueAt = this.createdAt.plus(SLA_HOURS, ChronoUnit.HOURS);
    }

    /** SLA breach flag for the admin queue — still PENDING past its due date. No auto-escalation. */
    @Transient
    @JsonProperty("isOverdue")
    public boolean isOverdue() {
        return status == ReportStatus.PENDING && dueAt != null && Instant.now().isAfter(dueAt);
    }
}
