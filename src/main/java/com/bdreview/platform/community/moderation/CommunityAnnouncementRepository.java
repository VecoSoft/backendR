package com.bdreview.platform.community.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityAnnouncementRepository extends JpaRepository<CommunityAnnouncement, UUID> {

    List<CommunityAnnouncement> findAllByOrderByCreatedAtDesc();

    List<CommunityAnnouncement> findByShowBannerTrueOrderByCreatedAtDesc();

    Optional<CommunityAnnouncement> findByPostId(UUID postId);
}
