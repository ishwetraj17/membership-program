package com.firstclub.customer.entity;

import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.platform.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A paying/billable customer on the FirstClub platform.
 *
 * <p><strong>Customer ≠ User.</strong>
 * A {@link com.firstclub.membership.entity.User} is a platform operator or admin
 * who can log in and manage resources via the API.  A {@code Customer} is a
 * business/end-consumer identity that exists within a single merchant's (tenant's)
 * scope.  Customers do not have platform credentials.
 *
 * <p>Tenant isolation: every customer belongs to exactly one
 * {@link MerchantAccount}.  The {@code (merchant_id, email)} pair is unique —
 * the same email address may appear in different merchants' customer lists.
 *
 * <p>PII fields ({@code phone}, {@code billingAddress}, {@code shippingAddress})
 * are stored as AES-256-GCM ciphertext via {@link EncryptedStringConverter}.
 */
@Entity
@Table(
    name = "customers",
    indexes = {
        @Index(name = "idx_customers_merchant_email",  columnList = "merchant_id, email"),
        @Index(name = "idx_customers_merchant_status", columnList = "merchant_id, status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_customer_merchant_email",
                columnNames = {"merchant_id", "email"}),
        @UniqueConstraint(name = "uq_customer_merchant_external_id",
                columnNames = {"merchant_id", "external_customer_id"})
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning merchant (tenant).  Never null; immutable after creation. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    @ToString.Exclude
    private MerchantAccount merchant;

    /**
     * Optional external identifier (e.g. from the merchant's own CRM).
     * Unique within the merchant's scope; nullable.
     */
    @Column(name = "external_customer_id", length = 128)
    private String externalCustomerId;

    /** Email address.  Unique within the merchant; stored in lower-case form. */
    @Column(nullable = false)
    private String email;

    /** Phone number — stored as AES-256-GCM ciphertext. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 1024)
    private String phone;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Billing address — stored as AES-256-GCM ciphertext. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_address", columnDefinition = "TEXT")
    private String billingAddress;

    /** Shipping address — stored as AES-256-GCM ciphertext. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private CustomerStatus status = CustomerStatus.ACTIVE;

    /**
     * FK to future {@code payment_methods} table — stored as a plain Long for now.
     * A proper FK constraint will be added once the payment_methods table is created.
     */
    @Column(name = "default_payment_method_id")
    private Long defaultPaymentMethodId;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<CustomerNote> notes = new ArrayList<>();
}
