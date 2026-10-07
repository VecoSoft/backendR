package com.bdreview.platform.admin.controller;

import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.Category;
import com.bdreview.platform.business.CategoryRepository;
import com.bdreview.platform.business.City;
import com.bdreview.platform.business.CityRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.listing.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Catalog → listing integrity (V65): verification queue, protected-edit approvals, duplicate
 * finder/merge and data checks. ADMIN only; every action needs a reason and is audited.
 */
@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_CATALOG')")
@RequestMapping("/admin")
public class AdminListingController {

    private final VerificationService verification;
    private final ProtectedEditService protectedEdits;
    private final DuplicateService duplicates;
    private final DataCheckService dataChecks;
    private final BusinessRepository businessRepository;
    private final CategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final com.bdreview.platform.gallery.ObjectStorageClient storage;

    public AdminListingController(VerificationService verification, ProtectedEditService protectedEdits,
                                  DuplicateService duplicates, DataCheckService dataChecks,
                                  BusinessRepository businessRepository, CategoryRepository categoryRepository,
                                  CityRepository cityRepository, AreaRepository areaRepository,
                                  com.bdreview.platform.gallery.ObjectStorageClient storage) {
        this.storage = storage;
        this.verification = verification;
        this.protectedEdits = protectedEdits;
        this.duplicates = duplicates;
        this.dataChecks = dataChecks;
        this.businessRepository = businessRepository;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
    }

    // ---------------------------------------------------------------- verification

    @GetMapping("/verification")
    public String verificationQueue(@RequestParam(required = false) BusinessVerificationRequest.Status status,
                                    @RequestParam(required = false) Integer page, Model model) {
        var results = verification.queue(status, AdminSupport.pageOrDefault(page));
        model.addAttribute("results", results);
        model.addAttribute("businesses", businessesById(results.getContent().stream()
                .map(BusinessVerificationRequest::getBusinessId).collect(Collectors.toSet())));
        model.addAttribute("status", status == null ? BusinessVerificationRequest.Status.PENDING : status);
        model.addAttribute("statuses", BusinessVerificationRequest.Status.values());
        model.addAttribute("active", "verification");
        return "admin/listing/verification";
    }

