package com.bdreview.platform.qr;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Business QR V1 — one permanent QR per business, resolving to a business id
 * (never a slug) so a later slug change never breaks an already-printed QR.
 * Deliberately minimal: no expiry, no regeneration, no per-table/product QR.
 */
@Service
public class QrService {

    private static final String TOKEN_PREFIX = "JCH_";
    private static final int TOKEN_RANDOM_LENGTH = 12;
    private static final String TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BusinessQrRepository qrRepository;
    private final BusinessRepository businessRepository;
    /** Package-visible, non-final: real callers get this self-injected by Spring; unit tests wire it directly. */
    QrService self;

    public QrService(BusinessQrRepository qrRepository, BusinessRepository businessRepository,
                     @Lazy QrService self) {
        this.qrRepository = qrRepository;
        this.businessRepository = businessRepository;
        this.self = self;
    }

    /** Owner-facing get-or-create. Never exposes another owner's business. */
    public QrResponse getOrCreateForOwner(UUID ownerUserId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return QrResponse.from(getOrCreate(businessId));
    }

    /**
     * Find-or-insert with a retry on the {@code UNIQUE(business_id)} constraint —
     * so two simultaneous first-view requests can never create two QR rows for
     * the same business. The insert attempt runs in its own transaction (via
     * {@link #insertNew}) so a lost race there never aborts this method's own
     * retry read — same shape as {@code BookingService.attemptPlacement}.
     */
    BusinessQr getOrCreate(UUID businessId) {
        return qrRepository.findByBusinessId(businessId).orElseGet(() -> {
            try {
                return self.insertNew(businessId);
            } catch (DataIntegrityViolationException raced) {
                return qrRepository.findByBusinessId(businessId).orElseThrow(() -> raced);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    BusinessQr insertNew(UUID businessId) {
        return qrRepository.saveAndFlush(BusinessQr.builder()
                .businessId(businessId)
                .qrToken(generateUniqueToken())
                .status(QrStatus.ACTIVE)
                .build());
    }

    public record QrResolveResponse(UUID businessId, String slug) {
    }

    /** Public resolution — token to the business's *current* slug, never a cached one. */
    @Transactional(readOnly = true)
    public QrResolveResponse resolvePublic(String token) {
        BusinessQr qr = qrRepository.findByQrTokenAndStatus(token, QrStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("QR code not found"));
        Business business = businessRepository.findById(qr.getBusinessId())
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("QR code not found"));
        return new QrResolveResponse(business.getId(), business.getSlug());
    }

    private String generateUniqueToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = TOKEN_PREFIX + randomSuffix();
            if (!qrRepository.existsByQrToken(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique QR token");
    }

    private static String randomSuffix() {
        StringBuilder sb = new StringBuilder(TOKEN_RANDOM_LENGTH);
        for (int i = 0; i < TOKEN_RANDOM_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }
}
