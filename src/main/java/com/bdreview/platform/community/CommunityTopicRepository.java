package com.bdreview.platform.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CommunityTopicRepository extends JpaRepository<CommunityTopic, String> {

    List<CommunityTopic> findAllByOrderByPositionAscLabelAsc();

    @Modifying
    @Query("UPDATE CommunityTopic t SET t.defaultTopic = false WHERE t.defaultTopic = true")
    void clearDefault();
}
