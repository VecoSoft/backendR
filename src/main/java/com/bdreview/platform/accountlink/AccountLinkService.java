package com.bdreview.platform.accountlink;

import com.bdreview.platform.common.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AccountLinkService {

    private final AccountLinkRepository accountLinkRepository;

    public AccountLinkService(AccountLinkRepository accountLinkRepository) {
        this.accountLinkRepository = accountLinkRepository;
    }

    @Transactional
    public void link(UUID consumerUserId, UUID businessUserId) {
        if (accountLinkRepository.existsByConsumerUserId(consumerUserId)) {
            throw new BadRequestException("This personal account is already linked to a business account.");
        }
        if (accountLinkRepository.existsByBusinessUserId(businessUserId)) {
            throw new BadRequestException("This business account is already linked to a personal account.");
        }
        accountLinkRepository.save(AccountLink.builder()
                .consumerUserId(consumerUserId)
                .businessUserId(businessUserId)
                .build());
    }

    /** The linked counterpart of {@code userId}, whichever side it's on — empty if unlinked. */
    public Optional<UUID> partnerOf(UUID userId) {
        Optional<AccountLink> asConsumer = accountLinkRepository.findByConsumerUserId(userId);
        if (asConsumer.isPresent()) {
            return asConsumer.map(AccountLink::getBusinessUserId);
        }
        return accountLinkRepository.findByBusinessUserId(userId).map(AccountLink::getConsumerUserId);
    }

    public boolean isLinked(UUID userId) {
        return accountLinkRepository.existsByConsumerUserId(userId) || accountLinkRepository.existsByBusinessUserId(userId);
    }
}
