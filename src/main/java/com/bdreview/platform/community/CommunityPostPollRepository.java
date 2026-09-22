package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostPollRepository extends JpaRepository<CommunityPostPoll, UUID> {

    Optional<CommunityPostPoll> findByPostId(UUID postId);

    List<CommunityPostPoll> findByPostIdIn(Collection<UUID> postIds);
}
