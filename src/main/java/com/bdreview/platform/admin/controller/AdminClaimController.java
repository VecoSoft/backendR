package com.bdreview.platform.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.claim.BusinessClaim;
import com.bdreview.platform.claim.BusinessClaimService;
import com.bdreview.platform.claim.ClaimDocument;
import com.bdreview.platform.claim.ResolveClaimRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_CONTENT')")
@RequestMapping("/admin/claims")
public class AdminClaimController {

    private final BusinessClaimService businessClaimService;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public AdminClaimController(BusinessClaimService businessClaimService,
                                 BusinessRepository businessRepository,
                                 UserRepository userRepository) {
        this.businessClaimService = businessClaimService;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Integer page, Model model) {
        var results = businessClaimService.queue(PageRequest.of(AdminSupport.pageOrDefault(page), 20));

        Map<UUID, String> businessNames = new HashMap<>();
        Map<UUID, String> claimantPhones = new HashMap<>();
        for (BusinessClaim claim : results.getContent()) {
            businessRepository.findById(claim.getBusinessId()).ifPresent(b -> businessNames.put(claim.getId(), b.getName()));
            userRepository.findById(claim.getClaimantUserId()).ifPresent(u -> claimantPhones.put(claim.getId(), u.getPhoneNumber()));
        }

        model.addAttribute("results", results);
        model.addAttribute("businessNames", businessNames);
        model.addAttribute("claimantPhones", claimantPhones);
        model.addAttribute("active", "claims");
        return "admin/claims/list";
    }

    @GetMapping("/{id}/document")
    @ResponseBody
    public ResponseEntity<byte[]> document(@PathVariable UUID id) {
        ClaimDocument document = businessClaimService.getDocument(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .body(document.bytes());
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String notes,
                           RedirectAttributes redirectAttributes) {
        return resolve(id, true, notes, redirectAttributes);
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String notes,
                          RedirectAttributes redirectAttributes) {
        return resolve(id, false, notes, redirectAttributes);
    }

    private String resolve(UUID id, boolean approve, String notes, RedirectAttributes redirectAttributes) {
        try {
            businessClaimService.resolve(id, new ResolveClaimRequest(approve, notes));
            redirectAttributes.addFlashAttribute("successMessage", approve ? "Claim approved." : "Claim rejected.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/claims";
    }
}
