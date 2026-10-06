package com.bdreview.platform.admin.controller;

import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.photomod.PhotoModeration;
import com.bdreview.platform.photomod.PhotoModerationService;
import com.bdreview.platform.photomod.PhotoSource;
import com.bdreview.platform.photomod.PhotoStatus;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLConnection;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Moderation → Photos (V63): the review queue for new photos, bulk approve/reject, and the
 * "Require approval for new photos" setting. ADMIN only.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/photos")
public class AdminPhotoController {

    private static final int PAGE_SIZE = 24;

    private final PhotoModerationService photoModeration;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final ObjectStorageClient objectStorageClient;

    public AdminPhotoController(PhotoModerationService photoModeration, UserRepository userRepository,
                                BusinessRepository businessRepository, ObjectStorageClient objectStorageClient) {
        this.photoModeration = photoModeration;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.objectStorageClient = objectStorageClient;
    }

    @GetMapping
    public String queue(@RequestParam(required = false) PhotoStatus status,
                        @RequestParam(required = false) PhotoSource source,
                        @RequestParam(required = false) Integer page,
                        Model model) {
        PhotoStatus shown = status == null ? PhotoStatus.PENDING : status;
        Page<PhotoModeration> results = photoModeration.queue(shown, source, AdminSupport.pageOrDefault(page), PAGE_SIZE);

        Set<UUID> userIds = results.stream().map(PhotoModeration::getUploaderUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> businessIds = results.stream().map(PhotoModeration::getBusinessId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, User> uploaders = userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, Business> businesses = businessRepository.findAllById(businessIds).stream()
                .collect(Collectors.toMap(Business::getId, Function.identity()));

        model.addAttribute("results", results);
        model.addAttribute("uploaders", uploaders);
        model.addAttribute("businesses", businesses);
        model.addAttribute("status", shown);
        model.addAttribute("source", source);
        model.addAttribute("statuses", List.of(PhotoStatus.PENDING, PhotoStatus.APPROVED, PhotoStatus.REJECTED,
                PhotoStatus.DELETED, PhotoStatus.WITHDRAWN));
        model.addAttribute("sources", PhotoSource.values());
        model.addAttribute("pendingCount", photoModeration.pendingCount());
        model.addAttribute("approvalRequired", photoModeration.approvalRequired());
        model.addAttribute("approvalRequiredDefault", photoModeration.approvalRequiredDefault());
        model.addAttribute("active", "photos");
        return "admin/photos/queue";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String reason,
                          @RequestParam(required = false) String back, RedirectAttributes ra) {
        try {
            photoModeration.approve(id, reason);
            ra.addFlashAttribute("successMessage", "Photo approved — it is public now.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return redirectBack(back);
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason,
                         @RequestParam(required = false) String back, RedirectAttributes ra) {
        try {
            photoModeration.reject(id, reason);
            ra.addFlashAttribute("successMessage", "Photo rejected — its file is no longer served.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return redirectBack(back);
    }

    @PostMapping("/bulk")
    public String bulk(@RequestParam(required = false) List<UUID> ids, @RequestParam String action,
                       @RequestParam(required = false) String reason, @RequestParam(required = false) String back,
                       RedirectAttributes ra) {
        try {
            PhotoStatus decision = "approve".equals(action) ? PhotoStatus.APPROVED
                    : "reject".equals(action) ? PhotoStatus.REJECTED : null;
            if (decision == null) {
                throw new com.bdreview.platform.common.BadRequestException("Unknown action.");
            }
            int changed = photoModeration.decideAll(ids, decision, reason);
            ra.addFlashAttribute("successMessage", changed + " photo(s) " + (decision == PhotoStatus.APPROVED ? "approved." : "rejected."));
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return redirectBack(back);
    }

    @PostMapping("/settings")
    public String settings(@RequestParam(defaultValue = "false") boolean approvalRequired,
                           @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            photoModeration.setApprovalRequired(approvalRequired, reason);
            ra.addFlashAttribute("successMessage", approvalRequired
                    ? "New photos now wait for approval before they are public."
                    : "New photos are now public immediately.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/photos";
    }

    /**
     * Thumbnail proxy: streams the stored object through the admin session, so pending and
     * rejected photos (whose public file URL may be blocked) still render for reviewers.
     */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        PhotoModeration p = photoModeration.get(id);
        if (p.getObjectKey() == null) {
            return ResponseEntity.status(302).header("Location", p.getUrl()).build();
        }
        String type = URLConnection.guessContentTypeFromName(p.getObjectKey());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(type != null ? MediaType.parseMediaType(type) : MediaType.APPLICATION_OCTET_STREAM)
                .body(objectStorageClient.getObject(p.getObjectKey()));
    }

    /** Only same-panel paths are accepted as a return target. */
    private static String redirectBack(String back) {
        return back != null && back.startsWith("/admin/") && !back.startsWith("//") ? "redirect:" + back : "redirect:/admin/photos";
    }
}
