package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User entity for storing customer information
 * 
 * Stores basic user details with Indian-specific validation.
 * Phone numbers follow Indian format (10 digits starting with 6-9).
 * 
 * Implemented by Shwet Raj
 */
@Entity
@Table(name = "users")
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