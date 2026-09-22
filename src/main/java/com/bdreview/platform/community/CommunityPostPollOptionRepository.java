package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostPollOptionRepository extends JpaRepository<CommunityPostPollOption, UUID> {

    List<CommunityPostPollOption> findByPollIdOrderByPosition(UUID pollId);

    List<CommunityPostPollOption> findByPollIdInOrderByPosition(Collection<UUID> pollIds);

    Optional<CommunityPostPollOption> findByIdAndPollId(UUID id, UUID pollId);

    // clearAutomatically: CommunityPostService#votePoll re-reads options (and the vote
    // row) in the same transaction right after this runs, to build the response — without
    // it, Hibernate's persistence context returns the pre-update entities it already has
    // cached by id instead of the fresh row (post/comment vote counters never hit this
    // because nothing re-reads them in-transaction; votePoll does, for live reveal state).
    // flushAutomatically: clearAutomatically alone drops any not-yet-flushed pending write
    // (e.g. the just-saved CommunityPostPollVote on a first-time vote) — clear() detaches
    // it before Hibernate ever issues its INSERT. Flushing first avoids losing it.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE CommunityPostPollOption o SET o.voteCount = o.voteCount + :delta WHERE o.id = :id")
    void adjustVoteCount(@Param("id") UUID id, @Param("delta") int delta);
}
