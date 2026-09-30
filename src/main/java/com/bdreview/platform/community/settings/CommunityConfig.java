package com.bdreview.platform.community.settings;

import com.bdreview.platform.community.CommunityPostType;

import java.util.List;
import java.util.Optional;

/** One cached snapshot of settings + topics — what every community request reads. */
public record CommunityConfig(CommunitySettings settings, List<TopicView> topics) {

    public Optional<TopicView> topic(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return topics.stream().filter(t -> t.code().equalsIgnoreCase(code)).findFirst();
    }

    public String defaultTopicCode() {
        return topics.stream().filter(TopicView::defaultTopic).map(TopicView::code).findFirst()
                .orElse(topics.isEmpty() ? "GENERAL" : topics.get(0).code());
    }

    public boolean postTypeEnabled(CommunityPostType type) {
        CommunitySettings.PostTypes p = settings.getPostTypes();
        return switch (type) {
            case DISCUSSION -> p.isDiscussionEnabled();
            case QUESTION -> p.isQuestionEnabled();
            case RECOMMENDATION -> p.isRecommendationEnabled();
            case POLL -> p.isPollEnabled();
        };
    }
}