    @PostMapping("/verification/{id}/{action}")
    public String decideVerification(@PathVariable UUID id, @PathVariable String action,
                                     @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/verification", () -> {
            switch (action) {
                case "approve" -> verification.approve(id, reason);
                case "reject" -> verification.reject(id, reason);
                default -> throw new BadRequestException("Unknown action.");
            }
            return "Verification request " + ("approve".equals(action) ? "approved — the business is now verified." : "rejected.");
        });
    }

    /** Streams a verification document through the admin session (the storage URL is JWT-admin-only). */
    @GetMapping("/verification/{id}/document")
    public org.springframework.http.ResponseEntity<byte[]> verificationDocument(@PathVariable UUID id) {
        BusinessVerificationRequest r = verification.get(id);
        if (r.getDocumentRef() == null) {
            throw new com.bdreview.platform.common.ResourceNotFoundException("No document");
        }
        String key = r.getDocumentRef().contains("/api/v1/storage/files/")
                ? com.bdreview.platform.photomod.PhotoModerationService.objectKeyOf(r.getDocumentRef()) : r.getDocumentRef();
        String type = java.net.URLConnection.guessContentTypeFromName(key);
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(type != null ? org.springframework.http.MediaType.parseMediaType(type)
                        : org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(storage.getObject(key));
    }

    /** Verify / revoke straight from the business page. */
    @PostMapping("/verification/business/{businessId}/{action}")
    public String verifyBusiness(@PathVariable UUID businessId, @PathVariable String action,
                                 @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/businesses/" + businessId + "#verification", () -> {
            switch (action) {
                case "verify" -> verification.verifyManually(businessId, reason);
                case "revoke" -> verification.revoke(businessId, reason);
                default -> throw new BadRequestException("Unknown action.");
            }
            return "verify".equals(action) ? "Business verified." : "Verification revoked.";
        });
    }

    // ---------------------------------------------------------------- protected edits

    @GetMapping("/pending-changes")
    public String pendingChanges(@RequestParam(required = false) BusinessPendingChange.Status status,
                                 @RequestParam(required = false) Integer page, Model model) {
        var results = protectedEdits.queue(status, AdminSupport.pageOrDefault(page));
        model.addAttribute("results", results);
        model.addAttribute("businesses", businessesById(results.getContent().stream()
                .map(BusinessPendingChange::getBusinessId).collect(Collectors.toSet())));
        Map<String, String> labels = referenceLabels(results.getContent());
        Map<UUID, List<FieldDiff>> diffs = new HashMap<>();
        for (BusinessPendingChange c : results.getContent()) {
            diffs.put(c.getId(), diff(c, labels));
        }
        model.addAttribute("diffs", diffs);
        model.addAttribute("status", status == null ? BusinessPendingChange.Status.PENDING : status);
        model.addAttribute("statuses", BusinessPendingChange.Status.values());
        model.addAttribute("active", "pending-changes");
        return "admin/listing/pending-changes";
    }

    @PostMapping("/pending-changes/{id}/{action}")
    public String decideChange(@PathVariable UUID id, @PathVariable String action,
                               @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/pending-changes", () -> {
            switch (action) {
                case "approve" -> protectedEdits.approve(id, reason);
                case "reject" -> protectedEdits.reject(id, reason);
                default -> throw new BadRequestException("Unknown action.");
            }
            return "approve".equals(action) ? "Change approved — the listing shows the new values now." : "Change rejected and discarded.";
        });
    }

    // ---------------------------------------------------------------- duplicates

    @GetMapping("/duplicates")
    public String duplicates(Model model) {
        model.addAttribute("pairs", duplicates.candidates(100));
        model.addAttribute("maxDistance", DuplicateService.MAX_DISTANCE_M);
        model.addAttribute("minSimilarity", DuplicateService.MIN_NAME_SIMILARITY);
        model.addAttribute("active", "duplicates");
        return "admin/listing/duplicates";
    }

    @PostMapping("/duplicates/merge")
    public String merge(@RequestParam UUID keepId, @RequestParam UUID removeId,
                        @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/duplicates", () -> {
            Map<String, Object> moved = duplicates.merge(keepId, removeId, reason);
            return "Merged. Moved: " + moved.entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                    .collect(Collectors.joining(", ")) + ". The old link now redirects.";
        });
    }

    @PostMapping("/duplicates/dismiss")
    public String dismiss(@RequestParam UUID a, @RequestParam UUID b, @RequestParam(required = false) String reason,
                          RedirectAttributes ra) {
        return act(ra, "/admin/duplicates", () -> {
            duplicates.dismiss(a, b, reason);
            return "Marked as not a duplicate.";
        });
    }

    // ---------------------------------------------------------------- data checks

    @GetMapping("/data-checks")
    public String dataChecks(@RequestParam(required = false) DataCheckService.Check check, Model model) {
        DataCheckService.Check shown = check == null ? DataCheckService.Check.AREA_CITY_MISMATCH : check;
        model.addAttribute("counts", dataChecks.counts());
        model.addAttribute("check", shown);
        model.addAttribute("rows", dataChecks.rows(shown, 200));
        model.addAttribute("checks", DataCheckService.Check.values());
        model.addAttribute("active", "data-checks");
        return "admin/listing/data-checks";
    }

    @PostMapping("/data-checks/delete")
    public String bulkDelete(@RequestParam(required = false) List<UUID> ids, @RequestParam(required = false) String check,
                             @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/data-checks" + (check == null ? "" : "?check=" + check), () ->
                dataChecks.softDelete(ids, reason) + " listing(s) soft-deleted.");
    }

    // ----------------------------------------------------------------

    private Map<UUID, Business> businessesById(Set<UUID> ids) {
        return businessRepository.findAllById(ids).stream().collect(Collectors.toMap(Business::getId, Function.identity()));
    }

    /** One row of a pending-change diff, already null-safe for the template. */
    public record FieldDiff(String field, String before, String after, boolean changed) {
    }

    private static final Map<String, String> FIELD_LABELS = Map.of(
            "name", "Name", "contactNumber", "Phone", "categoryId", "Category", "cityId", "City",
            "areaId", "Area", "latitude", "Latitude", "longitude", "Longitude");

    /** Field-by-field before/after (ids shown as names, missing values as "—"). */
    static List<FieldDiff> diff(BusinessPendingChange c, Map<String, String> labels) {
        Map<String, Object> before = c.getBeforeJson() == null ? Map.of() : c.getBeforeJson();
        Map<String, Object> after = c.getAfterJson() == null ? Map.of() : c.getAfterJson();
        List<FieldDiff> rows = new ArrayList<>();
        for (String field : ProtectedEditService.FIELDS) {
            String b = before.get(field) == null ? null : before.get(field).toString();
            String a = after.get(field) == null ? null : after.get(field).toString();
            rows.add(new FieldDiff(FIELD_LABELS.getOrDefault(field, field), display(b, labels), display(a, labels), !Objects.equals(b, a)));
        }
        return rows;
    }

    private static String display(String value, Map<String, String> labels) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return labels.getOrDefault(value, value);
    }

    /** id → display name for the category/city/area ids inside pending-change diffs. */
    private Map<String, String> referenceLabels(List<BusinessPendingChange> changes) {
        Set<UUID> ids = new HashSet<>();
        for (BusinessPendingChange c : changes) {
            for (Map<String, Object> m : Arrays.asList(c.getBeforeJson(), c.getAfterJson())) {
                if (m == null) {
                    continue;
                }
                for (String key : List.of("categoryId", "cityId", "areaId")) {
                    Object v = m.get(key);
                    if (v != null) {
                        try {
                            ids.add(UUID.fromString(v.toString()));
                        } catch (IllegalArgumentException ignored) {
                            // not an id — shown raw
                        }
                    }
                }
            }
        }
        Map<String, String> labels = new HashMap<>();
        categoryRepository.findAllById(ids).forEach((Category c) -> labels.put(c.getId().toString(), c.getName()));
        cityRepository.findAllById(ids).forEach((City c) -> labels.put(c.getId().toString(), c.getName()));
        areaRepository.findAllById(ids).forEach((Area a) -> labels.put(a.getId().toString(), a.getName()));
        return labels;
    }

    private static String act(RedirectAttributes ra, String back, Supplier<String> action) {
        try {
            ra.addFlashAttribute("successMessage", action.get());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + back;
    }
}
