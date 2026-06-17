package com.firstclub.membership.dto;

import com.firstclub.membership.entity.UserTierAssignment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A user's current <em>earned</em> tier (from order activity), as opposed to the tier
 * they have purchased via a subscription.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTierAssignmentDTO {

    private Long userId;
    private String earnedTierName;
    private Integer earnedTierLevel;
    private UserTierAssignment.Source source;
    private LocalDateTime evaluatedAt;
    private String evaluationNote;
}
