package com.firstclub.risk.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ip_blocklist")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IpBlocklist {

    /** The blocked IPv4 / IPv6 address. */
    @Id
    @Column(length = 64)
    private String ip;

    @Column(nullable = false, length = 255)
    private String reason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
