package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommunityPostMentionRepository extends JpaRepository<CommunityPostMention, UUID> {

    List<CommunityPostMention> findByPostId(UUID postId);

    /** Batch lookup for a feed page — avoids one query per post. */
    List<CommunityPostMention> findByPostIdIn(Collection<UUID> postIds);

    void deleteByPostId(UUID postId);
}
