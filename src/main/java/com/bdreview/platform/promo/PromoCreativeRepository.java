package com.bdreview.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PromoCreativeRepository extends JpaRepository<PromoCreative, UUID> {

    List<PromoCreative> findTop20ByBusinessIdOrderByCreatedAtDesc(UUID businessId);
}
