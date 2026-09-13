package com.bdreview.platform.catalog;

import com.bdreview.platform.catalog.CatalogRequests.*;
import com.bdreview.platform.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Phase 2 category showcase modules. GET is public (same as the rest of
 * {@code /api/v1/businesses/**}); every write requires the listing's owner.
 * None of these lists are bundled into GET /businesses/{slug} — the public
 * page fetches the one it needs when its tab is opened.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class CatalogController {

    private final CatalogService catalog;
    private final StaffScheduleService staffSchedule;

    public CatalogController(CatalogService catalog, StaffScheduleService staffSchedule) {
        this.catalog = catalog;
        this.staffSchedule = staffSchedule;
    }

    // ---- Services / gym membership / gym facilities ----------------------

    @GetMapping("/services")
    public List<ServiceOffering> services(@PathVariable UUID businessId,
                                          @RequestParam(required = false) ServiceSection section) {
        return catalog.services(businessId, section);
    }

    @PostMapping("/services")
    public ServiceOffering addService(@PathVariable UUID businessId,
                                      @Valid @RequestBody ServiceOfferingRequest req) {
        return catalog.addService(CurrentUser.id(), businessId, req);
    }

    @PutMapping("/services/{id}")
    public ServiceOffering updateService(@PathVariable UUID businessId, @PathVariable UUID id,
                                         @Valid @RequestBody ServiceOfferingRequest req) {
        return catalog.updateService(CurrentUser.id(), businessId, id, req);
    }

    @DeleteMapping("/services/{id}")
    public ResponseEntity<Void> deleteService(@PathVariable UUID businessId, @PathVariable UUID id) {
        catalog.deleteService(CurrentUser.id(), businessId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/services/reorder")
    public List<ServiceOffering> reorderServices(@PathVariable UUID businessId,
                                                 @RequestParam(required = false) ServiceSection section,
                                                 @Valid @RequestBody ReorderRequest req) {
        return catalog.reorderServices(CurrentUser.id(), businessId, section, req.orderedIds());
    }

    /** Public — active staff qualified to perform this service (booking's "Choose staff" step). */
    @GetMapping("/services/{serviceId}/staff")
    public List<TeamMember> staffForService(@PathVariable UUID businessId, @PathVariable UUID serviceId) {
        return staffSchedule.qualifiedActiveStaff(businessId, serviceId);
    }

    // ---- Team members (doctors / staff / trainers) ----------------------

    @GetMapping("/team")
    public List<TeamMember> team(@PathVariable UUID businessId) {
        return catalog.team(businessId);
    }

    @PostMapping("/team")
    public TeamMember addTeamMember(@PathVariable UUID businessId, @Valid @RequestBody TeamMemberRequest req) {
        return catalog.addTeamMember(CurrentUser.id(), businessId, req);
    }

    @PutMapping("/team/{id}")
    public TeamMember updateTeamMember(@PathVariable UUID businessId, @PathVariable UUID id,
                                       @Valid @RequestBody TeamMemberRequest req) {
        return catalog.updateTeamMember(CurrentUser.id(), businessId, id, req);
    }

    @DeleteMapping("/team/{id}")
    public ResponseEntity<Void> deleteTeamMember(@PathVariable UUID businessId, @PathVariable UUID id) {
        catalog.deleteTeamMember(CurrentUser.id(), businessId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/team/reorder")
    public List<TeamMember> reorderTeam(@PathVariable UUID businessId, @Valid @RequestBody ReorderRequest req) {
        return catalog.reorderTeam(CurrentUser.id(), businessId, req.orderedIds());
    }

    // ---- Staff scheduling (booking — Salon & Beauty) --------------------

    @GetMapping("/team/{staffId}/schedule")
    public StaffScheduleService.StaffScheduleResponse getStaffSchedule(@PathVariable UUID businessId, @PathVariable UUID staffId) {
        return staffSchedule.scheduleFor(CurrentUser.id(), businessId, staffId);
    }

    @PutMapping("/team/{staffId}/schedule")
    public List<StaffWeeklySchedule> updateStaffSchedule(@PathVariable UUID businessId, @PathVariable UUID staffId,
                                                         @Valid @RequestBody ReplaceScheduleRequest req) {
        return staffSchedule.replaceSchedule(CurrentUser.id(), businessId, staffId, req);
    }

    @PutMapping("/team/{staffId}/services")
    public List<StaffServiceAssignment> updateStaffServices(@PathVariable UUID businessId, @PathVariable UUID staffId,
                                                             @Valid @RequestBody StaffServiceAssignmentsRequest req) {
        return staffSchedule.replaceServices(CurrentUser.id(), businessId, staffId, req);
    }

    @PostMapping("/team/{staffId}/time-off")
    public StaffTimeOff addStaffTimeOff(@PathVariable UUID businessId, @PathVariable UUID staffId,
                                        @Valid @RequestBody TimeOffRequest req) {
        return staffSchedule.addTimeOff(CurrentUser.id(), businessId, staffId, req);
    }

    @DeleteMapping("/team/{staffId}/time-off/{id}")
    public ResponseEntity<Void> removeStaffTimeOff(@PathVariable UUID businessId, @PathVariable UUID staffId, @PathVariable UUID id) {
        staffSchedule.removeTimeOff(CurrentUser.id(), businessId, staffId, id);
        return ResponseEntity.noContent().build();
    }

    // ---- Menu items (restaurant) ---------------------------------------

    @GetMapping("/menu-items")
    public List<MenuItem> menu(@PathVariable UUID businessId) {
        return catalog.menu(businessId);
    }

    @PostMapping("/menu-items")
    public MenuItem addMenuItem(@PathVariable UUID businessId, @Valid @RequestBody MenuItemRequest req) {
        return catalog.addMenuItem(CurrentUser.id(), businessId, req);
    }

    @PutMapping("/menu-items/{id}")
    public MenuItem updateMenuItem(@PathVariable UUID businessId, @PathVariable UUID id,
                                   @Valid @RequestBody MenuItemRequest req) {
        return catalog.updateMenuItem(CurrentUser.id(), businessId, id, req);
    }

    @DeleteMapping("/menu-items/{id}")
    public ResponseEntity<Void> deleteMenuItem(@PathVariable UUID businessId, @PathVariable UUID id) {
        catalog.deleteMenuItem(CurrentUser.id(), businessId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/menu-items/reorder")
    public List<MenuItem> reorderMenu(@PathVariable UUID businessId, @Valid @RequestBody ReorderRequest req) {
        return catalog.reorderMenu(CurrentUser.id(), businessId, req.orderedIds());
    }

    // ---- Featured products (retail) -----------------------------------

    @GetMapping("/products")
    public List<FeaturedProduct> products(@PathVariable UUID businessId) {
        return catalog.products(businessId);
    }

    @PostMapping("/products")
    public FeaturedProduct addProduct(@PathVariable UUID businessId, @Valid @RequestBody FeaturedProductRequest req) {
        return catalog.addProduct(CurrentUser.id(), businessId, req);
    }

    @PutMapping("/products/{id}")
    public FeaturedProduct updateProduct(@PathVariable UUID businessId, @PathVariable UUID id,
                                         @Valid @RequestBody FeaturedProductRequest req) {
        return catalog.updateProduct(CurrentUser.id(), businessId, id, req);
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable UUID businessId, @PathVariable UUID id) {
        catalog.deleteProduct(CurrentUser.id(), businessId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/products/reorder")
    public List<FeaturedProduct> reorderProducts(@PathVariable UUID businessId, @Valid @RequestBody ReorderRequest req) {
        return catalog.reorderProducts(CurrentUser.id(), businessId, req.orderedIds());
    }
}
