package com.firstclub.support.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * An immutable audit-trail note attached to a {@link SupportCase}.
 *
 * <p>Notes are written by platform operators and cannot be modified
 * after creation.  Visibility controls whether the note surfaces only
 * to internal ops staff ({@code INTERNAL_ONLY}) or also to the merchant
 * ({@code MERCHANT_VISIBLE}).
 */
@Entity
@Table(
    name = "support_notes",
    indexes = {
        @Index(name = "idx_sn_case_id", columnList = "case_id"),
        @Index(name = "idx_sn_author",  columnList = "author_user_id")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Parent support case. */
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "note_text", nullable = false, columnDefinition = "TEXT")
    private String noteText;

    /** Platform operator/admin who authored this note.  Immutable after creation. */
    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private SupportNoteVisibility visibility = SupportNoteVisibility.INTERNAL_ONLY;

    /** Notes are immutable — no updated_at column. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
