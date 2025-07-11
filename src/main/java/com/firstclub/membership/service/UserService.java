package com.firstclub.membership.service;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for user management operations
 * 
 * Defines all user-related business operations.
 * Implemented by UserServiceImpl.
 * 
 * Implemented by Shwet Raj
 */
public interface UserService {

    /**
     * Create a new user account
     * 
     * @param userDTO user information
     * @return created user details
     */
    UserDTO createUser(UserDTO userDTO);

    /**
     * Update existing user information
     * 
     * @param id user ID
     * @param userDTO updated user information
     * @return updated user details
     */
    UserDTO updateUser(Long id, UserDTO userDTO);

    /**
     * Get user by ID
     * 
     * @param id user ID
     * @return user details if found
     */
    Optional<UserDTO> getUserById(Long id);

    /**
     * Get user by email address
     * 
     * @param email user email
     * @return user details if found
     */
    Optional<UserDTO> getUserByEmail(String email);

    /**
     * Get all users
     * 
     * @return list of all users
     */
    List<UserDTO> getAllUsers();

    /**
     * Delete user account
     * 
     * @param id user ID to delete
     */
    void deleteUser(Long id);

    /**
     * Find user entity by ID (for internal use)
     * 
     * @param id user ID
     * @return user entity
     */
    User findUserEntityById(Long id);
}