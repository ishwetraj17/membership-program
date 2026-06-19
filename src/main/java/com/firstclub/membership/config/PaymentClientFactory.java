package com.firstclub.membership.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Extension point that builds a timeout-hardened HTTP client for a future PSP adapter. A real
 * adapter (Stripe/Razorpay/Cashfree/Juspay/…) injects this factory and calls
 * {@link #newRestClientBuilder()} to obtain a {@link RestClient.Builder} pre-wired with the
 * configured connect/read timeouts, then layers on its own base URL, auth, and serialization.
 *
 * <p>This keeps timeout policy in one place and PSP-agnostic — no provider is referenced. It is a
 * factory, not an autoconfigured client, so it never interferes with the application's default
 * {@code RestClient.Builder}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentClientFactory {

    private final PaymentClientProperties properties;

    /**
     * A fresh {@link RestClient.Builder} with the connection and read timeouts applied. The write
     * timeout is exposed via {@link PaymentClientProperties} for adapters whose client supports a
     * distinct one; the JDK client used here bounds the connect phase and the read phase.
     */
    public RestClient.Builder newRestClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        log.debug("PSP HTTP client configured — connect={} read={} write={}",
                properties.getConnectTimeout(), properties.getReadTimeout(), properties.getWriteTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
