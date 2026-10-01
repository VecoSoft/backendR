package com.bdreview.platform.community;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** V60 community search, through the real HTTP layer on the throwaway {@code bd_review_it} database. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/bd_review_it",
        "features.nid-verification.enabled=false",
        "app.admin.bootstrap-enabled=false",
        "app.storage.local-dir=${java.io.tmpdir}/bd-review-it-uploads"
})
class CommunitySearchIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    /** Unique per test run so earlier runs' rows never match. */
    String tag;
    User author;

    @BeforeEach
    void setUp() {
        tag = "zq" + suffix();
        author = saveUser("au" + suffix(), "Real Person Name " + tag);
    }

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }

    private User saveUser(String username, String realName) {
        return userRepository.save(User.builder()
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .role(UserRole.CONSUMER)
                .name(realName)
                .otpVerified(true)
                .communityUsername(username)
                .communityGender("M")
                .build());
    }

    private UUID insertPost(String title, String body, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO community_post (id, author_user_id, title, body, post_type, topic, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'DISCUSSION', 'GENERAL', ?, now(), now(), 0)
                """, id, author.getId(), title, body, status);
        return id;
    }

    private List<String> ids(String path, String q, String field) throws Exception {
        String body = mvc.perform(get(path).param("q", q)).andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(body).get("content");
        List<String> out = new ArrayList<>();
        content.forEach(n -> out.add("id".equals(field) ? n.get("id").asText() : n.get("author").get(field).asText()));
        return out;
    }

    private List<String> posts(String q) throws Exception {
        return ids("/api/v1/community/search/posts", q, "id");
    }

    private List<String> people(String q) throws Exception {
        return ids("/api/v1/community/search/people", q, "communityUsername");
    }

    @Test
    void findsLivePostsByEveryWordInBanglaOrEnglish() throws Exception {
        // Oldest, but the whole phrase is in its title — ranks first anyway.
        UUID titled = insertPost("Kacchi biryani " + tag, "Where do you go?", "ACTIVE");
        jdbc.update("UPDATE community_post SET created_at = now() - interval '1 day' WHERE id = ?", titled);
        UUID biryani = insertPost(null, "Best kacchi place in Dhanmondi, biryani " + tag, "ACTIVE");
        UUID bangla = insertPost(null, "গুলশানে ভালো বিরিয়ানি কোথায় পাবো? " + tag, "ACTIVE");

        assertThat(posts("KACCHI biryani " + tag)).containsExactly(titled.toString(), biryani.toString());
        assertThat(posts("বিরিয়ানি " + tag)).containsExactly(bangla.toString());
        assertThat(posts("kacchi pizza " + tag)).isEmpty();
    }

    @Test
    void hiddenRemovedAndDeletedPostsNeverShowUp() throws Exception {
        UUID live = insertPost(null, "visible post " + tag, "ACTIVE");
        insertPost(null, "held post " + tag, "PENDING");
        insertPost(null, "removed post " + tag, "REMOVED");
        UUID deleted = insertPost(null, "deleted post " + tag, "ACTIVE");
        jdbc.update("UPDATE community_post SET deleted_at = now() WHERE id = ?", deleted);

        assertThat(posts("post " + tag)).containsExactly(live.toString());
    }

    @Test
    void peopleMatchUsernameOnlyNeverTheRealName() throws Exception {
        String base = "srch" + suffix().substring(0, 6);
        saveUser(base + "_longer", "Someone");
        saveUser(base, "Someone else");
        saveUser("x" + base, "Third");

        // Exact first, then prefix, then contains.
        assertThat(people(base)).containsExactly(base, base + "_longer", "x" + base);
        assertThat(people("u/" + base).get(0)).isEqualTo(base);
        assertThat(people("@" + base).get(0)).isEqualTo(base);
        // The private account name is never searchable.
        assertThat(people("Real Person Name " + tag)).isEmpty();
        assertThat(people(tag)).isEmpty();
    }

    @Test
    void bannedMembersAreLeftOutOfPeopleSearch() throws Exception {
        String name = "ban" + suffix().substring(0, 6);
        User banned = saveUser(name, "Banned");
        assertThat(people(name)).containsExactly(name);
        jdbc.update("INSERT INTO community_restriction (user_id, type, reason, created_by) VALUES (?, 'BAN', 'test', ?)",
                banned.getId(), author.getId());
        assertThat(people(name)).isEmpty();
    }

    @Test
    void wildcardsAreLiteralAndTooShortQueriesReturnNothing() throws Exception {
        UUID percent = insertPost(null, "Flat 50% off today " + tag, "ACTIVE");
        insertPost(null, "Flat 500 taka today " + tag, "ACTIVE");

        assertThat(posts("50% " + tag)).containsExactly(percent.toString());
        assertThat(posts("a")).isEmpty();
        assertThat(people("_")).isEmpty();
    }
}
