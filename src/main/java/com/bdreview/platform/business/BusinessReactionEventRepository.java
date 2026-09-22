package com.bdreview.platform.business;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessReactionEventRepository extends JpaRepository<BusinessReactionEvent, UUID> {
}
