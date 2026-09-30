package com.bdreview.platform.community;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.community.moderation.CommunityPolicyService;
import com.bdreview.platform.community.moderation.CommunityRestrictionRepository;
import com.bdreview.platform.community.settings.CommunityConfig;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.community.settings.TopicView;
import com.bdreview.platform.report.ReportRepository;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.review.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers doc §36 points 5-8 and 10-15: text-post creation, empty-post/title
 * validation, no-image-on-new-posts, author identity, comments/replies with
 * depth capping, vote toggle/swap, and optional business attachment.
 */
@ExtendWith(MockitoExtension.class)
class CommunityPostServiceTest {

    @Mock CommunityPostRepository postRepository;
    @Mock CommunityPostVoteRepository voteRepository;
    @Mock CommunityCommentVoteRepository commentVoteRepository;
    @Mock CommunityPostCommentRepository commentRepository;
    @Mock CommunityPostMentionRepository mentionRepository;
    @Mock CommunityBusinessMentionRepository businessMentionRepository;
    @Mock CommunityPostPhotoRepository photoRepository;
    @Mock CommunityFollowRepository followRepository;
    @Mock CommunityQuestionFollowRepository questionFollowRepository;
    @Mock CommunityQuestionPassRepository questionPassRepository;
    @Mock CommunityPostPollRepository pollRepository;
    @Mock CommunityPostPollOptionRepository pollOptionRepository;
    @Mock CommunityPostPollVoteRepository pollVoteRepository;
    @Mock UserRepository userRepository;
    @Mock BusinessRepository businessRepository;
    @Mock AreaRepository areaRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock ObjectStorageClient objectStorageClient;
    @Mock CommunityNotifier communityNotifier;

    @Mock CommunitySettingsService settingsService;
    @Mock CommunityRestrictionRepository restrictionRepository;
    @Mock ReportRepository reportRepository;

    CommunityPostService service;
    CommunityPolicyService policy;
    UUID userId;

    @BeforeEach
    void setUp() {
        // Real policy over mocked storage: default community settings + two enabled topics.
        lenient().when(settingsService.config()).thenReturn(new CommunityConfig(new CommunitySettings(), List.of(
                new TopicView("FOOD", "Food", null, null, null, 1, true, false),
                new TopicView("GENERAL", "General", null, null, null, 2, true, true))));
        policy = new CommunityPolicyService(settingsService, restrictionRepository, postRepository, commentRepository, reportRepository);
        service = new CommunityPostService(postRepository, voteRepository, commentVoteRepository, commentRepository,
                mentionRepository, businessMentionRepository, photoRepository, followRepository, questionFollowRepository,
                questionPassRepository, pollRepository, pollOptionRepository,
                pollVoteRepository, userRepository, businessRepository,
                areaRepository, reviewRepository, objectStorageClient, communityNotifier, policy);
        userId = UUID.randomUUID();
        // Every write now resolves the acting user first (restriction + rate-limit checks).
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        // lenient: not every test's response-assembly path touches both of these
        lenient().when(mentionRepository.findByPostId(any())).thenReturn(List.of());
        lenient().when(reviewRepository.countGroupedByUserId(any())).thenReturn(List.of());
    }

    private User pseudonymousUser(UUID id) {
        return User.builder().id(id).name("Nur Sayed").communityUsername("UrbanExplorer42").build();
    }

    private CreateCommunityPostRequest textPostRequest() {
        return new CreateCommunityPostRequest(
                "Best burger under 500tk?", "Looking for recommendations in Dhanmondi.",
                CommunityPostType.QUESTION, "FOOD", null, null, null, null, null);
    }

