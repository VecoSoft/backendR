package com.bdreview.platform.business;

import com.bdreview.platform.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    private final BusinessService businessService;

    private final com.bdreview.platform.listing.DuplicateService duplicateService;

    public BusinessController(BusinessService businessService,
                              com.bdreview.platform.listing.DuplicateService duplicateService) {
        this.duplicateService = duplicateService;
        this.businessService = businessService;
    }

    @PostMapping
    public ResponseEntity<BusinessResponse> create(@Valid @RequestBody CreateBusinessRequest request) {
        return ResponseEntity.ok(businessService.create(CurrentUser.id(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BusinessResponse> update(@PathVariable UUID id,
                                                   @Valid @RequestBody UpdateBusinessRequest request) {
        return ResponseEntity.ok(businessService.update(CurrentUser.id(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        businessService.softDelete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** Report workflow: owner's "next step" after a flag — notifies every admin, doesn't clear it. */
    @PostMapping("/{id}/flag/request-review")
    public ResponseEntity<Void> requestFlagReview(@PathVariable UUID id) {
        businessService.requestFlagReview(CurrentUser.id(), id);
        return ResponseEntity.accepted().build();
    }

    /** Business-card reaction (Like/Dislike/Love/Wow) — toggles on repeat calls with the same type. */
    @PostMapping("/{id}/react")
    public ResponseEntity<Void> react(@PathVariable UUID id, @Valid @RequestBody BusinessReactionRequest request) {
        businessService.react(CurrentUser.id(), id, request.reactionType());
        return ResponseEntity.accepted().build();
    }

    /** Owner-only: full-replace the structured weekly hours used for "open now" (see OperatingHoursEntry). */
    @PutMapping("/{id}/hours")
    public ResponseEntity<List<OperatingHoursEntry>> replaceHours(@PathVariable UUID id,
                                                                   @Valid @RequestBody ReplaceOperatingHoursRequest request) {
        return ResponseEntity.ok(businessService.replaceOperatingHours(CurrentUser.id(), id, request));
    }

    /** Owner-only: holiday / special-hours overrides — per-item CRUD (see HoursExceptionEntry). */
    @PostMapping("/{id}/hours-exceptions")
    public ResponseEntity<HoursExceptionEntry> addHoursException(@PathVariable UUID id,
                                                                  @Valid @RequestBody HoursExceptionRequest request) {
        return ResponseEntity.ok(businessService.addHoursException(CurrentUser.id(), id, request));
    }

    @PutMapping("/{id}/hours-exceptions/{exceptionId}")
    public ResponseEntity<HoursExceptionEntry> updateHoursException(@PathVariable UUID id, @PathVariable UUID exceptionId,
                                                                     @Valid @RequestBody HoursExceptionRequest request) {
        return ResponseEntity.ok(businessService.updateHoursException(CurrentUser.id(), id, exceptionId, request));
    }

    @DeleteMapping("/{id}/hours-exceptions/{exceptionId}")
    public ResponseEntity<Void> deleteHoursException(@PathVariable UUID id, @PathVariable UUID exceptionId) {
        businessService.deleteHoursException(CurrentUser.id(), id, exceptionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * V65: a slug that belonged to a listing merged into another one answers 301 to the kept
     * listing's URL (fetch follows it; the app then swaps the address bar to the new slug).
     */
    @GetMapping("/{slug}")
    public ResponseEntity<BusinessResponse> getBySlug(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(businessService.getBySlug(slug));
        } catch (com.bdreview.platform.common.ResourceNotFoundException notFound) {
            var target = duplicateService.redirectTarget(slug);
            if (target.isEmpty()) {
                throw notFound;
            }
            return ResponseEntity.status(org.springframework.http.HttpStatus.MOVED_PERMANENTLY)
                    .location(java.net.URI.create("/api/v1/businesses/" + target.get())).build();
        }
    }

    @GetMapping("/mine")
    public ResponseEntity<List<BusinessResponse>> mine() {
        return ResponseEntity.ok(businessService.myBusinesses(CurrentUser.id()));
    }

    /** §2: free-text pre-check before "add a business" — e.g. "Biriyani House Mirpur" or just "KFC". */
    @GetMapping("/potential-duplicates")
    public ResponseEntity<List<BusinessResponse>> potentialDuplicates(@RequestParam String q) {
        return ResponseEntity.ok(businessService.searchForClaim(q));
    }

    /** §3 Search + Filter (GPS-enabled). All filters are optional/combinable. `q`/`location` are the free-text search-bar fields. */
    @GetMapping("/search")
    public ResponseEntity<com.bdreview.platform.common.PageResponse<BusinessResponse>> search(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String priceTier,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusMeters,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String location,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var results = businessService.search(categoryId, areaId, priceTier, minRating, lat, lng, radiusMeters,
                q, location, sort, page, size);
        return ResponseEntity.ok(com.bdreview.platform.common.PageResponse.of(results));
    }
}