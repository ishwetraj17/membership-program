package com.firstclub.customer.entity;

import com.firstclub.membership.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * An immutable audit-trail note attached to a {@link Customer}.
 *
 * <p>Notes are written by platform operators or admins ({@link User}) and
 * cannot be modified after creation.  The {@code visibility} field determines
 * whether the note is visible only to platform staff or also to the owning
 * merchant's operators.
 *
 * <p>Notes are logically soft-hidden by toggling visibility to
 * {@code INTERNAL_ONLY} — records are never hard-deleted.
 */
@Entity
@Table(
    name = "customer_notes",
    indexes = {
        @Index(name = "idx_customer_notes_customer_id", columnList = "customer_id")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Customer this note belongs to.  Immutable after creation. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    @ToString.Exclude
    private Customer customer;

    /** Platform operator/admin who authored this note.  Immutable after creation. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", nullable = false)
    @ToString.Exclude
    private User author;

    @Column(name = "note_text", nullable = false, columnDefinition = "TEXT")
    private String noteText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private CustomerNoteVisibility visibility = CustomerNoteVisibility.INTERNAL_ONLY;

    /** Notes are immutable — no updated_at column. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
