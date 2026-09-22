package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** One selectable option of a {@link CommunityPostPoll}. voteCount is written only via atomic SQL increments (see CommunityPostPollOptionRepository). */
@Entity
@Table(name = "community_post_poll_option")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostPollOption {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(nullable = false)
    private short position;

    @Builder.Default
    @Column(name = "vote_count", nullable = false)
    private int voteCount = 0;
}
