package com.firstclub.membership.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * HTTP timeouts for the (future) PSP adapter's outbound calls. A real payment integration must never
 * block a request thread indefinitely on a slow provider, so every timeout has a production-safe
 * default and is overridable via {@code payment.client.*}.
 *
 * <ul>
 *   <li>{@code connectTimeout} — cap on establishing the TCP/TLS connection.</li>
 *   <li>{@code readTimeout} — cap on waiting for the response after the request is sent.</li>
 *   <li>{@code writeTimeout} — cap on streaming the request body (honoured by adapters whose client
 *       supports a distinct write timeout, e.g. Reactor Netty).</li>
 * </ul>
 *
 * These feed {@link com.firstclub.membership.config.PaymentClientFactory}; no provider is bound here.
 */
@ConfigurationProperties(prefix = "payment.client")
@Data
public class PaymentClientProperties {

    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration writeTimeout = Duration.ofSeconds(5);
}
