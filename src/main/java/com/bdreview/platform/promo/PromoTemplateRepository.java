package com.bdreview.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromoTemplateRepository extends JpaRepository<PromoTemplate, UUID> {

    List<PromoTemplate> findAllByOrderBySortOrderAscNameAsc();

    List<PromoTemplate> findByActiveTrueOrderBySortOrderAscNameAsc();

    Optional<PromoTemplate> findByKey(String key);
}
