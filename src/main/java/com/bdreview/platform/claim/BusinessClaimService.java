package com.bdreview.platform.claim;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.email.EmailVerificationService;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.otp.OtpService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spec §2: real owner claims an admin-seeded or already-listed business.
 * PHONE and EMAIL verify instantly (OTP/code to the business's own contact
 * channel) and transfer ownership immediately on success — no admin in the
 * loop, same as Yelp's phone/email claim path. DOCUMENT is the manual-review
 * fallback: claimant uploads a supporting file (e.g. utility bill) and an
 * admin approves/rejects it via the existing queue.
 */
@Service
public class BusinessClaimService {

    private final BusinessClaimRepository claimRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final OtpService otpService;
    private final EmailVerificationService emailVerificationService;
    private final ObjectStorageClient objectStorageClient;

    public BusinessClaimService(BusinessClaimRepository claimRepository,
                                 BusinessRepository businessRepository,
                                 UserRepository userRepository,
                                 AuditLogService auditLogService,
                                 OtpService otpService,
                                 EmailVerificationService emailVerificationService,
                                 ObjectStorageClient objectStorageClient) {
        this.claimRepository = claimRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.otpService = otpService;
        this.emailVerificationService = emailVerificationService;
        this.objectStorageClient = objectStorageClient;
    }

    // ---------------------------------------------------------------
    // Instant PHONE verification — OTP sent to the business's own listed number
    // ---------------------------------------------------------------

    public void requestPhoneVerification(UUID businessId) {
        Business business = getBusinessOrThrow(businessId);
        otpService.requestOtp(business.getContactNumber());
    }

    @Transactional
    public BusinessClaim verifyPhoneAndClaim(UUID claimantUserId, UUID businessId, String code) {
        CurrentUser.requireRole("BUSINESS_OWNER");
        Business business = getBusinessOrThrow(businessId);
        ensureClaimable(business, claimantUserId);
        otpService.verifyCode(business.getContactNumber(), code);
        return approveInstantClaim(claimantUserId, businessId, VerificationMethod.PHONE);
    }

    // ---------------------------------------------------------------
    // Instant EMAIL verification — code sent to a claimant-supplied address
    // ---------------------------------------------------------------

    public void requestEmailVerification(String email) {
        emailVerificationService.requestCode(email);
    }

    @Transactional
    public BusinessClaim verifyEmailAndClaim(UUID claimantUserId, UUID businessId, String email, String code) {
        CurrentUser.requireRole("BUSINESS_OWNER");
        Business business = getBusinessOrThrow(businessId);
        ensureClaimable(business, claimantUserId);
        emailVerificationService.verifyCode(email, code);
        return approveInstantClaim(claimantUserId, businessId, VerificationMethod.EMAIL);
    }

    private BusinessClaim approveInstantClaim(UUID claimantUserId, UUID businessId, VerificationMethod method) {
        BusinessClaim claim = claimRepository.save(BusinessClaim.builder()
                .businessId(businessId)
                .claimantUserId(claimantUserId)
                .verificationMethod(method)
                .status(ClaimStatus.APPROVED)
                .resolvedAt(Instant.now())
                .build());
        businessRepository.updateOwner(businessId, claimantUserId);
        businessRepository.markVerified(businessId);
        return claim;
    }

    /** A business already owned by a real (non-admin-seeded) different user can't be silently re-claimed instantly. */
    private void ensureClaimable(Business business, UUID claimantUserId) {
        if (business.getOwnerUserId().equals(claimantUserId)) {
            return;
        }
        User currentOwner = userRepository.findById(business.getOwnerUserId()).orElse(null);
        if (currentOwner != null && currentOwner.getRole() != UserRole.ADMIN) {
            throw new BadRequestException(
                    "This business is already claimed by another owner — file a document-based claim for admin review instead.");
        }
    }

    // ---------------------------------------------------------------
    // DOCUMENT method — manual admin review
    // ---------------------------------------------------------------

    public PreSignedUploadResponse requestDocumentUploadUrl(UUID claimantUserId, String filename) {
        String key = objectStorageClient.buildObjectKey("claim-document", claimantUserId.toString(), filename);
        return new PreSignedUploadResponse(
                objectStorageClient.presignPutUrl(key), key, objectStorageClient.cdnUrlFor(key));
    }

    @Transactional
    public BusinessClaim fileClaim(UUID claimantUserId, FileClaimRequest request) {
        CurrentUser.requireRole("BUSINESS_OWNER");
        if (request.verificationMethod() != VerificationMethod.DOCUMENT) {
            throw new BadRequestException("Use phone or email verification for an instant claim");
        }
        if (request.documentRef() == null || request.documentRef().isBlank()) {
            throw new BadRequestException("A supporting document is required");
        }
        getBusinessOrThrow(request.businessId());

        return claimRepository.save(BusinessClaim.builder()
                .businessId(request.businessId())
                .claimantUserId(claimantUserId)
                .verificationMethod(VerificationMethod.DOCUMENT)
                .documentRef(request.documentRef())
                .status(ClaimStatus.PENDING)
                .build());
    }

    /** Admin-only: fetches the raw claim-document bytes for review — never handed out as a direct storage URL. */
    public ClaimDocument getDocument(UUID claimId) {
        CurrentUser.requireAuthority("PERM_CONTENT");
        BusinessClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found"));
        if (claim.getDocumentRef() == null) {
            throw new ResourceNotFoundException("This claim has no document");
        }
        byte[] bytes = objectStorageClient.getObject(claim.getDocumentRef());
        String contentType = URLConnection.guessContentTypeFromName(claim.getDocumentRef());
        return new ClaimDocument(bytes, contentType != null ? contentType : "application/octet-stream");
    }

    // ---------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------

    public List<BusinessClaim> myClaims(UUID claimantUserId) {
        return claimRepository.findByClaimantUserId(claimantUserId);
    }

    public Page<BusinessClaim> queue(Pageable pageable) {
        return claimRepository.findByStatus(ClaimStatus.PENDING, pageable);
    }

    @Transactional
    public BusinessClaim resolve(UUID claimId, ResolveClaimRequest request) {
        CurrentUser.requireAuthority("PERM_CONTENT");
        BusinessClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found"));

        claim.setStatus(request.approve() ? ClaimStatus.APPROVED : ClaimStatus.REJECTED);
        claim.setResolvedAt(Instant.now());
        claimRepository.save(claim);

        if (request.approve()) {
            businessRepository.updateOwner(claim.getBusinessId(), claim.getClaimantUserId());
            businessRepository.markVerified(claim.getBusinessId());
        }

        auditLogService.log("BUSINESS_CLAIM", claimId,
                request.approve() ? "APPROVED" : "REJECTED", CurrentUser.id(), request.notes());

        return claim;
    }

    private Business getBusinessOrThrow(UUID businessId) {
        return businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }
}
