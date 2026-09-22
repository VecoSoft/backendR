package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostPollVoteRepository extends JpaRepository<CommunityPostPollVote, UUID> {

    Optional<CommunityPostPollVote> findByPollIdAndUserId(UUID pollId, UUID userId);

    List<CommunityPostPollVote> findByPollIdInAndUserId(Collection<UUID> pollIds, UUID userId);

    @Modifying
    @Transactional
    void deleteByPollIdAndUserId(UUID pollId, UUID userId);
}
