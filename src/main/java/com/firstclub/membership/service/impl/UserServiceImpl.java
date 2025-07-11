package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of UserService
 * 
 * Handles user CRUD operations with validation.
 * Includes email uniqueness check and proper error handling.
 * 
 * Implemented by Shwet Raj
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public UserDTO createUser(UserDTO userDTO) {
        log.info("Creating new user with email: {}", userDTO.getEmail());
        
        // Check if email already exists
        if (userRepository.existsByEmail(userDTO.getEmail())) {
            throw new MembershipException("Email already exists", "EMAIL_EXISTS");
        }
        
        // Convert DTO to entity
        User user = User.builder()
            .name(userDTO.getName())
            .email(userDTO.getEmail())
            .phoneNumber(userDTO.getPhoneNumber())
            .address(userDTO.getAddress())
            .city(userDTO.getCity())
            .state(userDTO.getState())
            .pincode(userDTO.getPincode())
            .status(User.UserStatus.ACTIVE) // New users are active by default
            .build();
            
        User savedUser = userRepository.save(user);
        log.info("User created successfully with ID: {}", savedUser.getId());
        
        return convertToDTO(savedUser);
    }

    @Override
    public UserDTO updateUser(Long id, UserDTO userDTO) {
        log.info("Updating user with ID: {}", id);
        
        User user = userRepository.findById(id)
            .orElseThrow(() -> new MembershipException("User not found", "USER_NOT_FOUND"));
            
        // Check if email is being changed and if new email already exists
        if (!user.getEmail().equals(userDTO.getEmail()) && 
            userRepository.existsByEmail(userDTO.getEmail())) {
            throw new MembershipException("Email already exists", "EMAIL_EXISTS");
        }
        
        // Update user fields
        user.setName(userDTO.getName());
        user.setEmail(userDTO.getEmail());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setAddress(userDTO.getAddress());
        user.setCity(userDTO.getCity());
        user.setState(userDTO.getState());
        user.setPincode(userDTO.getPincode());
        
        if (userDTO.getStatus() != null) {
            user.setStatus(userDTO.getStatus());
        }
        
        User updatedUser = userRepository.save(user);
        log.info("User updated successfully: {}", updatedUser.getId());
        
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserById(Long id) {
        log.debug("Fetching user by ID: {}", id);
        
        return userRepository.findById(id).map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserByEmail(String email) {
        log.debug("Fetching user by email: {}", email);
        
        return userRepository.findByEmail(email).map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDTO> getAllUsers() {
        log.debug("Fetching all users");
        
        return userRepository.findAll().stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());
    }

    @Override
    public void deleteUser(Long id) {
        log.info("Deleting user with ID: {}", id);
        
        User user = userRepository.findById(id)
            .orElseThrow(() -> new MembershipException("User not found", "USER_NOT_FOUND"));
            
        // TODO: Check if user has active subscriptions before deletion
        // For now, we'll just delete - but this might cause issues
        
        userRepository.delete(user);
        log.info("User deleted successfully: {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public User findUserEntityById(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new MembershipException("User not found", "USER_NOT_FOUND"));
    }

    /**
     * Convert User entity to DTO
     * 
     * Helper method to transform entity to DTO for API responses.
     * NOTE: This works but could be optimized with MapStruct later
     */
    private UserDTO convertToDTO(User user) {
        return UserDTO.builder()
            .id(user.getId())
            .name(user.getName())
            .email(user.getEmail())
            .phoneNumber(user.getPhoneNumber())
            .address(user.getAddress())
            .city(user.getCity())
            .state(user.getState())
            .pincode(user.getPincode())
            .status(user.getStatus())
            .build();
    }
}