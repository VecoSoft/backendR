package com.bdreview.platform.admin.controller;

import com.bdreview.platform.admin.form.BusinessForm;
import com.bdreview.platform.admin.service.AdminBusinessService;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.business.*;
import com.bdreview.platform.gallery.BusinessPhotoRepository;
import com.bdreview.platform.review.ReviewRepository;
import com.bdreview.platform.summary.BusinessReviewSummaryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/admin/businesses")
public class AdminBusinessController {

    private final AdminBusinessService adminBusinessService;
    private final CategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final BusinessAttributeRepository attributeRepository;
    private final BrandRepository brandRepository;
    private final ReviewRepository reviewRepository;
    private final BusinessPhotoRepository businessPhotoRepository;
    private final BusinessReviewSummaryRepository businessReviewSummaryRepository;

    public AdminBusinessController(AdminBusinessService adminBusinessService,
                                    CategoryRepository categoryRepository,
                                    CityRepository cityRepository,
                                    AreaRepository areaRepository,
                                    BusinessAttributeRepository attributeRepository,
                                    BrandRepository brandRepository,
                                    ReviewRepository reviewRepository,
                                    BusinessPhotoRepository businessPhotoRepository,
                                    BusinessReviewSummaryRepository businessReviewSummaryRepository) {
        this.adminBusinessService = adminBusinessService;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
        this.attributeRepository = attributeRepository;
        this.brandRepository = brandRepository;
        this.reviewRepository = reviewRepository;
        this.businessPhotoRepository = businessPhotoRepository;
        this.businessReviewSummaryRepository = businessReviewSummaryRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String query,
                        @RequestParam(required = false) UUID categoryId,
                        @RequestParam(required = false) UUID cityId,
                        @RequestParam(defaultValue = "false") boolean includeDeleted,
                        @RequestParam(defaultValue = "false") boolean unclaimedOnly,
                        @RequestParam(required = false) Integer page,
                        Model model) {
        var results = adminBusinessService.search(query, categoryId, cityId, includeDeleted, unclaimedOnly,
                AdminSupport.pageOrDefault(page));
        model.addAttribute("results", results);
        model.addAttribute("unclaimedOwnerIds", adminBusinessService.unclaimedOwnerIds(results.getContent()));
        model.addAttribute("query", query);
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("cityId", cityId);
        model.addAttribute("includeDeleted", includeDeleted);
        model.addAttribute("unclaimedOnly", unclaimedOnly);
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("cities", cityRepository.findAll(Sort.by("name")));
        model.addAttribute("active", "businesses");
        return "admin/businesses/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        Business business = adminBusinessService.get(id);
        model.addAttribute("business", business);
        model.addAttribute("reviews",
                reviewRepository.findByBusinessIdAndDeletedAtIsNull(id, PageRequest.of(0, 10)));
        model.addAttribute("photos", businessPhotoRepository.findByBusinessIdOrderBySortOrderAsc(id));
        model.addAttribute("summary", businessReviewSummaryRepository.findByBusinessId(id).orElse(null));
        model.addAttribute("active", "businesses");
        return "admin/businesses/view";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("businessForm", new BusinessForm());
        addReferenceData(model);
        model.addAttribute("active", "businesses");
        return "admin/businesses/form";
    }

    @PostMapping("/new")
    public String create(@ModelAttribute("businessForm") BusinessForm form, Model model,
                          Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            Business created = adminBusinessService.create(AdminSupport.currentAdminId(authentication), form);
            redirectAttributes.addFlashAttribute("successMessage", "Listing created.");
            return "redirect:/admin/businesses/" + created.getId();
        } catch (RuntimeException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            addReferenceData(model);
            model.addAttribute("active", "businesses");
            return "admin/businesses/form";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("businessForm", BusinessForm.from(adminBusinessService.get(id)));
        addReferenceData(model);
        model.addAttribute("active", "businesses");
        return "admin/businesses/edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id, @ModelAttribute("businessForm") BusinessForm form, Model model,
                          RedirectAttributes redirectAttributes) {
        try {
            adminBusinessService.update(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Listing updated.");
            return "redirect:/admin/businesses/" + id;
        } catch (RuntimeException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            addReferenceData(model);
            model.addAttribute("active", "businesses");
            return "admin/businesses/edit";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        adminBusinessService.softDelete(id);
        redirectAttributes.addFlashAttribute("successMessage", "Listing removed (soft-deleted).");
        return "redirect:/admin/businesses/" + id;
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        adminBusinessService.restore(id);
        redirectAttributes.addFlashAttribute("successMessage", "Listing restored.");
        return "redirect:/admin/businesses/" + id;
    }

    @PostMapping("/{id}/verify")
    public String verify(@PathVariable UUID id, @RequestParam boolean verified, RedirectAttributes redirectAttributes) {
        adminBusinessService.setVerified(id, verified);
        redirectAttributes.addFlashAttribute("successMessage", verified ? "Marked verified." : "Verification removed.");
        return "redirect:/admin/businesses/" + id;
    }

    @PostMapping("/{id}/unflag")
    public String unflag(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        adminBusinessService.unflag(id);
        redirectAttributes.addFlashAttribute("successMessage", "Flag cleared.");
        return "redirect:/admin/businesses/" + id;
    }

    private void addReferenceData(Model model) {
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("cities", cityRepository.findAll(Sort.by("name")));
        model.addAttribute("areas", areaRepository.findAllWithCity());
        model.addAttribute("attributes", attributeRepository.findAll(Sort.by("name")));
        model.addAttribute("brands", brandRepository.findByDeletedAtIsNull(Sort.by("name")));
        model.addAttribute("priceTiers", PriceTier.values());
    }
}
