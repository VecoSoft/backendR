package com.bdreview.platform.commerce;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessCommerceSettingsRepository extends JpaRepository<BusinessCommerceSettings, UUID> {
}
