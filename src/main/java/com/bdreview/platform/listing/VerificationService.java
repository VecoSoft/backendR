package com.bdreview.platform.listing;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business verification (V65): owners request the Verified badge (phone call-back or a document),
 * admins approve/reject from Catalog → Verification, and can verify or revoke directly. Every
 * decision is a row in {@code business_verification_request} (the per-business history) plus an
 * audit entry.
 */
@Service
public class VerificationService {

    private final BusinessVerificationRequestRepository repository;
    private final BusinessRepository businessRepository;
    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;
    private final AdminNotifier notifier;

    public VerificationService(BusinessVerificationRequestRepository repository, BusinessRepository businessRepository,
                               JdbcTemplate jdbc, AuditLogService auditLogService, AdminNotifier notifier) {
        this.repository = repository;
        this.businessRepository = businessRepository;
        this.jdbc = jdbc;
        this.auditLogService = auditLogService;
        this.notifier = notifier;
    }

    // ---------------------------------------------------------------- owner

    @Transactional
    public BusinessVerificationRequest request(UUID ownerUserId, UUID businessId, BusinessVerificationRequest.Method method,
                                               String documentRef, String note) {
        Business business = ownedLive(ownerUserId, businessId);
        if (method == null || method == BusinessVerificationRequest.Method.MANUAL) {
            throw new BadRequestException("Choose PHONE or DOCUMENT verification.");
        }
        if (method == BusinessVerificationRequest.Method.DOCUMENT && (documentRef == null || documentRef.isBlank())) {
            throw new BadRequestException("Upload a document for document verification.");
        }
        if (business.isVerified()) {
            throw new BadRequestException("This business is already verified.");
        }
        if (repository.existsByBusinessIdAndStatus(businessId, BusinessVerificationRequest.Status.PENDING)) {
            throw new BadRequestException("A verification request is already waiting for review.");
        }
        return repository.save(BusinessVerificationRequest.builder()
                .businessId(businessId)
                .requestedBy(ownerUserId)
                .method(method)
                .documentRef(blankToNull(documentRef))
                .note(note == null ? null : note.trim().substring(0, Math.min(note.trim().length(), 1000)))
                .build());
    }

    /** The owner withdraws a request that's still waiting for review. */
    @Transactional
    public BusinessVerificationRequest cancel(UUID ownerUserId, UUID businessId, UUID requestId) {
        ownedLive(ownerUserId, businessId);
        BusinessVerificationRequest r = repository.findById(requestId)
                .filter(x -> x.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Verification request not found"));
        if (r.getStatus() != BusinessVerificationRequest.Status.PENDING) {
            throw new BadRequestException("Only a request that's still waiting can be cancelled.");
        }
        r.setStatus(BusinessVerificationRequest.Status.CANCELLED);
        r.setReviewedAt(Instant.now());
        return repository.save(r);
    }

    public List<BusinessVerificationRequest> historyForOwner(UUID ownerUserId, UUID businessId) {
        ownedLive(ownerUserId, businessId);
        return repository.findByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    // ---------------------------------------------------------------- admin

    public Page<BusinessVerificationRequest> queue(BusinessVerificationRequest.Status status, int page) {
        return repository.findByStatusOrderByCreatedAtAsc(
                status == null ? BusinessVerificationRequest.Status.PENDING : status, PageRequest.of(page, 25));
    }

    public List<BusinessVerificationRequest> history(UUID businessId) {
        return repository.findByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    public BusinessVerificationRequest get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Verification request not found"));
    }

    @Transactional
    public void approve(UUID requestId, String reason) {
        String why = requireReason(reason);
        BusinessVerificationRequest r = pending(requestId);
        Business business = live(r.getBusinessId());
        decide(r, BusinessVerificationRequest.Status.APPROVED, why);
        setVerified(business, true, "VERIFICATION_APPROVED", why, r.getMethod());
        notifier.notify(business.getOwnerUserId(), business.getName() + " is now verified",
                "Your verification request was approved — the Verified badge now shows on your listing.", "BUSINESS", business.getId());
    }

    @Transactional
    public void reject(UUID requestId, String reason) {
        String why = requireReason(reason);
        BusinessVerificationRequest r = pending(requestId);
        Business business = live(r.getBusinessId());
        decide(r, BusinessVerificationRequest.Status.REJECTED, why);
        auditLogService.record("BUSINESS", business.getId(), "VERIFICATION_REJECTED", why,
                Map.of("request", r.getId().toString(), "status", "PENDING"), Map.of("status", "REJECTED"));
        notifier.notify(business.getOwnerUserId(), "Verification request not approved",
                "Your verification request for " + business.getName() + " was not approved: " + why, "BUSINESS", business.getId());
    }

    /** Admin verifies a business directly (no owner request), e.g. after an in-person check. */
    @Transactional
    public void verifyManually(UUID businessId, String reason) {
        String why = requireReason(reason);
        Business business = live(businessId);
        if (business.isVerified()) {
            throw new BadRequestException("Already verified.");
        }
        manualEntry(businessId, BusinessVerificationRequest.Status.APPROVED, why);
        setVerified(business, true, "VERIFICATION_GRANTED", why, BusinessVerificationRequest.Method.MANUAL);
    }

    @Transactional
    public void revoke(UUID businessId, String reason) {
        String why = requireReason(reason);
        Business business = live(businessId);
        if (!business.isVerified()) {
            throw new BadRequestException("This business isn't verified.");
        }
        manualEntry(businessId, BusinessVerificationRequest.Status.REVOKED, why);
        setVerified(business, false, "VERIFICATION_REVOKED", why, BusinessVerificationRequest.Method.MANUAL);
        notifier.notify(business.getOwnerUserId(), "Verified badge removed",
                "The Verified badge was removed from " + business.getName() + ": " + why, "BUSINESS", businessId);
    }

    // ----------------------------------------------------------------

    private void setVerified(Business business, boolean verified, String action, String reason,
                             BusinessVerificationRequest.Method method) {
        jdbc.update("UPDATE business SET verified = ?, updated_at = now() WHERE id = ?", verified, business.getId());
        auditLogService.record("BUSINESS", business.getId(), action, reason,
                Map.of("verified", business.isVerified()), Map.of("verified", verified, "method", method.name()));
    }

    private void decide(BusinessVerificationRequest r, BusinessVerificationRequest.Status status, String reason) {
        r.setStatus(status);
        r.setReason(reason);
        r.setReviewedBy(CurrentUser.idOrNull());
        r.setReviewedAt(Instant.now());
        repository.save(r);
    }

    private void manualEntry(UUID businessId, BusinessVerificationRequest.Status status, String reason) {
        repository.save(BusinessVerificationRequest.builder()
                .businessId(businessId)
                .requestedBy(CurrentUser.idOrNull())
                .method(BusinessVerificationRequest.Method.MANUAL)
                .status(status)
                .reason(reason)
                .reviewedBy(CurrentUser.idOrNull())
                .reviewedAt(Instant.now())
                .build());
    }

    private BusinessVerificationRequest pending(UUID id) {
        BusinessVerificationRequest r = get(id);
        if (r.getStatus() != BusinessVerificationRequest.Status.PENDING) {
            throw new BadRequestException("This request was already " + r.getStatus().name().toLowerCase() + ".");
        }
        return r;
    }

    private Business live(UUID businessId) {
        return businessRepository.findById(businessId).filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    private Business ownedLive(UUID ownerUserId, UUID businessId) {
        Business business = live(businessId);
        if (!business.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return business;
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 1000) {
            throw new BadRequestException("The reason can be at most 1000 characters.");
        }
        return trimmed;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
