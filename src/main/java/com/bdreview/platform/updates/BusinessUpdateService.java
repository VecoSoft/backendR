package com.bdreview.platform.updates;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.updates.BusinessUpdateRequests.UpsertRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Owner CRUD + public read for {@link BusinessUpdate}. Same ownership rule as the other owner-scoped services. */
@Service
public class BusinessUpdateService {

    private static final int MAX_PAGE_SIZE = 50;

    private final BusinessUpdateRepository repository;
    private final BusinessRepository businessRepository;

    public BusinessUpdateService(BusinessUpdateRepository repository, BusinessRepository businessRepository) {
        this.repository = repository;
        this.businessRepository = businessRepository;
    }

    @Transactional(readOnly = true)
    public Page<BusinessUpdate> publicList(UUID businessId, int page, int size) {
        return repository.findByBusinessIdAndPublishedTrueOrderByPublishedAtDesc(businessId, pageable(page, size));
    }

    @Transactional(readOnly = true)
    public Page<BusinessUpdate> manageList(UUID ownerUserId, UUID businessId, int page, int size) {
        getOwnedOrThrow(ownerUserId, businessId);
        return repository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable(page, size));
    }

    @Transactional(readOnly = true)
    public boolean hasPublished(UUID businessId) {
        return repository.existsByBusinessIdAndPublishedTrue(businessId);
    }

    @Transactional
    public BusinessUpdate create(UUID ownerUserId, UUID businessId, UpsertRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        boolean published = req.published() == null || req.published();
        return repository.save(BusinessUpdate.builder()
                .businessId(businessId)
                .body(req.body().trim())
                .imageUrl(blankToNull(req.imageUrl()))
                .published(published)
                .build());
    }

    @Transactional
    public BusinessUpdate update(UUID ownerUserId, UUID businessId, UUID id, UpsertRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        BusinessUpdate row = ownedRow(businessId, id);
        row.setBody(req.body().trim());
        row.setImageUrl(blankToNull(req.imageUrl()));
        if (req.published() != null) {
            applyPublished(row, req.published());
        }
        return repository.save(row);
    }

    @Transactional
    public BusinessUpdate setPublished(UUID ownerUserId, UUID businessId, UUID id, boolean published) {
        getOwnedOrThrow(ownerUserId, businessId);
        BusinessUpdate row = ownedRow(businessId, id);
        applyPublished(row, published);
        return repository.save(row);
    }

    @Transactional
    public void delete(UUID ownerUserId, UUID businessId, UUID id) {
        getOwnedOrThrow(ownerUserId, businessId);
        repository.deleteByIdAndBusinessId(id, businessId);
    }

    private void applyPublished(BusinessUpdate row, boolean published) {
        row.setPublished(published);
        // Stamp publishedAt the first time it goes live; keep the original date on re-publish so
        // the public ordering doesn't jump around, but set it if it was never stamped.
        if (published && row.getPublishedAt() == null) {
            row.setPublishedAt(Instant.now());
        }
    }

    private BusinessUpdate ownedRow(UUID businessId, UUID id) {
        BusinessUpdate row = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Update not found"));
        if (!row.getBusinessId().equals(businessId)) {
            throw new ResourceNotFoundException("Update not found");
        }
        return row;
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

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
