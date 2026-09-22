package com.bdreview.platform.offer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OfferSaveRepository extends JpaRepository<OfferSave, UUID> {

    boolean existsByUserIdAndOfferId(UUID userId, UUID offerId);

    void deleteByUserIdAndOfferId(UUID userId, UUID offerId);

    Page<OfferSave> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
