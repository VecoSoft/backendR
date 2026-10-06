package com.bdreview.platform.admin.controller;

import com.bdreview.platform.adminconfig.AdminConfigService;
import com.bdreview.platform.adminconfig.HomepageConfig;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.BusinessService;
import com.bdreview.platform.business.CategoryRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.homepage.HomepageService;
import com.bdreview.platform.photomod.PhotoModerationService;
import com.bdreview.platform.photomod.PhotoSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * System → Homepage (V65): hero texts and image (the image goes through photo moderation),
 * featured categories, and Trending / Most loved curation (minimum reviews, excluded listings,
 * up to 3 pins with an end date), with a preview of the resulting sections. ADMIN only.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/homepage")
public class AdminHomepageController {

    private final AdminConfigService adminConfig;
    private final HomepageService homepageService;
    private final BusinessService businessService;
    private final BusinessRepository businessRepository;
    private final CategoryRepository categoryRepository;
    private final PhotoModerationService photoModeration;

    public AdminHomepageController(AdminConfigService adminConfig, HomepageService homepageService,
                                   BusinessService businessService, BusinessRepository businessRepository,
                                   CategoryRepository categoryRepository, PhotoModerationService photoModeration) {
        this.adminConfig = adminConfig;
        this.homepageService = homepageService;
        this.businessService = businessService;
        this.businessRepository = businessRepository;
        this.categoryRepository = categoryRepository;
        this.photoModeration = photoModeration;
    }

    @GetMapping
    public String index(Model model) {
        HomepageConfig config = adminConfig.homepage();
        Set<UUID> referenced = new HashSet<>();
        for (HomepageConfig.Section s : List.of(config.getTrending(), config.getMostLoved())) {
            referenced.addAll(s.getExcludedBusinessIds());
            s.getPins().forEach(p -> referenced.add(p.getBusinessId()));
        }
        model.addAttribute("config", config);
        model.addAttribute("names", businessRepository.findAllById(referenced).stream()
                .collect(Collectors.toMap(Business::getId, Business::getName)));
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("featured", homepageService.featured(config));
        model.addAttribute("preview", homepageService.publicHomepage());
        model.addAttribute("trending", businessService.search(null, null, null, null, null, null, null, null, null, "trending", 0, 10).getContent());
        model.addAttribute("mostLoved", businessService.search(null, null, null, null, null, null, null, null, null, "most_loved", 0, 10).getContent());
        model.addAttribute("pendingHero", photoModeration.queue(com.bdreview.platform.photomod.PhotoStatus.PENDING, PhotoSource.HERO, 0, 5).getContent());
        model.addAttribute("active", "homepage");
        return "admin/homepage/index";
    }

    /** Hero texts, featured categories and both curated sections, in one form. */
    @PostMapping
    public String save(HttpServletRequest request, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            HomepageConfig c = adminConfig.homepage();
            c.setHeroTitle(trimToNull(request.getParameter("heroTitle")));
            c.setHeroSubtitle(trimToNull(request.getParameter("heroSubtitle")));
            if ("on".equals(request.getParameter("removeHeroImage"))) {
                c.setHeroImageUrl(null);
            }
            c.setFeaturedCategoryIds(featuredFrom(request));
            c.setTrending(sectionFrom(request, "trending"));
            c.setMostLoved(sectionFrom(request, "mostLoved"));
            adminConfig.saveHomepage(c, reason);
            ra.addFlashAttribute("successMessage", "Homepage saved — the app shows it on the next page view.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/homepage";
    }

    @PostMapping("/hero-image")
    public String uploadHero(@RequestParam("image") MultipartFile image, @RequestParam(required = false) String reason,
                             RedirectAttributes ra) {
        try {
            boolean live = homepageService.uploadHero(image.getOriginalFilename(), image.getBytes(), reason);
            ra.addFlashAttribute("successMessage", live ? "Hero image updated."
                    : "Hero image uploaded — it goes live once approved in Moderation → Photos.");
        } catch (IOException e) {
            ra.addFlashAttribute("errorMessage", "Couldn't read the uploaded file.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/homepage";
    }

    // ----------------------------------------------------------------

    /** featured=<categoryId> checkboxes + pos_<categoryId> numbers → ordered ids. */
    private static List<UUID> featuredFrom(HttpServletRequest request) {
        String[] ids = request.getParameterValues("featured");
        if (ids == null) {
            return new ArrayList<>();
        }
        record Entry(UUID id, int pos) {
        }
        List<Entry> entries = new ArrayList<>();
        for (String id : ids) {
            String pos = request.getParameter("pos_" + id);
            int p;
            try {
                p = pos == null || pos.isBlank() ? 999 : Integer.parseInt(pos.trim());
            } catch (NumberFormatException e) {
                p = 999;
            }
            entries.add(new Entry(UUID.fromString(id), p));
        }
        entries.sort(Comparator.comparingInt(Entry::pos));
        return entries.stream().map(Entry::id).collect(Collectors.toCollection(ArrayList::new));
    }

    private HomepageConfig.Section sectionFrom(HttpServletRequest request, String prefix) {
        HomepageConfig.Section s = new HomepageConfig.Section();
        String min = request.getParameter(prefix + ".minReviewCount");
        try {
            s.setMinReviewCount(min == null || min.isBlank() ? 0 : Integer.parseInt(min.trim()));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Minimum review count must be a number.");
        }
        String excluded = Optional.ofNullable(request.getParameter(prefix + ".excluded")).orElse("");
        for (String token : excluded.split("[\\s,]+")) {
            if (!token.isBlank()) {
                s.getExcludedBusinessIds().add(resolveBusiness(token));
            }
        }
        for (int i = 0; i < HomepageConfig.MAX_PINS; i++) {
            String business = request.getParameter(prefix + ".pin" + i);
            String until = request.getParameter(prefix + ".pinUntil" + i);
            if (business == null || business.isBlank()) {
                continue;
            }
            if (until == null || until.isBlank()) {
                throw new BadRequestException("Every pin needs an end date.");
            }
            s.getPins().add(new HomepageConfig.Pin(resolveBusiness(business),
                    LocalDate.parse(until).plusDays(1).atStartOfDay(com.bdreview.platform.accountcontrol.AccountControlService.DISPLAY_ZONE).toInstant()));
        }
        return s;
    }

    /** Accepts a business id or slug (what admins copy from a URL). */
    private UUID resolveBusiness(String token) {
        String t = token.trim();
        if (t.contains("/business/")) {
            t = t.substring(t.lastIndexOf("/business/") + "/business/".length()).replaceAll("[/?#].*$", "");
        }
        try {
            UUID id = UUID.fromString(t);
            if (businessRepository.existsById(id)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // treat as slug
        }
        String slug = t;
        return businessRepository.findBySlugAndDeletedAtIsNull(slug).map(Business::getId)
                .orElseThrow(() -> new BadRequestException("No live business \"" + slug + "\"."));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
