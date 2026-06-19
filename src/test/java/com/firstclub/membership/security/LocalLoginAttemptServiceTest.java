package com.firstclub.membership.security;

import com.firstclub.membership.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Local (in-process) login lockout")
class LocalLoginAttemptServiceTest {

    private LocalLoginAttemptService service(int maxAttempts) {
        SecurityProperties props = new SecurityProperties();
        props.getLockout().setMaxAttempts(maxAttempts);
        props.getLockout().setWindowMinutes(15);
        return new LocalLoginAttemptService(props);
    }

    @Test @DisplayName("locks the account once the failure threshold is reached")
    void locksAtThreshold() {
        LocalLoginAttemptService service = service(3);
        assertThat(service.isLockedOut("bob")).isFalse();
        service.recordFailure("bob");
        service.recordFailure("bob");
        assertThat(service.isLockedOut("bob")).isFalse(); // 2 < 3
        service.recordFailure("bob");
        assertThat(service.isLockedOut("bob")).isTrue();   // 3 >= 3
    }

    @Test @DisplayName("a successful login clears the failure counter")
    void successResets() {
        LocalLoginAttemptService service = service(3);
        service.recordFailure("bob");
        service.recordFailure("bob");
        service.recordFailure("bob");
        assertThat(service.isLockedOut("bob")).isTrue();

        service.recordSuccess("bob");
        assertThat(service.isLockedOut("bob")).isFalse();
    }

    @Test @DisplayName("lockout is tracked per username")
    void perUsername() {
        LocalLoginAttemptService service = service(2);
        service.recordFailure("bob");
        service.recordFailure("bob");
        assertThat(service.isLockedOut("bob")).isTrue();
        assertThat(service.isLockedOut("alice")).isFalse();
    }
}
