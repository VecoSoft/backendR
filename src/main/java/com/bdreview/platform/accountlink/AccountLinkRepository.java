package com.bdreview.platform.accountlink;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountLinkRepository extends JpaRepository<AccountLink, UUID> {

    Optional<AccountLink> findByConsumerUserId(UUID consumerUserId);

    Optional<AccountLink> findByBusinessUserId(UUID businessUserId);

    boolean existsByConsumerUserId(UUID consumerUserId);

    boolean existsByBusinessUserId(UUID businessUserId);
}
