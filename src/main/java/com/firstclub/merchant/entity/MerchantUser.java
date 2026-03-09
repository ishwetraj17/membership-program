package com.firstclub.merchant.entity;

import com.firstclub.membership.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Maps a platform user to a merchant with a specific role.
 *
 * <p>One user may belong to multiple merchants (e.g. a contractor serving two
 * companies).  The {@code (merchant_id, user_id)} pair is unique — the same
 * user cannot be added to the same merchant twice.
 */
@Entity
@Table(name = "merchant_users", indexes = {
    @Index(name = "idx_merchant_users_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_merchant_users_user_id",     columnList = "user_id")
})
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false)
    @ToString.Exclude
    private MerchantAccount merchant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MerchantUserRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
