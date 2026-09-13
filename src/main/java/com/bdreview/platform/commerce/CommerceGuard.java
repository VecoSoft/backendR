package com.bdreview.platform.commerce;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Shared owner/live checks for the commerce services — mirrors {@code CatalogService}'s private helpers. */
@Component
class CommerceGuard {

    private final BusinessRepository businessRepository;

    CommerceGuard(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    Business getLiveOrThrow(UUID businessId) {
        return businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    Business getOwnedOrThrow(UUID ownerUserId, UUID businessId) {
        Business business = getLiveOrThrow(businessId);
        if (!business.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return business;
    }
}
