package com.bdreview.platform.admin.controller;

import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.photomod.PhotoModeration;
import com.bdreview.platform.photomod.PhotoModerationService;
import com.bdreview.platform.support.SupportService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLConnection;
import java.util.UUID;

/** Support inbox (V67): tickets from Help → Contact support. SUPPORT and SUPER_ADMIN. */
@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_SUPPORT_INBOX')")
@RequestMapping("/admin/support")
public class AdminSupportController {

    private final SupportService support;
    private final PhotoModerationService photoModeration;
    private final ObjectStorageClient storage;

    public AdminSupportController(SupportService support, PhotoModerationService photoModeration, ObjectStorageClient storage) {
        this.support = support;
        this.photoModeration = photoModeration;
        this.storage = storage;
    }

    @GetMapping
    public String inbox(@RequestParam(required = false) String status, @RequestParam(required = false) String category,
                        @RequestParam(required = false) String assignee, @RequestParam(required = false) String q, Model model) {
        model.addAttribute("tickets", support.inbox(status, category, assignee, q));
        model.addAttribute("counts", support.counts());
        model.addAttribute("statuses", SupportService.STATUSES);
        model.addAttribute("categories", SupportService.CATEGORIES);
        model.addAttribute("status", status);
        model.addAttribute("category", category);
        model.addAttribute("assignee", assignee);
        model.addAttribute("q", q);
        model.addAttribute("active", "support");
        return "admin/support/index";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        model.addAttribute("t", support.ticket(id));
        model.addAttribute("messages", support.thread(id));
        model.addAttribute("staff", support.staff());
        model.addAttribute("statuses", SupportService.STATUSES);
        model.addAttribute("screenshotPhotoId", support.screenshotPhotoId(id).orElse(null));
        model.addAttribute("active", "support");
        return "admin/support/view";
    }

    @PostMapping("/{id}/reply")
    public String reply(@PathVariable UUID id, @RequestParam String message, @RequestParam(required = false) String status,
                        RedirectAttributes ra) {
        return run(id, ra, "Reply sent — the user got a notification.", () -> support.reply(id, message, status));
    }

    @PostMapping("/{id}/note")
    public String note(@PathVariable UUID id, @RequestParam String message, RedirectAttributes ra) {
        return run(id, ra, "Internal note added.", () -> support.note(id, message));
    }

    @PostMapping("/{id}/status")
    public String status(@PathVariable UUID id, @RequestParam String status, @RequestParam(required = false) String reason,
                         RedirectAttributes ra) {
        return run(id, ra, "Status changed.", () -> support.setStatus(id, status, reason));
    }

    @PostMapping("/{id}/assign")
    public String assign(@PathVariable UUID id, @RequestParam(required = false) String assigneeId,
                         @RequestParam(required = false) String reason, RedirectAttributes ra) {
        UUID assignee = assigneeId == null || assigneeId.isBlank() ? null : UUID.fromString(assigneeId);
        return run(id, ra, "Assignee updated.", () -> support.assign(id, assignee, reason));
    }

    /** Private screenshot, streamed through the staff session (never a public URL). */
    @GetMapping("/{id}/screenshot")
    public ResponseEntity<byte[]> screenshot(@PathVariable UUID id) {
        UUID photoId = support.screenshotPhotoId(id).orElse(null);
        if (photoId == null) {
            return ResponseEntity.notFound().build();
        }
        PhotoModeration p = photoModeration.get(photoId);
        if (p.getObjectKey() == null) {
            return ResponseEntity.notFound().build();
        }
        String type = URLConnection.guessContentTypeFromName(p.getObjectKey());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(type != null ? MediaType.parseMediaType(type) : MediaType.APPLICATION_OCTET_STREAM)
                .body(storage.getObject(p.getObjectKey()));
    }

    private String run(UUID id, RedirectAttributes ra, String ok, Runnable action) {
        try {
            action.run();
            ra.addFlashAttribute("successMessage", ok);
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/support/" + id;
    }
}
