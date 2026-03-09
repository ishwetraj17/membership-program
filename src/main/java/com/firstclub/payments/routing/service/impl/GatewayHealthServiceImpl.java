package com.firstclub.payments.routing.service.impl;

import com.firstclub.payments.routing.cache.GatewayHealthCache;
import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.dto.GatewayHealthUpdateRequestDTO;
import com.firstclub.payments.routing.entity.GatewayHealth;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.payments.routing.repository.GatewayHealthRepository;
import com.firstclub.payments.routing.service.GatewayHealthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class GatewayHealthServiceImpl implements GatewayHealthService {

    private final GatewayHealthRepository healthRepository;
    private final GatewayHealthCache gatewayHealthCache;

    public GatewayHealthServiceImpl(GatewayHealthRepository healthRepository,
                                     GatewayHealthCache gatewayHealthCache) {
        this.healthRepository = healthRepository;
        this.gatewayHealthCache = gatewayHealthCache;
    }

    @Override
    @Transactional(readOnly = true)
    public GatewayHealthResponseDTO getHealthSnapshot(String gatewayName) {
        GatewayHealth health = healthRepository.findByGatewayName(gatewayName)
                .orElseThrow(() -> new RoutingException(
                        "Gateway '" + gatewayName + "' not found in health registry.",
                        "GATEWAY_NOT_FOUND",
                        org.springframework.http.HttpStatus.NOT_FOUND));
        return toResponse(health);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GatewayHealthResponseDTO> getAllHealthSnapshots() {
        return healthRepository.findAllByOrderByGatewayNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public GatewayHealthResponseDTO updateGatewayHealth(String gatewayName,
                                                        GatewayHealthUpdateRequestDTO request) {
        GatewayHealth health = healthRepository.findByGatewayName(gatewayName)
                .orElseGet(() -> {
                    GatewayHealth fresh = new GatewayHealth();
                    fresh.setGatewayName(gatewayName);
                    return fresh;
                });

        health.setStatus(request.getStatus());
        health.setLastCheckedAt(LocalDateTime.now());
        if (request.getRollingSuccessRate() != null) {
            health.setRollingSuccessRate(request.getRollingSuccessRate());
        }
        if (request.getRollingP95LatencyMs() != null) {
            health.setRollingP95LatencyMs(request.getRollingP95LatencyMs());
        }

        GatewayHealthResponseDTO dto = toResponse(healthRepository.save(health));
        // Refresh the Redis health cache so routing decisions pick up the new status immediately
        gatewayHealthCache.put(gatewayName, dto);
        return dto;
    }

    private GatewayHealthResponseDTO toResponse(GatewayHealth health) {
        GatewayHealthResponseDTO dto = new GatewayHealthResponseDTO();
        dto.setGatewayName(health.getGatewayName());
        dto.setStatus(health.getStatus());
        dto.setLastCheckedAt(health.getLastCheckedAt());
        dto.setRollingSuccessRate(health.getRollingSuccessRate());
        dto.setRollingP95LatencyMs(health.getRollingP95LatencyMs());
        return dto;
    }
}

