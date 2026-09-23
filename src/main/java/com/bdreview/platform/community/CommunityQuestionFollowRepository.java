package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CommunityQuestionFollowRepository extends JpaRepository<CommunityQuestionFollow, UUID> {

    boolean existsByUserIdAndPostId(UUID userId, UUID postId);

    void deleteByUserIdAndPostId(UUID userId, UUID postId);

    long countByPostId(UUID postId);

    /** Drives the "Last followed <date>" line on a question card. */
    Optional<CommunityQuestionFollow> findTopByPostIdOrderByCreatedAtDesc(UUID postId);
}
