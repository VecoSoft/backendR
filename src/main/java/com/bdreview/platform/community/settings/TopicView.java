package com.bdreview.platform.community.settings;

import com.bdreview.platform.community.CommunityTopic;

/** Cache/API-friendly copy of a {@link CommunityTopic} row. */
public record TopicView(String code, String label, String labelBn, String icon, String color,
                        int position, boolean enabled, boolean defaultTopic) {

    public static TopicView from(CommunityTopic t) {
        return new TopicView(t.getCode(), t.getLabel(), t.getLabelBn(), t.getIcon(), t.getColor(),
                t.getPosition(), t.isEnabled(), t.isDefaultTopic());
    }
}
