package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityCommentVoteRepository extends JpaRepository<CommunityCommentVote, UUID> {

    Optional<CommunityCommentVote> findByCommentIdAndUserId(UUID commentId, UUID userId);

    void deleteByCommentIdAndUserId(UUID commentId, UUID userId);

    /** Batch lookup for a comment thread: which vote (if any) the current viewer left on each comment. */
    List<CommunityCommentVote> findByCommentIdInAndUserId(Collection<UUID> commentIds, UUID userId);
}
