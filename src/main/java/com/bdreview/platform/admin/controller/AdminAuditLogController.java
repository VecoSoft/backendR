package com.bdreview.platform.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.moderation.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/audit-log")
public class AdminAuditLogController {

    private final AuditLogRepository auditLogRepository;

    public AdminAuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Integer page, Model model) {
        var results = auditLogRepository.findAll(
                PageRequest.of(AdminSupport.pageOrDefault(page), 25, Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("results", results);
        model.addAttribute("active", "audit");
        return "admin/audit/list";
    }
}
