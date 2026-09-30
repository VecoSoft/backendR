package com.bdreview.platform.community.settings;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ConflictException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.CommunityTopic;
import com.bdreview.platform.community.CommunityTopicRepository;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/** Topic CRUD / reorder / default (ADMIN only). Every change is audit-logged and evicts the settings cache. */
@Service
public class CommunityTopicService {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,19}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final CommunityTopicRepository topicRepository;
    private final CommunitySettingsService settingsService;
    private final AuditLogService auditLogService;

    public CommunityTopicService(CommunityTopicRepository topicRepository,
                                 CommunitySettingsService settingsService,
                                 AuditLogService auditLogService) {
        this.topicRepository = topicRepository;
        this.settingsService = settingsService;
        this.auditLogService = auditLogService;
    }

    public List<CommunityTopic> list() {
        return topicRepository.findAllByOrderByPositionAscLabelAsc();
    }

    public record TopicInput(String code, String label, String labelBn, String icon, String color,
                             Boolean enabled, Boolean defaultTopic) {
    }

    @Transactional
    public CommunityTopic create(TopicInput in) {
        CurrentUser.requireRole("ADMIN");
        String code = in.code() == null ? "" : in.code().trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new BadRequestException("Topic code must be 2-20 characters: A-Z, 0-9 or _, starting with a letter");
        }
        if (topicRepository.existsById(code)) {
            throw new ConflictException("A topic with code " + code + " already exists");
        }
        int nextPosition = list().stream().mapToInt(CommunityTopic::getPosition).max().orElse(0) + 1;
        CommunityTopic topic = CommunityTopic.builder().code(code).position(nextPosition).build();
        apply(topic, in);
        topic = topicRepository.save(topic);
        auditLogService.record("COMMUNITY_TOPIC", null, "TOPIC_CREATED", "Created topic " + code, null, snapshot(topic));
        settingsService.evict();
        return topic;
    }

    @Transactional
    public CommunityTopic update(String code, TopicInput in) {
        CurrentUser.requireRole("ADMIN");
        CommunityTopic topic = require(code);
        Map<String, Object> before = snapshot(topic);
        apply(topic, in);
        topic = topicRepository.save(topic);
        auditLogService.record("COMMUNITY_TOPIC", null, "TOPIC_UPDATED", "Updated topic " + code, before, snapshot(topic));
        settingsService.evict();
        return topic;
    }

    @Transactional
    public void reorder(List<String> orderedCodes) {
        CurrentUser.requireRole("ADMIN");
        Map<String, CommunityTopic> byCode = new HashMap<>();
        list().forEach(t -> byCode.put(t.getCode(), t));
        List<String> before = list().stream().map(CommunityTopic::getCode).toList();
        int pos = 1;
        for (String c : orderedCodes) {
            CommunityTopic t = byCode.remove(c);
            if (t != null) {
                t.setPosition(pos++);
            }
        }
        for (CommunityTopic leftover : byCode.values()) {
            leftover.setPosition(pos++);
        }
        topicRepository.flush();
        auditLogService.record("COMMUNITY_TOPIC", null, "TOPICS_REORDERED", "Reordered topics",
                Map.of("order", before), Map.of("order", list().stream().map(CommunityTopic::getCode).toList()));
        settingsService.evict();
    }

    @Transactional
    public void move(String code, int delta) {
        List<String> codes = new ArrayList<>(list().stream().map(CommunityTopic::getCode).toList());
        int i = codes.indexOf(code);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= codes.size()) {
            return;
        }
        Collections.swap(codes, i, j);
        reorder(codes);
    }

    private void apply(CommunityTopic topic, TopicInput in) {
        if (in.label() == null || in.label().isBlank() || in.label().trim().length() > 60) {
            throw new BadRequestException("Topic label is required (max 60 characters)");
        }
        topic.setLabel(in.label().trim());
        topic.setLabelBn(blankToNull(in.labelBn()));
        topic.setIcon(blankToNull(in.icon()));
        String color = blankToNull(in.color());
        if (color != null && !COLOR.matcher(color).matches()) {
            throw new BadRequestException("Color must be a hex value like #0ea5e9");
        }
        topic.setColor(color);
        if (in.enabled() != null) {
            topic.setEnabled(in.enabled());
        }
        if (Boolean.TRUE.equals(in.defaultTopic())) {
            topicRepository.clearDefault();
            topic.setDefaultTopic(true);
            topic.setEnabled(true);
        } else if (Boolean.FALSE.equals(in.defaultTopic()) && topic.isDefaultTopic()) {
            throw new BadRequestException("Pick another default topic first");
        }
        if (topic.isDefaultTopic() && !topic.isEnabled()) {
            throw new BadRequestException("The default topic can't be disabled");
        }
    }

    private CommunityTopic require(String code) {
        return topicRepository.findById(code).orElseThrow(() -> new ResourceNotFoundException("Topic not found"));
    }

    private static Map<String, Object> snapshot(CommunityTopic t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.getCode());
        m.put("label", t.getLabel());
        m.put("labelBn", t.getLabelBn());
        m.put("icon", t.getIcon());
        m.put("color", t.getColor());
        m.put("position", t.getPosition());
        m.put("enabled", t.isEnabled());
        m.put("default", t.isDefaultTopic());
        return m;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
