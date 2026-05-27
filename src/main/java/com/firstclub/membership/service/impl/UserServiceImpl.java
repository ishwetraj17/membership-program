package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Override
    public UserDTO createUser(UserDTO userDTO) {
        if (userRepository.existsByEmail(userDTO.getEmail())) {
            throw new MembershipException("Email already registered", "EMAIL_EXISTS");
        }
        User saved = userRepository.save(User.builder()
            .name(userDTO.getName())
            .email(userDTO.getEmail())
            .phoneNumber(userDTO.getPhoneNumber())
            .address(userDTO.getAddress())
            .city(userDTO.getCity())
            .state(userDTO.getState())
            .pincode(userDTO.getPincode())
            .status(User.UserStatus.ACTIVE)
            .build());
        log.info("User created — id={} email={}", saved.getId(), saved.getEmail());
        return convertToDTO(saved);
    }

    @Override
    public UserDTO updateUser(Long id, UserDTO userDTO) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> MembershipException.userNotFound(id));

        if (!user.getEmail().equals(userDTO.getEmail()) && userRepository.existsByEmail(userDTO.getEmail())) {
            throw new MembershipException("Email already registered", "EMAIL_EXISTS");
        }

        user.setName(userDTO.getName());
        user.setEmail(userDTO.getEmail());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setAddress(userDTO.getAddress());
        user.setCity(userDTO.getCity());
        user.setState(userDTO.getState());
        user.setPincode(userDTO.getPincode());
        if (userDTO.getStatus() != null) user.setStatus(userDTO.getStatus());

        return convertToDTO(userRepository.save(user));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserById(Long id) {
        return userRepository.findById(id).map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserByEmail(String email) {
        return userRepository.findByEmail(email).map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDTO> getAllUsers() {
        return userRepository.findAll().stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());
    }

    @Override
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> MembershipException.userNotFound(id));
        if (subscriptionRepository.hasActiveSubscriptions(user, LocalDateTime.now())) {
            throw new MembershipException(
                "Cancel all active subscriptions before deleting this account",
                "USER_HAS_ACTIVE_SUBSCRIPTIONS");
        }
        userRepository.delete(user);
        log.info("User deleted — id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public User findUserEntityById(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> MembershipException.userNotFound(id));
    }

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
