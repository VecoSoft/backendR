package com.bdreview.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BoostPackageRepository extends JpaRepository<BoostPackage, UUID> {

    List<BoostPackage> findAllByOrderBySortOrderAscPriceBdtAsc();

    List<BoostPackage> findByActiveTrueOrderBySortOrderAscPriceBdtAsc();
}
