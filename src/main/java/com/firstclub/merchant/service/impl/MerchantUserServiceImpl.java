package com.firstclub.merchant.service.impl;

import com.firstclub.membership.entity.User;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.merchant.dto.MerchantUserCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantUserResponseDTO;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantUser;
import com.firstclub.merchant.entity.MerchantUserRole;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.mapper.MerchantUserMapper;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.merchant.repository.MerchantUserRepository;
import com.firstclub.merchant.service.MerchantUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link MerchantUserService}.
 *
 * Business rules:
 * - A user cannot be assigned to the same merchant twice
 * - The last OWNER of a merchant cannot be removed
 *
 * Implemented by Shwet Raj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantUserServiceImpl implements MerchantUserService {

    private final MerchantAccountRepository merchantAccountRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final UserRepository userRepository;
    private final MerchantUserMapper merchantUserMapper;

    @Override
    @Transactional
    public MerchantUserResponseDTO addUserToMerchant(Long merchantId, MerchantUserCreateRequestDTO request) {
        log.info("Adding user {} to merchant {} with role {}", request.getUserId(), merchantId, request.getRole());

        MerchantAccount merchant = merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> MerchantException.userNotFound(request.getUserId()));

        if (merchantUserRepository.existsByMerchantIdAndUserId(merchantId, request.getUserId())) {
            throw MerchantException.duplicateUserAssignment(merchantId, request.getUserId());
        }

        MerchantUser merchantUser = MerchantUser.builder()
                .merchant(merchant)
                .user(user)
                .role(request.getRole())
                .build();

        MerchantUser saved = merchantUserRepository.save(merchantUser);
        log.info("User {} added to merchant {} as {}", request.getUserId(), merchantId, request.getRole());
        return merchantUserMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MerchantUserResponseDTO> listMerchantUsers(Long merchantId) {
        if (!merchantAccountRepository.existsById(merchantId)) {
            throw MerchantException.merchantNotFound(merchantId);
        }
        return merchantUserRepository.findByMerchantId(merchantId)
                .stream()
                .map(merchantUserMapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void removeUserFromMerchant(Long merchantId, Long userId) {
        log.info("Removing user {} from merchant {}", userId, merchantId);

        MerchantUser merchantUser = merchantUserRepository
                .findByMerchantIdAndUserId(merchantId, userId)
                .orElseThrow(() -> MerchantException.userNotInMerchant(merchantId, userId));

        // Prevent removal of the last OWNER
        if (merchantUser.getRole() == MerchantUserRole.OWNER) {
            long ownerCount = merchantUserRepository.countByMerchantIdAndRole(merchantId, MerchantUserRole.OWNER);
            if (ownerCount <= 1) {
                throw MerchantException.lastOwnerRemoval();
            }
        }

        merchantUserRepository.deleteByMerchantIdAndUserId(merchantId, userId);
        log.info("User {} removed from merchant {}", userId, merchantId);
    }
}
