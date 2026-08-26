package com.bdreview.platform.gallery;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Spec §13: 5-10 photos per business beyond the cover photo. */
@Service
public class BusinessPhotoService {

    private static final int MAX_PHOTOS = 10;

    private final BusinessPhotoRepository photoRepository;
    private final BusinessRepository businessRepository;
    private final ObjectStorageClient objectStorageClient;

    public BusinessPhotoService(BusinessPhotoRepository photoRepository,
                                 BusinessRepository businessRepository,
                                 ObjectStorageClient objectStorageClient) {
        this.photoRepository = photoRepository;
        this.businessRepository = businessRepository;
        this.objectStorageClient = objectStorageClient;
    }

    public PreSignedUploadResponse requestUploadUrl(UUID ownerUserId, UUID businessId, String filename) {
        Business business = getOwnedOrThrow(ownerUserId, businessId);
        if (photoRepository.countByBusinessId(business.getId()) >= MAX_PHOTOS) {
            throw new BadRequestException("Maximum of " + MAX_PHOTOS + " gallery photos reached");
        }
        String key = objectStorageClient.buildObjectKey("business", business.getId().toString(), filename);
        return new PreSignedUploadResponse(
                objectStorageClient.presignPutUrl(key), key, objectStorageClient.cdnUrlFor(key));
    }

    @Transactional
    public BusinessPhoto confirmUpload(UUID ownerUserId, ConfirmUploadRequest request) {
        Business business = getOwnedOrThrow(ownerUserId, request.businessId());
        int nextOrder = (int) photoRepository.countByBusinessId(business.getId());
        return photoRepository.save(BusinessPhoto.builder()
                .businessId(business.getId()).url(request.cdnUrl()).sortOrder(nextOrder).build());
    }

    @Transactional
    public void delete(UUID ownerUserId, UUID businessId, UUID photoId) {
        getOwnedOrThrow(ownerUserId, businessId);
        photoRepository.deleteByIdAndBusinessId(photoId, businessId);
    }

    /**
     * Rewrites {@code sort_order} to match the given order. {@code orderedPhotoIds}
     * must list every current gallery photo id exactly once — a partial or stale
     * list is rejected rather than silently applied.
     */
    @Transactional
    public List<BusinessPhoto> reorder(UUID ownerUserId, UUID businessId, List<UUID> orderedPhotoIds) {
        getOwnedOrThrow(ownerUserId, businessId);
        List<BusinessPhoto> existing = photoRepository.findByBusinessIdOrderBySortOrderAsc(businessId);

        Map<UUID, BusinessPhoto> byId = new HashMap<>();
        for (BusinessPhoto photo : existing) {
            byId.put(photo.getId(), photo);
        }
        if (orderedPhotoIds.size() != existing.size()
                || !byId.keySet().equals(new HashSet<>(orderedPhotoIds))) {
            throw new BadRequestException("Reorder list must contain every gallery photo id exactly once.");
        }

        for (int i = 0; i < orderedPhotoIds.size(); i++) {
            byId.get(orderedPhotoIds.get(i)).setSortOrder(i);
        }
        photoRepository.saveAll(byId.values());
        return photoRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    public List<BusinessPhoto> gallery(UUID businessId) {
        return photoRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    private Business getOwnedOrThrow(UUID ownerUserId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return business;
    }
}
