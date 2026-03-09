package com.firstclub.payments.routing.service;

import com.firstclub.payments.routing.cache.GatewayHealthCache;
import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.dto.GatewayHealthUpdateRequestDTO;
import com.firstclub.payments.routing.entity.GatewayHealth;
import com.firstclub.payments.routing.entity.GatewayHealthStatus;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.payments.routing.repository.GatewayHealthRepository;
import com.firstclub.payments.routing.service.impl.GatewayHealthServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayHealthService Unit Tests")
class GatewayHealthServiceTest {

    @Mock  private GatewayHealthRepository healthRepository;
    @Mock  private GatewayHealthCache gatewayHealthCache;
    @InjectMocks private GatewayHealthServiceImpl service;

    private GatewayHealth gatewayHealth(String name, GatewayHealthStatus status) {
        GatewayHealth h = new GatewayHealth();
        h.setGatewayName(name);
        h.setStatus(status);
        h.setLastCheckedAt(LocalDateTime.now());
        h.setRollingSuccessRate(BigDecimal.valueOf(99.5));
        h.setRollingP95LatencyMs(120L);
        return h;
    }

    // ── getHealthSnapshot ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getHealthSnapshot")
    class GetHealthSnapshot {

        @Test
        @DisplayName("returns DTO when gateway exists")
        void foundReturnsDTO() {
            when(healthRepository.findByGatewayName("razorpay"))
                    .thenReturn(Optional.of(gatewayHealth("razorpay", GatewayHealthStatus.HEALTHY)));

            GatewayHealthResponseDTO dto = service.getHealthSnapshot("razorpay");

            assertThat(dto.getGatewayName()).isEqualTo("razorpay");
            assertThat(dto.getStatus()).isEqualTo(GatewayHealthStatus.HEALTHY);
        }

        @Test
        @DisplayName("throws 404 RoutingException when gateway not found")
        void notFoundThrows() {
            when(healthRepository.findByGatewayName("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getHealthSnapshot("unknown"))
                    .isInstanceOf(RoutingException.class)
                    .satisfies(t -> {
                        RoutingException re = (RoutingException) t;
                        assertThat(re.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(re.getErrorCode()).isEqualTo("GATEWAY_NOT_FOUND");
                    });
        }
    }

    // ── getAllHealthSnapshots ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllHealthSnapshots")
    class GetAllHealthSnapshots {

        @Test
        @DisplayName("returns list ordered by gateway name")
        void returnsAll() {
            when(healthRepository.findAllByOrderByGatewayNameAsc()).thenReturn(List.of(
                    gatewayHealth("payu",     GatewayHealthStatus.HEALTHY),
                    gatewayHealth("razorpay", GatewayHealthStatus.DEGRADED),
                    gatewayHealth("stripe",   GatewayHealthStatus.DOWN)
            ));

            List<GatewayHealthResponseDTO> result = service.getAllHealthSnapshots();

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getGatewayName()).isEqualTo("payu");
        }
    }

    // ── updateGatewayHealth ───────────────────────────────────────────────────

    @Nested
    @DisplayName("updateGatewayHealth")
    class UpdateGatewayHealth {

        @Test
        @DisplayName("updates existing gateway health record")
        void updatesExisting() {
            GatewayHealth existing = gatewayHealth("razorpay", GatewayHealthStatus.HEALTHY);
            when(healthRepository.findByGatewayName("razorpay")).thenReturn(Optional.of(existing));
            when(healthRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            GatewayHealthUpdateRequestDTO req = new GatewayHealthUpdateRequestDTO();
            req.setStatus(GatewayHealthStatus.DOWN);
            req.setRollingSuccessRate(BigDecimal.valueOf(55.0));
            req.setRollingP95LatencyMs(2000L);

            GatewayHealthResponseDTO result = service.updateGatewayHealth("razorpay", req);

            assertThat(result.getStatus()).isEqualTo(GatewayHealthStatus.DOWN);
            assertThat(result.getRollingSuccessRate()).isEqualByComparingTo("55.0");
        }

        @Test
        @DisplayName("creates new gateway health record (upsert) when gateway not found")
        void createsWhenNotFound() {
            when(healthRepository.findByGatewayName("newgw")).thenReturn(Optional.empty());
            when(healthRepository.save(any())).thenAnswer(i -> {
                GatewayHealth h = i.getArgument(0);
                h.setLastCheckedAt(LocalDateTime.now());
                return h;
            });

            GatewayHealthUpdateRequestDTO req = new GatewayHealthUpdateRequestDTO();
            req.setStatus(GatewayHealthStatus.HEALTHY);
            req.setRollingSuccessRate(BigDecimal.valueOf(100.0));
            req.setRollingP95LatencyMs(50L);

            GatewayHealthResponseDTO result = service.updateGatewayHealth("newgw", req);

            assertThat(result.getGatewayName()).isEqualTo("newgw");
            assertThat(result.getStatus()).isEqualTo(GatewayHealthStatus.HEALTHY);
            verify(healthRepository).save(argThat(h -> "newgw".equals(h.getGatewayName())));
        }
    }
}
