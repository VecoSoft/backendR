package com.bdreview.platform.community;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Covers doc §36 points 1-4: create, uniqueness, format validation, duplicate rejection. */
@ExtendWith(MockitoExtension.class)
class CommunityUsernameServiceTest {

    @Mock UserRepository userRepository;

    CommunityUsernameService service;
    UUID userId;

    @BeforeEach
    void setUp() {
        service = new CommunityUsernameService(userRepository);
        userId = UUID.randomUUID();
    }

    @Test
    void validAvailableUsernameCanBeSet() {
        when(userRepository.existsByCommunityUsernameIgnoreCase("UrbanExplorer42")).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));

        String result = service.setUsername(userId, "UrbanExplorer42", "M");

        assertThat(result).isEqualTo("UrbanExplorer42");
        verify(userRepository).save(argThat(u -> "UrbanExplorer42".equals(u.getCommunityUsername())));
    }

    @Test
    void tooShortIsRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "ab", "M"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void spacesAreRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "urban explorer", "M"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void punctuationOtherThanUnderscoreIsRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "urban-explorer!", "M"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void reservedNameIsRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "admin", "M"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setUsername(userId, "Jachai", "M"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void alreadyTakenUsernameIsRejected() {
        when(userRepository.existsByCommunityUsernameIgnoreCase("DhakaFoodie")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));

        assertThatThrownBy(() -> service.setUsername(userId, "DhakaFoodie", "M"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void uniquenessCheckIsCaseInsensitive() {
        // "already taken" is decided purely via existsByCommunityUsernameIgnoreCase, which the
        // DB backs with a functional index on LOWER(community_username) — this test just pins
        // that the service asks the case-insensitive question, not a case-sensitive one.
        when(userRepository.existsByCommunityUsernameIgnoreCase("dhakafoodie")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));

        assertThatThrownBy(() -> service.setUsername(userId, "dhakafoodie", "M"))
                .isInstanceOf(BadRequestException.class);
        verify(userRepository).existsByCommunityUsernameIgnoreCase("dhakafoodie");
    }

    @Test
    void isAvailableReflectsAllThreeChecks() {
        when(userRepository.existsByCommunityUsernameIgnoreCase("CoffeeHunter")).thenReturn(false);
        assertThat(service.isAvailable("CoffeeHunter")).isTrue();
        assertThat(service.isAvailable("a")).isFalse(); // too short
        assertThat(service.isAvailable("admin")).isFalse(); // reserved
    }

    @Test
    void suggestReturnsAnAvailableJachaiUserHandle() {
        when(userRepository.existsByCommunityUsernameIgnoreCase(anyString())).thenReturn(false);

        String suggestion = service.suggest();

        assertThat(suggestion).matches("JachaiUser\\d{4}");
    }

    @Test
    void suggestRetriesOnCollision() {
        when(userRepository.existsByCommunityUsernameIgnoreCase(anyString()))
                .thenReturn(true, true, false);

        String suggestion = service.suggest();

        assertThat(suggestion).matches("JachaiUser\\d{4}");
        verify(userRepository, atLeast(3)).existsByCommunityUsernameIgnoreCase(anyString());
    }

    @Test
    void resubmittingYourOwnCurrentUsernameIsANoOp() {
        User user = User.builder().id(userId).communityUsername("CoffeeHunter").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        String result = service.setUsername(userId, "CoffeeHunter", "M");

        assertThat(result).isEqualTo("CoffeeHunter");
        verify(userRepository, never()).existsByCommunityUsernameIgnoreCase(any());
    }

    // ----- V59: gender badge -----

    @Test
    void firstTimeSetupWithoutGenderIsRejected() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));

        assertThatThrownBy(() -> service.setUsername(userId, "UrbanExplorer42", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Male or Female");
        verify(userRepository, never()).save(any());
    }

    @Test
    void genderIsStoredAndNormalized() {
        when(userRepository.existsByCommunityUsernameIgnoreCase("NusratDhk")).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));

        service.setUsername(userId, "NusratDhk", "female");

        verify(userRepository).save(argThat(u -> "F".equals(u.getCommunityGender()) && u.isCommunityGenderVisible()));
    }

    @Test
    void invalidGenderIsRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "UrbanExplorer42", "X"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void renameKeepsAnExistingGenderWhenNoneIsSent() {
        User user = User.builder().id(userId).communityUsername("OldName").communityGender("F").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.existsByCommunityUsernameIgnoreCase("NewName")).thenReturn(false);

        service.setUsername(userId, "NewName", null);

        verify(userRepository).save(argThat(u -> "NewName".equals(u.getCommunityUsername()) && "F".equals(u.getCommunityGender())));
    }

    @Test
    void existingMemberCanPickGenderThenHideTheBadge() {
        User user = User.builder().id(userId).communityUsername("CoffeeHunter").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.updateGender(userId, null, false))
                .isInstanceOf(BadRequestException.class);

        User picked = service.updateGender(userId, "M", null);
        assertThat(picked.getCommunityGender()).isEqualTo("M");
        assertThat(picked.publicCommunityGender()).isEqualTo("M");

        User hidden = service.updateGender(userId, null, false);
        assertThat(hidden.getCommunityGender()).isEqualTo("M");
        assertThat(hidden.publicCommunityGender()).isNull();
    }
}
