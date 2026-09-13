package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostReactionRepository extends JpaRepository<CommunityPostReaction, UUID> {

    Optional<CommunityPostReaction> findByPostIdAndUserId(UUID postId, UUID userId);

    void deleteByPostIdAndUserId(UUID postId, UUID userId);

    /** Batch lookup for a feed page: which reaction (if any) the current viewer left on each of these posts. */
    List<CommunityPostReaction> findByPostIdInAndUserId(Collection<UUID> postIds, UUID userId);

    void deleteByPostId(UUID postId);
}
