package com.bdreview.platform.listing;

import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owner side of listing integrity (V65): request the Verified badge, see the verification history,
 * and see a protected edit that is waiting for admin approval.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class ListingOwnerController {

    private final VerificationService verificationService;
    private final ProtectedEditService protectedEdits;
    private final BusinessRepository businessRepository;

    public ListingOwnerController(VerificationService verificationService, ProtectedEditService protectedEdits,
                                  BusinessRepository businessRepository) {
        this.verificationService = verificationService;
        this.protectedEdits = protectedEdits;
        this.businessRepository = businessRepository;
    }

    public record VerificationRequestBody(BusinessVerificationRequest.Method method, String documentRef, String note) {
    }

    public record VerificationView(UUID id, String method, String status, String note, String reason,
                                   Instant createdAt, Instant reviewedAt) {
        static VerificationView of(BusinessVerificationRequest r) {
            return new VerificationView(r.getId(), r.getMethod().name(), r.getStatus().name(), r.getNote(), r.getReason(),
                    r.getCreatedAt(), r.getReviewedAt());
        }
    }

    public record PendingChangeView(UUID id, Map<String, Object> before, Map<String, Object> after,
                                    List<String> changedFields, Instant createdAt) {
    }

    @PostMapping("/verification-requests")
    public VerificationView requestVerification(@PathVariable UUID businessId, @RequestBody VerificationRequestBody body) {
        return VerificationView.of(verificationService.request(CurrentUser.id(), businessId, body.method(),
                body.documentRef(), body.note()));
    }

    @DeleteMapping("/verification-requests/{requestId}")
    public VerificationView cancelVerification(@PathVariable UUID businessId, @PathVariable UUID requestId) {
        return VerificationView.of(verificationService.cancel(CurrentUser.id(), businessId, requestId));
    }

    @GetMapping("/verification-requests")
    public List<VerificationView> verificationHistory(@PathVariable UUID businessId) {
        return verificationService.historyForOwner(CurrentUser.id(), businessId).stream().map(VerificationView::of).toList();
    }

    /** V67: the owner withdraws a protected edit that is still waiting for review. */
    @DeleteMapping("/pending-changes/{changeId}")
    public ResponseEntity<Void> cancelPendingChange(@PathVariable UUID businessId, @PathVariable UUID changeId) {
        protectedEdits.cancelByOwner(CurrentUser.id(), businessId, changeId);
        return ResponseEntity.noContent().build();
    }

    /** The protected edit waiting for review (204 when none). Owner only. */
    @GetMapping("/pending-changes")
    public ResponseEntity<PendingChangeView> pendingChange(@PathVariable UUID businessId) {
        var business = businessRepository.findById(businessId).filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(CurrentUser.id())) {
            throw new ForbiddenException("You do not own this business listing");
        }
        BusinessPendingChange change = protectedEdits.pendingFor(businessId);
        if (change == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new PendingChangeView(change.getId(), change.getBeforeJson(), change.getAfterJson(),
                ProtectedEditService.changedFields(change.getBeforeJson(), change.getAfterJson()), change.getCreatedAt()));
    }
}
