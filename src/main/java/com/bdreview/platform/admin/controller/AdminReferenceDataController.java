package com.bdreview.platform.admin.controller;

import com.bdreview.platform.admin.form.ReferenceItemForm;
import com.bdreview.platform.business.*;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/** Categories, cities, areas and business attributes are small fixed lookup tables — one page, four tabs. */
@Controller
@RequestMapping("/admin/reference-data")
public class AdminReferenceDataController {

    private final CategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final BusinessAttributeRepository attributeRepository;

    public AdminReferenceDataController(CategoryRepository categoryRepository,
                                         CityRepository cityRepository,
                                         AreaRepository areaRepository,
                                         BusinessAttributeRepository attributeRepository) {
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
        this.attributeRepository = attributeRepository;
    }

    @GetMapping
    public String index(@RequestParam(defaultValue = "categories") String tab, Model model) {
        model.addAttribute("tab", tab);
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("cities", cityRepository.findAll(Sort.by("name")));
        model.addAttribute("areas", areaRepository.findAllWithCity());
        model.addAttribute("attributes", attributeRepository.findAll(Sort.by("name")));
        model.addAttribute("categoryKinds", com.bdreview.platform.business.CategoryKind.values());
        model.addAttribute("referenceItemForm", new ReferenceItemForm());
        model.addAttribute("active", "reference-data");
        return "admin/reference/index";
    }

    @PostMapping("/categories/new")
    public String addCategory(@ModelAttribute ReferenceItemForm form, RedirectAttributes redirectAttributes) {
        try {
            categoryRepository.save(Category.builder()
                    .name(form.getName())
                    .kind(form.getKind() != null ? form.getKind() : com.bdreview.platform.business.CategoryKind.GENERAL)
                    .build());
            redirectAttributes.addFlashAttribute("successMessage", "Category added.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "That category already exists.");
        }
        return "redirect:/admin/reference-data?tab=categories";
    }

    /** Edit an existing category's name and/or kind (spec Phase 2: admin must be able to correct the kind). */
    @PostMapping("/categories/{id}/update")
    public String updateCategory(@PathVariable UUID id, @ModelAttribute ReferenceItemForm form,
                                 RedirectAttributes redirectAttributes) {
        try {
            Category category = categoryRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
            if (form.getName() != null && !form.getName().isBlank()) {
                category.setName(form.getName().trim());
            }
            if (form.getKind() != null) {
                category.setKind(form.getKind());
            }
            categoryRepository.save(category);
            redirectAttributes.addFlashAttribute("successMessage", "Category updated.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Another category already has that name.");
        } catch (ResourceNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/reference-data?tab=categories";
    }

    @PostMapping("/categories/{id}/delete")
    public String deleteCategory(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            categoryRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Category removed.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Category is still in use by one or more listings.");
        }
        return "redirect:/admin/reference-data?tab=categories";
    }

    @PostMapping("/cities/new")
    public String addCity(@ModelAttribute ReferenceItemForm form, RedirectAttributes redirectAttributes) {
        try {
            cityRepository.save(City.builder().name(form.getName()).build());
            redirectAttributes.addFlashAttribute("successMessage", "City added.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "That city already exists.");
        }
        return "redirect:/admin/reference-data?tab=cities";
    }

    @PostMapping("/cities/{id}/delete")
    public String deleteCity(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            cityRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "City removed.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "City is still in use by one or more areas/listings.");
        }
        return "redirect:/admin/reference-data?tab=cities";
    }

    @PostMapping("/areas/new")
    public String addArea(@ModelAttribute ReferenceItemForm form, RedirectAttributes redirectAttributes) {
        try {
            City city = cityRepository.findById(form.getCityId())
                    .orElseThrow(() -> new ResourceNotFoundException("City not found"));
            areaRepository.save(Area.builder().city(city).name(form.getName()).build());
            redirectAttributes.addFlashAttribute("successMessage", "Area added.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not add area: " + ex.getMessage());
        }
        return "redirect:/admin/reference-data?tab=areas";
    }

    @PostMapping("/areas/{id}/delete")
    public String deleteArea(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            areaRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Area removed.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Area is still in use by one or more listings.");
        }
        return "redirect:/admin/reference-data?tab=areas";
    }

    @PostMapping("/attributes/new")
    public String addAttribute(@ModelAttribute ReferenceItemForm form, RedirectAttributes redirectAttributes) {
        try {
            attributeRepository.save(BusinessAttribute.builder().name(form.getName()).build());
            redirectAttributes.addFlashAttribute("successMessage", "Attribute added.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "That attribute already exists.");
        }
        return "redirect:/admin/reference-data?tab=attributes";
    }

    @PostMapping("/attributes/{id}/delete")
    public String deleteAttribute(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            attributeRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Attribute removed.");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Attribute is still assigned to one or more listings.");
        }
        return "redirect:/admin/reference-data?tab=attributes";
    }
}
