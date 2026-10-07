package com.bdreview.platform.community;

import com.bdreview.platform.auth.JwtService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * V59 community gender badge, through the real HTTP layer on the throwaway {@code bd_review_it}
 * database: required before posting, shown next to the username, never sent when hidden.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/bd_review_it",
        "features.nid-verification.enabled=false",
        "app.admin.bootstrap-enabled=false",
        "app.storage.local-dir=${java.io.tmpdir}/bd-review-it-uploads"
})
class CommunityGenderIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommunitySettingsService settingsService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE community_settings SET settings = '{}'::jsonb WHERE id = 1");
        settingsService.evict();
    }

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }

    private User saveUser(String communityUsername, String gender) {
        return userRepository.save(User.builder()
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .email(java.util.UUID.randomUUID() + "@it.jachai.test").emailVerifiedAt(java.time.Instant.now())
                .role(UserRole.CONSUMER)
                .name("Gender tester")
                .otpVerified(true)
                .communityUsername(communityUsername)
                .communityGender(gender)
                .build());
    }

    private String token(User u) {
        return jwtService.generateAccessToken(u.getId(), UserRole.CONSUMER);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            b.content(objectMapper.writeValueAsString(body));
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(b).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult post(String token) throws Exception {
        return call(HttpMethod.POST, "/api/v1/community/posts", token,
                Map.of("body", "Gender badge test post " + suffix(), "postType", "DISCUSSION", "topic", "GENERAL", "imageUrls", List.of()));
    }

    @Test
    void setupRequiresAGenderAndStoresIt() throws Exception {
        User fresh = saveUser(null, null);
        Map<String, Object> noGender = new HashMap<>();
        noGender.put("username", "gnd" + suffix());
        MvcResult rejected = call(HttpMethod.POST, "/api/v1/community/username", token(fresh), noGender);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);

        MvcResult ok = call(HttpMethod.POST, "/api/v1/community/username", token(fresh),
                Map.of("username", "gnd" + suffix(), "gender", "F"));
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(ok).get("communityGender").asText()).isEqualTo("F");
        assertThat(json(call(HttpMethod.GET, "/api/v1/users/me", token(fresh), null)).get("communityGender").asText()).isEqualTo("F");
    }

    @Test
    void memberWithoutGenderCannotPostUntilTheyPickOne() throws Exception {
        User legacy = saveUser("leg" + suffix(), null);
        MvcResult blocked = post(token(legacy));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(blocked).get("message").asText()).contains("Male or Female");

        MvcResult picked = call(HttpMethod.PUT, "/api/v1/community/gender", token(legacy), Map.of("gender", "M"));
        assertThat(picked.getResponse().getStatus()).as(picked.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult allowed = post(token(legacy));
        assertThat(allowed.getResponse().getStatus()).as(allowed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(allowed).get("author").get("gender").asText()).isEqualTo("M");
    }

    @Test
    void hiddenBadgeIsNeverSentToOthers() throws Exception {
        User author = saveUser("hid" + suffix(), "F");
        String username = author.getCommunityUsername();
        UUID postId = UUID.fromString(json(post(token(author))).get("id").asText());
        User viewer = saveUser("vw" + suffix(), "M");

        assertThat(json(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, token(viewer), null))
                .get("author").get("gender").asText()).isEqualTo("F");
        assertThat(json(call(HttpMethod.GET, "/api/v1/community/profile/" + username, token(viewer), null))
                .get("gender").asText()).isEqualTo("F");

        MvcResult hide = call(HttpMethod.PUT, "/api/v1/community/gender", token(author), Map.of("visible", false));
        assertThat(hide.getResponse().getStatus()).isEqualTo(200);

        String postJson = call(HttpMethod.GET, "/api/v1/community/posts/" + postId, token(viewer), null).getResponse().getContentAsString();
        assertThat(objectMapper.readTree(postJson).get("author").get("gender").isNull()).isTrue();
        assertThat(json(call(HttpMethod.GET, "/api/v1/community/profile/" + username, token(viewer), null)).get("gender").isNull()).isTrue();
        // The owner still sees their own choice so they can turn it back on.
        JsonNode me = json(call(HttpMethod.GET, "/api/v1/users/me", token(author), null));
        assertThat(me.get("communityGender").asText()).isEqualTo("F");
        assertThat(me.get("communityGenderVisible").asBoolean()).isFalse();
    }

    @Test
    void invalidGenderIsRejected() throws Exception {
        User member = saveUser("inv" + suffix(), "M");
        assertThat(call(HttpMethod.PUT, "/api/v1/community/gender", token(member), Map.of("gender", "X"))
                .getResponse().getStatus()).isEqualTo(400);
    }
}
