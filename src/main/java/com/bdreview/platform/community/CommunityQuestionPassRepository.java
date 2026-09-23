package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CommunityQuestionPassRepository extends JpaRepository<CommunityQuestionPass, UUID> {

    boolean existsByUserIdAndPostId(UUID userId, UUID postId);
}