    @Test
    void userCanCreateATextPost() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.save(any())).thenAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        CommunityPostResponse response = service.createPost(userId, textPostRequest());

        assertThat(response.title()).isEqualTo("Best burger under 500tk?");
        assertThat(response.body()).isEqualTo("Looking for recommendations in Dhanmondi.");
        assertThat(response.imageUrls()).isEmpty(); // no imageUrls were sent on this request
        assertThat(response.author().communityUsername()).isEqualTo("UrbanExplorer42");
        assertThat(response.postType()).isEqualTo(CommunityPostType.QUESTION);
        assertThat(response.topic()).isEqualTo("FOOD");
    }

    @Test
    void photoAttachmentsArePersistedOnTheNewPostInOrder() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        UUID postId = UUID.randomUUID();
        when(postRepository.save(any())).thenAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            p.setId(postId);
            return p;
        });
        List<String> urls = List.of(
                "https://cdn.example.com/community-post/abc/1.jpg",
                "https://cdn.example.com/community-post/abc/2.jpg");
        CreateCommunityPostRequest request = new CreateCommunityPostRequest(
                null, "Look at this place!", CommunityPostType.DISCUSSION, "GENERAL",
                null, null, null, null, urls);

        service.createPost(userId, request);

        verify(photoRepository).saveAll(argThat((List<CommunityPostPhoto> photos) ->
                photos.size() == 2
                        && photos.get(0).getPostId().equals(postId) && photos.get(0).getUrl().equals(urls.get(0)) && photos.get(0).getPosition() == 0
                        && photos.get(1).getUrl().equals(urls.get(1)) && photos.get(1).getPosition() == 1));
    }

    @Test
    void moreThanTenPhotosIsRejected() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        List<String> elevenUrls = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> "https://cdn.example.com/community-post/abc/" + i + ".jpg").toList();
        CreateCommunityPostRequest request = new CreateCommunityPostRequest(
                null, "Too many photos", CommunityPostType.DISCUSSION, "GENERAL",
                null, null, null, null, elevenUrls);

        assertThatThrownBy(() -> service.createPost(userId, request))
                .isInstanceOf(BadRequestException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void wholeCommunityAuthorSummaryNeverCarriesTheRealAccountName() {
        // CommunityAuthorSummary's record shape has no `name`/`profilePhotoUrl` field at all —
        // this pins that toAuthorSummary populates communityUsername (never falls back to name).
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.save(any())).thenAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        CommunityPostResponse response = service.createPost(userId, textPostRequest());

        assertThat(response.author().communityUsername()).isNotEqualTo("Nur Sayed");
        assertThat(response.author().communityUsername()).isEqualTo("UrbanExplorer42");
    }

    @Test
    void postingWithoutACommunityUsernameIsRejected() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).name("Nur Sayed").communityUsername(null).build()));

        assertThatThrownBy(() -> service.createPost(userId, textPostRequest()))
                .isInstanceOf(BadRequestException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void aBlankOrMissingTitleIsAcceptedAndNormalizedToNull() {
        // The composer is a single text box (no title field) — title is optional at the
        // service layer; a blank/whitespace-only value must be stored as null, not rejected.
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.save(any())).thenAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        CreateCommunityPostRequest noTitle = new CreateCommunityPostRequest(
                "          ", "Just a quick update, no title needed.",
                CommunityPostType.DISCUSSION, "GENERAL", null, null, null, null, null);

        CommunityPostResponse response = service.createPost(userId, noTitle);

        assertThat(response.title()).isNull();
        assertThat(response.body()).isEqualTo("Just a quick update, no title needed.");
    }

    @Test
    void postingTooFrequentlyIsRateLimited() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(20L);

        assertThatThrownBy(() -> service.createPost(userId, textPostRequest()))
                .isInstanceOf(RateLimitExceededException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void businessCanBeOptionallyAttachedAndAreaIsInferredFromIt() {
        UUID businessId = UUID.randomUUID();
        UUID areaId = UUID.randomUUID();
        Business business = Business.builder().id(businessId).ownerUserId(UUID.randomUUID())
                .name("ABC Restaurant").area(Area.builder().id(areaId).build()).deletedAt(null).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business));
        when(businessRepository.findAllById(List.of(businessId))).thenReturn(List.of(business));
        ArgumentCaptor<CommunityPost> captor = ArgumentCaptor.forClass(CommunityPost.class);
        when(postRepository.save(captor.capture())).thenAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        CreateCommunityPostRequest request = new CreateCommunityPostRequest(
                "Had a great experience here", "Food and service were both excellent.",
                CommunityPostType.RECOMMENDATION, "FOOD", businessId, null, null, null, null);
        service.createPost(userId, request);

        assertThat(captor.getValue().getAreaId()).isEqualTo(areaId);
        verify(mentionRepository).saveAll(argThat((List<CommunityPostMention> mentions) ->
                mentions.size() == 1 && mentions.get(0).getBusinessId().equals(businessId)));
    }

    @Test
    void attachingADeletedBusinessIsRejected() {
        UUID businessId = UUID.randomUUID();
        Business deleted = Business.builder().id(businessId).ownerUserId(UUID.randomUUID())
                .deletedAt(java.time.Instant.now()).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(deleted));

        CreateCommunityPostRequest request = new CreateCommunityPostRequest(
                "Title long enough", null, CommunityPostType.DISCUSSION, "GENERAL", businessId, null, null, null, null);

        assertThatThrownBy(() -> service.createPost(userId, request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void feedReturnsPostsWithNoAreaWithoutThrowing() {
        // Regression: Map.of()'s get() throws NPE on a null key (unlike HashMap.get(null)),
        // and most posts have no areaId — buildPageResponse must guard that lookup.
        CommunityPost post = CommunityPost.builder().id(UUID.randomUUID()).authorUserId(userId)
                .title("No area here").areaId(null).build();
        when(postRepository.findLiveByFiltersOrderByCreatedAtDesc(any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(post)));
        when(userRepository.findAllById(any())).thenReturn(List.of(pseudonymousUser(userId)));

        var response = service.feed(null, null, null, CommunitySortOrder.NEW, null, 0, 20, null);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).area()).isNull();
    }

    @Test
    void userCanCommentOnAPost() {
        UUID postId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(commentRepository.save(any())).thenAnswer(inv -> {
            CommunityPostComment c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CommunityCommentResponse response = service.addComment(userId, postId,
                new CreateCommunityCommentRequest("Try ABC Burger.", null));

        assertThat(response.content()).isEqualTo("Try ABC Burger.");
        assertThat(response.depth()).isZero();
        assertThat(response.parentCommentId()).isNull();
        verify(postRepository).adjustCommentCount(postId, 1);
    }

    @Test
    void userCanReplyToAComment() {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(parentId, postId)).thenReturn(Optional.of(
                CommunityPostComment.builder().id(parentId).postId(postId)
                        .authorUserId(UUID.randomUUID()).depth((short) 0).build()));
        when(commentRepository.save(any())).thenAnswer(inv -> {
            CommunityPostComment c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CommunityCommentResponse response = service.addComment(userId, postId,
                new CreateCommunityCommentRequest("Thanks!", parentId));

        assertThat(response.depth()).isEqualTo(1);
        assertThat(response.parentCommentId()).isEqualTo(parentId);
        verify(communityNotifier).commentReplied(any(), eq(postId), any());
    }

    @Test
    void replyDepthIsCappedAtFiveLevels() {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(parentId, postId)).thenReturn(Optional.of(
                CommunityPostComment.builder().id(parentId).postId(postId)
                        .authorUserId(UUID.randomUUID()).depth((short) 5).build()));

        assertThatThrownBy(() -> service.addComment(userId, postId,
                new CreateCommunityCommentRequest("One reply too many", parentId)))
                .isInstanceOf(BadRequestException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void firstVoteCreatesARowAndIncrementsTheCount() {
        UUID postId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(voteRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.empty());

        service.vote(userId, postId, CommunityPostVoteType.UPVOTE);

        verify(voteRepository).save(argThat(v -> v.getPostId().equals(postId) && v.getVoteType() == CommunityPostVoteType.UPVOTE));
        verify(postRepository).adjustUpvoteCount(postId, 1);
    }

    @Test
    void repeatingTheSameVoteRemovesItInsteadOfDuplicating() {
        UUID postId = UUID.randomUUID();
        CommunityPostVote existing = CommunityPostVote.builder().postId(postId).userId(userId)
                .voteType(CommunityPostVoteType.UPVOTE).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(voteRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.of(existing));

        service.vote(userId, postId, CommunityPostVoteType.UPVOTE);

        verify(voteRepository).deleteByPostIdAndUserId(postId, userId);
        verify(voteRepository, never()).save(any());
        verify(postRepository).adjustUpvoteCount(postId, -1);
        verify(postRepository, never()).adjustDownvoteCount(any(), anyInt());
    }

    @Test
    void votingTheOtherWaySwapsTheVoteRatherThanAddingASecondRow() {
        UUID postId = UUID.randomUUID();
        CommunityPostVote existing = CommunityPostVote.builder().postId(postId).userId(userId)
                .voteType(CommunityPostVoteType.UPVOTE).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(voteRepository.findByPostIdAndUserId(postId, userId)).thenReturn(Optional.of(existing));

        service.vote(userId, postId, CommunityPostVoteType.DOWNVOTE);

        verify(postRepository).adjustUpvoteCount(postId, -1);
        verify(postRepository).adjustDownvoteCount(postId, 1);
        verify(voteRepository).save(argThat(v -> v.getVoteType() == CommunityPostVoteType.DOWNVOTE));
        verify(voteRepository, never()).deleteByPostIdAndUserId(any(), any());
    }

    // -----------------------------------------------------------------
    // Polls
    // -----------------------------------------------------------------

    private CreateCommunityPostRequest pollRequest(List<String> options, Integer durationHours) {
        return new CreateCommunityPostRequest(null, "Which is the best biryani spot?",
                CommunityPostType.POLL, "FOOD", null, null, options, durationHours, null);
    }

    @Test
    void creatingAPollRequiresAtLeastTwoOptions() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.createPost(userId, pollRequest(List.of("Sultan's Dine"), 24)))
                .isInstanceOf(BadRequestException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void creatingAPollRejectsMoreThanSixOptions() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        List<String> sevenOptions = List.of("A", "B", "C", "D", "E", "F", "G");

        assertThatThrownBy(() -> service.createPost(userId, pollRequest(sevenOptions, 24)))
                .isInstanceOf(BadRequestException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void creatingAPollRejectsDuplicateOptions() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.createPost(userId, pollRequest(List.of("Sultan's Dine", "sultan's dine"), 24)))
                .isInstanceOf(BadRequestException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void creatingAPollRequiresOneOfTheThreeAllowedDurations() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.createPost(userId, pollRequest(List.of("Sultan's Dine", "Star Kabab"), 5)))
                .isInstanceOf(BadRequestException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void creatingAPollSavesOptionsInOrder() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(postRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.save(any())).thenAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(pollRepository.save(any())).thenAnswer(inv -> {
            CommunityPostPoll p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        service.createPost(userId, pollRequest(List.of("Sultan's Dine", "Star Kabab", "Kacchi Bhai"), 72));

        verify(pollOptionRepository).saveAll(argThat((List<CommunityPostPollOption> options) ->
                options.size() == 3
                        && options.get(0).getLabel().equals("Sultan's Dine") && options.get(0).getPosition() == 0
                        && options.get(2).getLabel().equals("Kacchi Bhai") && options.get(2).getPosition() == 2));
    }

    @Test
    void firstPollVoteCreatesARowAndIncrementsTheCount() {
        UUID postId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        CommunityPostPoll poll = CommunityPostPoll.builder().id(pollId).postId(postId)
                .closesAt(java.time.Instant.now().plusSeconds(3600)).build();
        CommunityPostPollOption option = CommunityPostPollOption.builder().id(optionId).pollId(pollId)
                .label("Sultan's Dine").position((short) 0).voteCount(0).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(pollRepository.findByPostId(postId)).thenReturn(Optional.of(poll));
        when(pollOptionRepository.findByIdAndPollId(optionId, pollId)).thenReturn(Optional.of(option));
        when(pollVoteRepository.findByPollIdAndUserId(pollId, userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(CommunityPostPollVote.builder().pollId(pollId).userId(userId).optionId(optionId).build()));
        when(pollOptionRepository.findByPollIdOrderByPosition(pollId))
                .thenReturn(List.of(CommunityPostPollOption.builder().id(optionId).pollId(pollId)
                        .label("Sultan's Dine").position((short) 0).voteCount(1).build()));

        CommunityPollResponse response = service.votePoll(userId, postId, optionId);

        verify(pollVoteRepository).save(argThat(v -> v.getOptionId().equals(optionId) && v.getUserId().equals(userId)));
        verify(pollOptionRepository).adjustVoteCount(optionId, 1);
        assertThat(response.myVoteOptionId()).isEqualTo(optionId);
        // The viewer just voted, so counts are revealed to them immediately.
        assertThat(response.options().get(0).voteCount()).isEqualTo(1);
    }

    @Test
    void repeatingTheSamePollVoteRemovesIt() {
        UUID postId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        CommunityPostPoll poll = CommunityPostPoll.builder().id(pollId).postId(postId)
                .closesAt(java.time.Instant.now().plusSeconds(3600)).build();
        CommunityPostPollOption option = CommunityPostPollOption.builder().id(optionId).pollId(pollId)
                .label("Sultan's Dine").position((short) 0).voteCount(1).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(pollRepository.findByPostId(postId)).thenReturn(Optional.of(poll));
        when(pollOptionRepository.findByIdAndPollId(optionId, pollId)).thenReturn(Optional.of(option));
        when(pollVoteRepository.findByPollIdAndUserId(pollId, userId))
                .thenReturn(Optional.of(CommunityPostPollVote.builder().pollId(pollId).userId(userId).optionId(optionId).build()))
                .thenReturn(Optional.empty());
        when(pollOptionRepository.findByPollIdOrderByPosition(pollId))
                .thenReturn(List.of(CommunityPostPollOption.builder().id(optionId).pollId(pollId)
                        .label("Sultan's Dine").position((short) 0).voteCount(0).build()));

        CommunityPollResponse response = service.votePoll(userId, postId, optionId);

        verify(pollVoteRepository).deleteByPollIdAndUserId(pollId, userId);
        verify(pollVoteRepository, never()).save(any());
        verify(pollOptionRepository).adjustVoteCount(optionId, -1);
        assertThat(response.myVoteOptionId()).isNull();
        // Unvoted and still open — counts go back to hidden for this viewer.
        assertThat(response.options().get(0).voteCount()).isNull();
    }

    @Test
    void votingOnAClosedPollIsRejected() {
        UUID postId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        CommunityPostPoll closedPoll = CommunityPostPoll.builder().id(pollId).postId(postId)
                .closesAt(java.time.Instant.now().minusSeconds(3600)).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(UUID.randomUUID()).build()));
        when(pollRepository.findByPostId(postId)).thenReturn(Optional.of(closedPoll));

        assertThatThrownBy(() -> service.votePoll(userId, postId, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class);
        verify(pollVoteRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // Questions — Best Answer, Open/Answered/Closed, Answers vs Comments
    // -----------------------------------------------------------------

    private CommunityPost questionPost(UUID id, UUID authorId) {
        return CommunityPost.builder().id(id).authorUserId(authorId).postType(CommunityPostType.QUESTION).build();
    }

    @Test
    void markBestAnswerRequiresTheQuestionsOwnAuthor() {
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(questionPost(postId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.markBestAnswer(userId, postId, commentId))
                .isInstanceOf(ForbiddenException.class);
        verify(commentRepository, never()).clearBestAnswer(any());
    }

    @Test
    void markBestAnswerOnlyWorksOnQuestionPosts() {
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(userId)
                        .postType(CommunityPostType.DISCUSSION).build()));

        assertThatThrownBy(() -> service.markBestAnswer(userId, postId, commentId))
                .isInstanceOf(BadRequestException.class);
        verify(commentRepository, never()).clearBestAnswer(any());
    }

    @Test
    void markBestAnswerOnlyAcceptsATopLevelAnswer() {
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(questionPost(postId, userId)));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(commentId, postId)).thenReturn(Optional.of(
                CommunityPostComment.builder().id(commentId).postId(postId)
                        .authorUserId(UUID.randomUUID()).depth((short) 1).build()));

        assertThatThrownBy(() -> service.markBestAnswer(userId, postId, commentId))
                .isInstanceOf(BadRequestException.class);
        verify(commentRepository, never()).clearBestAnswer(any());
    }

    @Test
    void markingANewBestAnswerClearsAnyExistingOneFirst() {
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        UUID answererId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(questionPost(postId, userId)));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(commentId, postId)).thenReturn(Optional.of(
                CommunityPostComment.builder().id(commentId).postId(postId)
                        .authorUserId(answererId).depth((short) 0).content("Try ABC.").build()));

        CommunityCommentResponse response = service.markBestAnswer(userId, postId, commentId);

        InOrder order = inOrder(commentRepository);
        order.verify(commentRepository).clearBestAnswer(postId);
        order.verify(commentRepository).save(argThat(CommunityPostComment::isBestAnswer));
        assertThat(response.isBestAnswer()).isTrue();
        verify(communityNotifier).bestAnswerMarked(answererId, postId, commentId);
    }

    @Test
    void markingYourOwnAnswerAsBestDoesNotNotifyYourself() {
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(questionPost(postId, userId)));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(commentId, postId)).thenReturn(Optional.of(
                CommunityPostComment.builder().id(commentId).postId(postId)
                        .authorUserId(userId).depth((short) 0).content("Self-answered.").build()));

        service.markBestAnswer(userId, postId, commentId);

        verify(communityNotifier, never()).bestAnswerMarked(any(), any(), any());
    }

    @Test
    void unmarkBestAnswerRequiresTheQuestionsOwnAuthor() {
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(questionPost(postId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.unmarkBestAnswer(userId, postId, commentId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void closeQuestionRequiresTheQuestionsOwnAuthor() {
        UUID postId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(questionPost(postId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.closeQuestion(userId, postId))
                .isInstanceOf(ForbiddenException.class);
        verify(postRepository, never()).save(any());
    }

    @Test
    void closeQuestionOnlyWorksOnQuestionPosts() {
        UUID postId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(CommunityPost.builder().id(postId).authorUserId(userId)
                        .postType(CommunityPostType.DISCUSSION).build()));

        assertThatThrownBy(() -> service.closeQuestion(userId, postId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void closingThenReopeningClearsClosedAt() {
        UUID postId = UUID.randomUUID();
        CommunityPost post = questionPost(postId, userId);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.closeQuestion(userId, postId);
        assertThat(post.getClosedAt()).isNotNull();

        service.reopenQuestion(userId, postId);
        assertThat(post.getClosedAt()).isNull();
    }

    @Test
    void newTopLevelAnswerIsRejectedOnAClosedQuestion() {
        UUID postId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        CommunityPost closedQuestion = questionPost(postId, UUID.randomUUID());
        closedQuestion.setClosedAt(java.time.Instant.now());
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(closedQuestion));

        assertThatThrownBy(() -> service.addComment(userId, postId, new CreateCommunityCommentRequest("A new answer", null)))
                .isInstanceOf(BadRequestException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void replyToAnExistingAnswerStillWorksOnAClosedQuestion() {
        UUID postId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        CommunityPost closedQuestion = questionPost(postId, UUID.randomUUID());
        closedQuestion.setClosedAt(java.time.Instant.now());
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(closedQuestion));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(answerId, postId)).thenReturn(Optional.of(
                CommunityPostComment.builder().id(answerId).postId(postId)
                        .authorUserId(UUID.randomUUID()).depth((short) 0).build()));
        when(commentRepository.save(any())).thenAnswer(inv -> {
            CommunityPostComment c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        service.addComment(userId, postId, new CreateCommunityCommentRequest("Does it need a reservation?", answerId));

        verify(commentRepository).save(any());
    }

    @Test
    void answerCountIncrementsOnlyForTopLevelAnswersNotNestedReplies() {
        UUID postId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(0L);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(questionPost(postId, UUID.randomUUID())));
        when(commentRepository.save(any())).thenAnswer(inv -> {
            CommunityPostComment c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        service.addComment(userId, postId, new CreateCommunityCommentRequest("A top-level answer", null));
        verify(postRepository).adjustAnswerCount(postId, 1);

        UUID answerId = UUID.randomUUID();
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(answerId, postId)).thenReturn(Optional.of(
                CommunityPostComment.builder().id(answerId).postId(postId)
                        .authorUserId(UUID.randomUUID()).depth((short) 0).build()));
        service.addComment(userId, postId, new CreateCommunityCommentRequest("Just a clarifying reply", answerId));

        // Still only the one call from the top-level answer above — the nested reply never adjusts answerCount.
        verify(postRepository, times(1)).adjustAnswerCount(eq(postId), anyInt());
    }

    // -----------------------------------------------------------------
    // Following / Followers lists (sidebar people-list pages)
    // -----------------------------------------------------------------

    @Test
    void followingListsThePeopleTheGivenUserFollowsWithViewerFollowStateFlagged() {
        UUID viewerId = UUID.randomUUID();
        UUID followedA = UUID.randomUUID();
        UUID followedB = UUID.randomUUID();
        when(followRepository.findByFollowerUserId(eq(userId), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(
                CommunityFollow.builder().followerUserId(userId).followedUserId(followedA).build(),
                CommunityFollow.builder().followerUserId(userId).followedUserId(followedB).build())));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                User.builder().id(followedA).communityUsername("Alice").build(),
                User.builder().id(followedB).communityUsername("Bob").build()));
        // The viewer already follows Alice but not Bob.
        when(followRepository.findByFollowerUserIdAndFollowedUserIdIn(eq(viewerId), any()))
                .thenReturn(List.of(CommunityFollow.builder().followerUserId(viewerId).followedUserId(followedA).build()));

        when(userRepository.findByCommunityProfileId(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        var response = service.following(userId, 0, 20, viewerId);

        assertThat(response.content()).hasSize(2);
        assertThat(response.content()).anySatisfy(item -> {
            assertThat(item.author().communityUsername()).isEqualTo("Alice");
            assertThat(item.isFollowing()).isTrue();
        });
        assertThat(response.content()).anySatisfy(item -> {
            assertThat(item.author().communityUsername()).isEqualTo("Bob");
            assertThat(item.isFollowing()).isFalse();
        });
    }

    @Test
    void followersListsThePeopleWhoFollowTheGivenUser() {
        UUID followerA = UUID.randomUUID();
        when(followRepository.findByFollowedUserId(eq(userId), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(
                CommunityFollow.builder().followerUserId(followerA).followedUserId(userId).build())));
        when(userRepository.findAllById(any())).thenReturn(List.of(User.builder().id(followerA).communityUsername("Carol").build()));

        when(userRepository.findByCommunityProfileId(userId)).thenReturn(Optional.of(pseudonymousUser(userId)));
        var response = service.followers(userId, 0, 20, null);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).author().communityUsername()).isEqualTo("Carol");
        // Anonymous viewer never "already follows" anyone.
        assertThat(response.content().get(0).isFollowing()).isFalse();
    }

    @Test
    void profileIncludesFollowerAndFollowingCounts() {
        when(userRepository.findByCommunityUsernameIgnoreCase("UrbanExplorer42"))
                .thenReturn(Optional.of(pseudonymousUser(userId)));
        when(followRepository.countByFollowedUserId(userId)).thenReturn(5L);
        when(followRepository.countByFollowerUserId(userId)).thenReturn(3L);

        CommunityProfileResponse response = service.getProfile("UrbanExplorer42", null);

        assertThat(response.followerCount()).isEqualTo(5L);
        assertThat(response.followingCount()).isEqualTo(3L);
    }
}
