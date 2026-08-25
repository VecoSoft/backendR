package com.bdreview.platform.accountlink;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Pairs one CONSUMER account with one BUSINESS_OWNER account belonging to
 * the same person (same phone number), enabling a frictionless switch
 * between them (see {@code auth.AuthService#switchAccount}). Strictly 1:1 —
 * both columns are unique, so a given account can be linked to at most one
 * counterpart. This never grants any permission by itself; ownership and
 * role checks are always evaluated independently on the target account.
 */
@Entity
@Table(name = "account_link")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AccountLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "consumer_user_id", nullable = false, unique = true)
    private UUID consumerUserId;

    @Column(name = "business_user_id", nullable = false, unique = true)
    private UUID businessUserId;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @PrePersist
    void onCreate() {
        this.linkedAt = Instant.now();
    }
}
