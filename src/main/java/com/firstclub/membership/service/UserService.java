package com.firstclub.membership.service;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;

import java.util.List;
import java.util.Optional;

public interface UserService {
    UserDTO createUser(UserDTO userDTO);
    UserDTO updateUser(Long id, UserDTO userDTO);
    Optional<UserDTO> getUserById(Long id);
    Optional<UserDTO> getUserByEmail(String email);
    List<UserDTO> getAllUsers();
    void deleteUser(Long id);
    User findUserEntityById(Long id);
}
