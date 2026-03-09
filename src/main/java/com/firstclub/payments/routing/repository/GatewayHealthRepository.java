package com.firstclub.payments.routing.repository;

import com.firstclub.payments.routing.entity.GatewayHealth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GatewayHealthRepository extends JpaRepository<GatewayHealth, String> {

    Optional<GatewayHealth> findByGatewayName(String gatewayName);

    List<GatewayHealth> findAllByOrderByGatewayNameAsc();
}
