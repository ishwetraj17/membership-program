package com.firstclub.events.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class ReplayReportDTO {
    LocalDateTime   from;
    LocalDateTime   to;
    String          mode;
    int             eventsScanned;
    boolean         valid;
    List<String>    findings;
}
