package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User entity for storing customer information
 * 
 * Stores basic user details with Indian-specific validation.
 * Phone numbers follow Indian format (10 digits starting with 6-9).
 * 
 * Implemented by Shwet Raj
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_email", columnList = "email")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    // BCrypt-encoded — never stored or returned in plain text
    @Column(nullable = false)
    private String password;

    // Roles: ROLE_USER, ROLE_ADMIN
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Builder.Default
    private Set<String> roles = new HashSet<>();

    @Column(nullable = false)
    private String name;

    // Indian phone number format - 10 digits starting with 6-9
    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    // Indian pincode - 6 digits
    @Column(nullable = false)
    private String pincode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    // Soft delete flag - users are never physically removed from the database
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    // One user can have multiple subscriptions over time
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Subscription> subscriptions;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * User account status
     * ACTIVE - can use all features
     * INACTIVE - temporarily disabled
     * SUSPENDED - blocked due to violations
     */
    public enum UserStatus {
        ACTIVE,
        INACTIVE,
        SUSPENDED
    }
}