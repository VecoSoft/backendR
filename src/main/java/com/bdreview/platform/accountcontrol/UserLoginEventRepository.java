package com.bdreview.platform.accountcontrol;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserLoginEventRepository extends JpaRepository<UserLoginEvent, UUID> {

    List<UserLoginEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
