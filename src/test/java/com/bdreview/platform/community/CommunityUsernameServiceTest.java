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

        String result = service.setUsername(userId, "UrbanExplorer42");

        assertThat(result).isEqualTo("UrbanExplorer42");
        verify(userRepository).save(argThat(u -> "UrbanExplorer42".equals(u.getCommunityUsername())));
    }

    @Test
    void tooShortIsRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "ab"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void spacesAreRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "urban explorer"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void punctuationOtherThanUnderscoreIsRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "urban-explorer!"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void reservedNameIsRejected() {
        assertThatThrownBy(() -> service.setUsername(userId, "admin"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setUsername(userId, "Jachai"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void alreadyTakenUsernameIsRejected() {
        when(userRepository.existsByCommunityUsernameIgnoreCase("DhakaFoodie")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));

        assertThatThrownBy(() -> service.setUsername(userId, "DhakaFoodie"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void uniquenessCheckIsCaseInsensitive() {
        // "already taken" is decided purely via existsByCommunityUsernameIgnoreCase, which the
        // DB backs with a functional index on LOWER(community_username) — this test just pins
        // that the service asks the case-insensitive question, not a case-sensitive one.
        when(userRepository.existsByCommunityUsernameIgnoreCase("dhakafoodie")).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));

        assertThatThrownBy(() -> service.setUsername(userId, "dhakafoodie"))
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

        String result = service.setUsername(userId, "CoffeeHunter");

        assertThat(result).isEqualTo("CoffeeHunter");
        verify(userRepository, never()).existsByCommunityUsernameIgnoreCase(any());
    }
}
