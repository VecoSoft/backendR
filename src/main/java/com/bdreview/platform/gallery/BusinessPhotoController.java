package com.bdreview.platform.gallery;

import com.bdreview.platform.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/photos")
public class BusinessPhotoController {

    private final BusinessPhotoService photoService;

    public BusinessPhotoController(BusinessPhotoService photoService) {
        this.photoService = photoService;
    }

    @PostMapping("/upload-url")
    public ResponseEntity<PreSignedUploadResponse> requestUploadUrl(@PathVariable UUID businessId,
                                                                      @RequestParam String filename) {
        return ResponseEntity.ok(photoService.requestUploadUrl(CurrentUser.id(), businessId, filename));
    }

    @PostMapping("/confirm")
    public ResponseEntity<BusinessPhoto> confirm(@PathVariable UUID businessId,
                                                   @Valid @RequestBody ConfirmUploadRequest request) {
        return ResponseEntity.ok(photoService.confirmUpload(CurrentUser.id(), request));
    }

    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> delete(@PathVariable UUID businessId, @PathVariable UUID photoId) {
        photoService.delete(CurrentUser.id(), businessId, photoId);
        return ResponseEntity.noContent().build();
    }

    /** Owner reorders their gallery — body carries every photo id once, in the new display order. */
    @PatchMapping("/reorder")
    public ResponseEntity<List<BusinessPhoto>> reorder(@PathVariable UUID businessId,
                                                       @Valid @RequestBody ReorderPhotosRequest request) {
        return ResponseEntity.ok(photoService.reorder(CurrentUser.id(), businessId, request.orderedPhotoIds()));
    }

    @GetMapping
    public ResponseEntity<List<BusinessPhoto>> gallery(@PathVariable UUID businessId) {
        return ResponseEntity.ok(photoService.gallery(businessId, CurrentUser.idOrNull()));
    }
}
