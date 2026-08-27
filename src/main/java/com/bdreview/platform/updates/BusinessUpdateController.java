package com.bdreview.platform.updates;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.updates.BusinessUpdateRequests.PublishRequest;
import com.bdreview.platform.updates.BusinessUpdateRequests.UpsertRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Phase 3 — business updates. {@code GET /updates} is public (published only);
 * {@code GET /updates/manage} and all writes require the listing's owner.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}/updates")
public class BusinessUpdateController {

    private final BusinessUpdateService service;

    public BusinessUpdateController(BusinessUpdateService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<BusinessUpdate> publicList(@PathVariable UUID businessId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        return PageResponse.of(service.publicList(businessId, page, size));
    }

    @GetMapping("/manage")
    public PageResponse<BusinessUpdate> manageList(@PathVariable UUID businessId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.manageList(CurrentUser.id(), businessId, page, size));
    }

    @PostMapping
    public BusinessUpdate create(@PathVariable UUID businessId, @Valid @RequestBody UpsertRequest req) {
        return service.create(CurrentUser.id(), businessId, req);
    }

    @PutMapping("/{id}")
    public BusinessUpdate update(@PathVariable UUID businessId, @PathVariable UUID id,
                                @Valid @RequestBody UpsertRequest req) {
        return service.update(CurrentUser.id(), businessId, id, req);
    }

    @PatchMapping("/{id}/publish")
    public BusinessUpdate setPublished(@PathVariable UUID businessId, @PathVariable UUID id,
                                       @Valid @RequestBody PublishRequest req) {
        return service.setPublished(CurrentUser.id(), businessId, id, req.published());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID businessId, @PathVariable UUID id) {
        service.delete(CurrentUser.id(), businessId, id);
        return ResponseEntity.noContent().build();
    }
}
