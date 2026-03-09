package com.firstclub.support.entity;

/**
 * Visibility of a {@link SupportNote}: controls who can read the note.
 */
public enum SupportNoteVisibility {
    /** Visible only to internal platform ops staff. */
    INTERNAL_ONLY,
    /** Also surfaced to the merchant's operators (if the portal exposes this). */
    MERCHANT_VISIBLE
}
