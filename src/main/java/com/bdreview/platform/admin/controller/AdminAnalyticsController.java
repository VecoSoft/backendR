package com.bdreview.platform.admin.controller;

import com.bdreview.platform.admin.service.AdminAnalyticsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** System → Analytics (V67): 7/30/90-day charts and totals, search insights, moderation speed, CSV export. */
@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_ANALYTICS')")
@RequestMapping("/admin/analytics")
public class AdminAnalyticsController {

    private final AdminAnalyticsService analytics;

    public AdminAnalyticsController(AdminAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public String index(@RequestParam(required = false) Integer days, Model model) {
        int d = AdminAnalyticsService.clampDays(days);
        List<Map<String, Object>> daily = analytics.daily(d);
        model.addAttribute("days", d);
        model.addAttribute("ranges", AdminAnalyticsService.RANGES);
        model.addAttribute("series", AdminAnalyticsService.SERIES);
        model.addAttribute("daily", daily);
        model.addAttribute("totals", analytics.totals(daily));
        model.addAttribute("topQueries", analytics.topQueries(d, 25));
        model.addAttribute("zeroResults", analytics.zeroResultQueries(d, 25));
        model.addAttribute("reportsByReason", analytics.reportsByReason(d));
        model.addAttribute("times", analytics.moderationTimes(d));
        model.addAttribute("active", "analytics");
        return "admin/analytics/index";
    }

    /** daily | top-queries | zero-results | reports-by-reason */
    @GetMapping("/export/{table}.csv")
    public ResponseEntity<byte[]> export(@PathVariable String table, @RequestParam(required = false) Integer days) {
        int d = AdminAnalyticsService.clampDays(days);
        List<Map<String, Object>> rows = switch (table) {
            case "daily" -> analytics.daily(d);
            case "top-queries" -> analytics.topQueries(d, 1000);
            case "zero-results" -> analytics.zeroResultQueries(d, 1000);
            case "reports-by-reason" -> analytics.reportsByReason(d);
            default -> throw new ResponseStatusException(NOT_FOUND);
        };
        byte[] body = ("﻿" + analytics.csv(rows)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + table + "-" + d + "d.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
