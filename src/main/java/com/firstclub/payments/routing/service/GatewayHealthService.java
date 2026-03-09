package com.firstclub.payments.routing.service;

import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.dto.GatewayHealthUpdateRequestDTO;

import java.util.List;

public interface GatewayHealthService {

    GatewayHealthResponseDTO getHealthSnapshot(String gatewayName);

    List<GatewayHealthResponseDTO> getAllHealthSnapshots();

    GatewayHealthResponseDTO updateGatewayHealth(String gatewayName, GatewayHealthUpdateRequestDTO request);
}
