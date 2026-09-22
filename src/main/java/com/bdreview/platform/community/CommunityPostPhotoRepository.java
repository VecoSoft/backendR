package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommunityPostPhotoRepository extends JpaRepository<CommunityPostPhoto, UUID> {

    List<CommunityPostPhoto> findByPostIdOrderByPositionAsc(UUID postId);

    /** Batch lookup for a feed page — avoids one query per post; group and sort by position client-side. */
    List<CommunityPostPhoto> findByPostIdIn(Collection<UUID> postIds);

    void deleteByPostId(UUID postId);
}
