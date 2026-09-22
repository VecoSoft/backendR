package com.bdreview.platform.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommunityFollowRepository extends JpaRepository<CommunityFollow, UUID> {

    boolean existsByFollowerUserIdAndFollowedUserId(UUID followerUserId, UUID followedUserId);

    void deleteByFollowerUserIdAndFollowedUserId(UUID followerUserId, UUID followedUserId);

    /** Unpaginated — feed's FOLLOWING tab only (small enough to load in full for the author-id IN clause). */
    List<CommunityFollow> findByFollowerUserId(UUID followerUserId);

    /** Paginated "who do I follow" list — see CommunityPostService#following. */
    Page<CommunityFollow> findByFollowerUserId(UUID followerUserId, Pageable pageable);

    /** Paginated "who follows me" list — see CommunityPostService#followers. */
    Page<CommunityFollow> findByFollowedUserId(UUID followedUserId, Pageable pageable);

    long countByFollowerUserId(UUID followerUserId);

    long countByFollowedUserId(UUID followedUserId);

    /** Batch lookup for a feed/profile page: which of these authors does the viewer already follow. */
    List<CommunityFollow> findByFollowerUserIdAndFollowedUserIdIn(UUID followerUserId, Collection<UUID> followedUserIds);
}
