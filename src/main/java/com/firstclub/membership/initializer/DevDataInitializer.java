package com.firstclub.membership.initializer;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds demo users in the dev profile only.
 *
 * This component is never instantiated in production because it is
 * guarded by {@code @Profile("dev")}. The data it creates exists purely
 * to make the Swagger demo flow work out of the box.
 */
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class DevDataInitializer implements ApplicationRunner {

    private final UserService userService;

    @Override
    public void run(ApplicationArguments args) {
        if (!userService.getAllUsers().isEmpty()) {
            log.debug("Dev users already present — skipping demo data.");
            return;
        }

        List<UserDTO> sampleUsers = List.of(
            UserDTO.builder()
                .name("Karan Singh")
                .email("karan.singh@example.com")
                .phoneNumber("9876543210")
                .address("12 HSR Layout")
                .city("Bangalore")
                .state("Karnataka")
                .pincode("560102")
                .build(),
            UserDTO.builder()
                .name("Ananya Sharma")
                .email("ananya.sharma@example.com")
                .phoneNumber("9876543211")
                .address("23 Andheri West")
                .city("Mumbai")
                .state("Maharashtra")
                .pincode("400058")
                .build(),
            UserDTO.builder()
                .name("Rohit Agarwal")
                .email("rohit.agarwal@example.com")
                .phoneNumber("9876543212")
                .address("45 Connaught Place")
                .city("New Delhi")
                .state("Delhi")
                .pincode("110001")
                .build()
        );

        sampleUsers.forEach(dto -> {
            try {
                userService.createUser(dto);
            } catch (Exception e) {
                log.warn("Could not create demo user '{}': {}", dto.getEmail(), e.getMessage());
            }
        });

        log.info("Demo users created — ready for Swagger walkthrough.");
    }
}
