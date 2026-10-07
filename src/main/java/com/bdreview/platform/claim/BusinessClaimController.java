package com.bdreview.platform.claim;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
public class BusinessClaimController {

    private final BusinessClaimService claimService;
    private final UserRepository userRepository;

    public BusinessClaimController(BusinessClaimService claimService, UserRepository userRepository) {
        this.claimService = claimService;
        this.userRepository = userRepository;
    }

    @PostMapping("/phone/request")
    public ResponseEntity<Void> requestPhone(@Valid @RequestBody BusinessIdRequest request) {
        claimService.requestPhoneVerification(request.businessId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/phone/verify")
    public ResponseEntity<BusinessClaim> verifyPhone(@Valid @RequestBody VerifyPhoneClaimRequest request) {
        return ResponseEntity.ok(claimService.verifyPhoneAndClaim(CurrentUser.id(), request.businessId(), request.code()));
    }

    @PostMapping("/email/request")
    public ResponseEntity<Void> requestEmail(@Valid @RequestBody RequestEmailClaimRequest request) {
        claimService.requestEmailVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email/verify")
    public ResponseEntity<BusinessClaim> verifyEmail(@Valid @RequestBody VerifyEmailClaimRequest request) {
        return ResponseEntity.ok(
                claimService.verifyEmailAndClaim(CurrentUser.id(), request.businessId(), request.email(), request.code()));
    }

    @PostMapping("/document/upload-url")
    public ResponseEntity<PreSignedUploadResponse> documentUploadUrl(@RequestParam String filename) {
        return ResponseEntity.ok(claimService.requestDocumentUploadUrl(CurrentUser.id(), filename));
    }

    @PostMapping
    public ResponseEntity<BusinessClaim> fileClaim(@Valid @RequestBody FileClaimRequest request) {
        return ResponseEntity.ok(claimService.fileClaim(CurrentUser.id(), request));
    }

    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_CONTENT')")
    @GetMapping("/{id}/document")
    public ResponseEntity<byte[]> document(@PathVariable UUID id) {
        ClaimDocument document = claimService.getDocument(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .body(document.bytes());
    }

    @GetMapping("/mine")
    public ResponseEntity<List<BusinessClaim>> mine() {
        return ResponseEntity.ok(claimService.myClaims(CurrentUser.id()));
    }

    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_CONTENT')")
    @GetMapping("/queue")
    public ResponseEntity<PageResponse<BusinessClaimResponse>> queue(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        CurrentUser.requireAuthority("PERM_CONTENT");
        var results = claimService.queue(PageRequest.of(page, size))
                .map(c -> BusinessClaimResponse.from(c, userRepository.findById(c.getClaimantUserId())
                        .map(User::getName).orElse(null)));
        return ResponseEntity.ok(PageResponse.of(results));
    }

    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_CONTENT')")
    @PostMapping("/{id}/resolve")
    public ResponseEntity<BusinessClaim> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveClaimRequest request) {
        return ResponseEntity.ok(claimService.resolve(id, request));
    }
}
