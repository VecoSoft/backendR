package com.bdreview.platform.admin.controller;

import com.bdreview.platform.messaging.ChatModerationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Moderation → Chat reports (V67). Only conversations a participant reported are listed, and a
 * conversation can only be opened through its report — there is no route by thread id.
 */
@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_CONTENT')")
@RequestMapping("/admin/chat-reports")
public class AdminChatReportController {

    private final ChatModerationService chat;

    public AdminChatReportController(ChatModerationService chat) {
        this.chat = chat;
    }

    @GetMapping
    public String queue(@RequestParam(required = false) String status, Model model) {
        String s = status == null || status.isBlank() ? "OPEN" : status;
        model.addAttribute("reports", chat.queue(s));
        model.addAttribute("status", s);
        model.addAttribute("openCount", chat.openCount());
        model.addAttribute("active", "chat-reports");
        return "admin/chat/index";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var r = chat.report(id);
        model.addAttribute("r", r);
        model.addAttribute("messages", chat.reportedThread(id));
        model.addAttribute("blocks", chat.blocksFor((UUID) r.get("reported_user_id")));
        model.addAttribute("blockDays", ChatModerationService.BLOCK_DAYS);
        model.addAttribute("active", "chat-reports");
        return "admin/chat/view";
    }

    @PostMapping("/{id}/action")
    public String action(@PathVariable UUID id, @RequestParam String action, @RequestParam(required = false) Integer days,
                         @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            switch (action) {
                case "warn" -> chat.warn(id, reason);
                case "block" -> chat.block(id, days == null ? 0 : days, reason);
                case "dismiss" -> chat.dismiss(id, reason);
                default -> throw new IllegalArgumentException("Unknown action");
            }
            ra.addFlashAttribute("successMessage", switch (action) {
                case "warn" -> "Warning sent.";
                case "block" -> "Sender blocked from messaging for " + days + " day(s).";
                default -> "Report dismissed.";
            });
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/chat-reports/" + id;
    }

    @PostMapping("/{id}/blocks/{blockId}/lift")
    public String lift(@PathVariable UUID id, @PathVariable UUID blockId, @RequestParam(required = false) String reason,
                       RedirectAttributes ra) {
        try {
            chat.liftBlock(blockId, reason);
            ra.addFlashAttribute("successMessage", "Messaging block lifted.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/chat-reports/" + id;
    }
}
