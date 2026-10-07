package com.bdreview.platform.admin.controller;

import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.CategoryRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.notification.BroadcastService;
import com.bdreview.platform.notification.NotificationTemplateService;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * System → Notifications (V67): broadcasts (preview, send now or schedule, cancel, history with
 * delivered/read counts) and the editable notification templates (en + bn). SUPER_ADMIN.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/notifications")
public class AdminNotificationsController {

    private final BroadcastService broadcasts;
    private final NotificationTemplateService templates;
    private final AreaRepository areaRepository;
    private final CategoryRepository categoryRepository;

    public AdminNotificationsController(BroadcastService broadcasts, NotificationTemplateService templates,
                                        AreaRepository areaRepository, CategoryRepository categoryRepository) {
        this.broadcasts = broadcasts;
        this.templates = templates;
        this.areaRepository = areaRepository;
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("history", broadcasts.history(100));
        model.addAttribute("audiences", BroadcastService.Audience.values());
        model.addAttribute("areas", areaRepository.findAllWithCity());
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("smsAvailable", broadcasts.smsAvailable());
        model.addAttribute("active", "notifications");
        return "admin/notifications/index";
    }

    /** Audience size for the preview box (JSON, same session). */
    @GetMapping("/audience-size")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> audienceSize(@RequestParam BroadcastService.Audience audience,
                                                            @RequestParam(required = false) UUID areaId,
                                                            @RequestParam(required = false) UUID categoryId) {
        return ResponseEntity.ok(Map.of("recipients", broadcasts.audienceSize(audience, areaId, categoryId)));
    }

    @PostMapping("/broadcasts")
    public String create(@RequestParam String title, @RequestParam String body, @RequestParam BroadcastService.Audience audience,
                         @RequestParam(required = false) UUID areaId, @RequestParam(required = false) UUID categoryId,
                         @RequestParam(defaultValue = "false") boolean sendSms,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime scheduledAt,
                         @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            var when = scheduledAt == null ? null : scheduledAt.atZone(com.bdreview.platform.accountcontrol.AccountControlService.DISPLAY_ZONE).toInstant();
            broadcasts.create(new BroadcastService.Draft(title, body, audience, areaId, categoryId, sendSms, when), reason);
            ra.addFlashAttribute("successMessage", when == null ? "Broadcast sent." : "Broadcast scheduled.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/notifications";
    }

    @PostMapping("/broadcasts/{id}/cancel")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            broadcasts.cancel(id, reason);
            ra.addFlashAttribute("successMessage", "Scheduled broadcast cancelled.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/notifications";
    }

    // ---------------------------------------------------------------- templates

    @GetMapping("/templates")
    public String templates(@RequestParam(required = false) NotificationTemplateService.Key key, Model model) {
        NotificationTemplateService.Key shown = key == null ? NotificationTemplateService.Key.ORDER_STATUS : key;
        model.addAttribute("templates", templates.all());
        model.addAttribute("key", shown);
        model.addAttribute("view", templates.all().stream().filter(v -> v.key() == shown).findFirst().orElseThrow());
        Map<String, String> sample = NotificationTemplateService.sampleVars(shown);
        model.addAttribute("sample", sample);
        model.addAttribute("previewEn", templates.render(shown, "en", sample));
        model.addAttribute("previewBn", templates.render(shown, "bn", sample));
        model.addAttribute("active", "notifications");
        return "admin/notifications/templates";
    }

    @PostMapping("/templates/{key}/{locale}")
    public String saveTemplate(@PathVariable NotificationTemplateService.Key key, @PathVariable String locale,
                               @RequestParam(required = false) String title, @RequestParam(required = false) String body,
                               @RequestParam(required = false) String action, @RequestParam(required = false) String reason,
                               RedirectAttributes ra) {
        try {
            if (!"en".equals(locale) && !"bn".equals(locale)) {
                throw new BadRequestException("Unknown language.");
            }
            if ("reset".equals(action)) {
                templates.reset(key, locale, reason);
                ra.addFlashAttribute("successMessage", "Template reset to the built-in text.");
            } else {
                templates.save(key, locale, title, body, reason);
                ra.addFlashAttribute("successMessage", "Template saved — the next notification uses it.");
            }
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/notifications/templates?key=" + key;
    }
}
