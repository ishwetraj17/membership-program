package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.mapper.UserMapper;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of UserService
 *
 * Handles user CRUD operations with validation.
 * Includes email uniqueness check, soft delete, and BCrypt password encoding.
 *
 * Implemented by Shwet Raj
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserDTO createUser(UserDTO userDTO) {
        log.info("Creating new user with email: {}", userDTO.getEmail());
        
        if (userRepository.existsByEmailAndIsDeletedFalse(userDTO.getEmail())) {
            throw new MembershipException("Email already exists", "EMAIL_EXISTS");
        }

        User user = userMapper.toEntity(userDTO);
        user.setStatus(User.UserStatus.ACTIVE);
        user.setIsDeleted(false);
        user.setRoles(Set.of("ROLE_USER"));

        // Encode password — default to a placeholder if not provided (e.g. for seed data callers)
        String rawPassword = userDTO.getPassword() != null ? userDTO.getPassword() : "ChangeMe@1234";
        user.setPassword(passwordEncoder.encode(rawPassword));
            
        User savedUser = userRepository.save(user);
        log.info("User created successfully with ID: {}", savedUser.getId());
        return userMapper.toDTO(savedUser);
    }

    @Override
    public UserDTO createAdminUser(UserDTO userDTO) {
        if (userRepository.existsByEmailAndIsDeletedFalse(userDTO.getEmail())) {
            return getUserByEmail(userDTO.getEmail()).orElseThrow();
        }
        User user = userMapper.toEntity(userDTO);
        user.setStatus(User.UserStatus.ACTIVE);
        user.setIsDeleted(false);
        user.setRoles(Set.of("ROLE_USER", "ROLE_ADMIN"));
        String rawPassword = userDTO.getPassword() != null ? userDTO.getPassword() : "ChangeMe@1234";
        user.setPassword(passwordEncoder.encode(rawPassword));
        User saved = userRepository.save(user);
        log.info("Admin user created with ID: {}", saved.getId());
        return userMapper.toDTO(saved);
    }

    @Override
    public UserDTO updateUser(Long id, UserDTO userDTO) {
        log.info("Updating user with ID: {}", id);
        
        User user = userRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new MembershipException("User not found", "USER_NOT_FOUND"));
            
        if (!user.getEmail().equals(userDTO.getEmail()) &&
            userRepository.existsByEmailAndIsDeletedFalse(userDTO.getEmail())) {
            throw new MembershipException("Email already exists", "EMAIL_EXISTS");
        }

        userMapper.updateEntityFromDTO(userDTO, user);

        if (userDTO.getStatus() != null) {
            user.setStatus(userDTO.getStatus());
        }

        // Re-encode password only if caller explicitly provides one
        if (userDTO.getPassword() != null && !userDTO.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(userDTO.getPassword()));
        }
        
        User updatedUser = userRepository.save(user);
        log.info("User updated successfully: {}", updatedUser.getId());
        return userMapper.toDTO(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserById(Long id) {
        log.debug("Fetching user by ID: {}", id);
        return userRepository.findByIdAndIsDeletedFalse(id).map(userMapper::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserByEmail(String email) {
        log.debug("Fetching user by email: {}", email);
        return userRepository.findByEmailAndIsDeletedFalse(email).map(userMapper::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDTO> getAllUsers() {
        log.debug("Fetching all users");
        return userRepository.findAll().stream()
            .map(userMapper::toDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserDTO> getAllUsersPaged(Pageable pageable) {
        log.debug("Fetching users - page: {}, size: {}", pageable.getPageNumber(), pageable.getPageSize());
        return userRepository.findByIsDeletedFalse(pageable).map(userMapper::toDTO);
    }

    @Override
    public void deleteUser(Long id) {
        log.info("Soft-deleting user with ID: {}", id);
        
        User user = userRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new MembershipException("User not found", "USER_NOT_FOUND"));
            
        if (subscriptionRepository.hasActiveSubscriptions(user, LocalDateTime.now())) {
            throw new MembershipException(
                "Cannot delete user with active subscriptions. Please cancel all active subscriptions first.",
                "USER_HAS_ACTIVE_SUBSCRIPTIONS"
            );
        }
        
        user.setIsDeleted(true);
        userRepository.save(user);
        log.info("User soft-deleted successfully: {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public User findUserEntityById(Long id) {
        return userRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new MembershipException("User not found", "USER_NOT_FOUND"));
    }
}
