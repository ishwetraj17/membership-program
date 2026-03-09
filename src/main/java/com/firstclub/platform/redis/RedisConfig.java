package com.firstclub.platform.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Redis bean configuration.
 *
 * <p>This entire class is conditional on {@code app.redis.enabled=true}.
 * When the property is absent or {@code false}, no Redis beans are created
 * and the application starts normally with the DB as sole source of truth.
 *
 * <h3>Why we exclude Spring's RedisAutoConfiguration</h3>
 * <p>Spring Boot's auto-configuration creates a {@code LettuceConnectionFactory}
 * eagerly from {@code spring.data.redis.*} properties, which would cause a
 * connection error at startup when Redis is not running.  Instead, we take
 * full ownership here: {@code application.properties} excludes
 * {@code RedisAutoConfiguration} and {@code RedisRepositoriesAutoConfiguration},
 * and this class conditionally creates all Redis beans from our own
 * {@code app.redis.*} namespace.
 *
 * <h3>Beans produced (when enabled)</h3>
 * <ul>
 *   <li>{@code redisConnectionFactory} — Lettuce single-node connection factory</li>
 *   <li>{@code stringRedisTemplate} — for simple string-valued keys (counters, flags, locks)</li>
 *   <li>{@code jsonRedisTemplate} — for complex domain objects serialised as JSON</li>
 * </ul>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
@EnableConfigurationProperties({RedisProperties.class, RedisTtlConfig.class})
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties props) {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(props.getHost());
        serverConfig.setPort(props.getPort());
        serverConfig.setDatabase(props.getDatabase());

        if (StringUtils.hasText(props.getPassword())) {
            serverConfig.setPassword(RedisPassword.of(props.getPassword()));
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();

        ClientOptions.Builder clientOptionsBuilder = ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(props.getCommandTimeoutMs())));

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfigBuilder =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(props.getCommandTimeoutMs()));

        if (props.isSsl()) {
            clientConfigBuilder.useSsl();
        }

        clientConfigBuilder.clientOptions(clientOptionsBuilder.build());

        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfigBuilder.build());
        log.info("Redis connection factory created → {}:{} db={} ssl={}",
                props.getHost(), props.getPort(), props.getDatabase(), props.isSsl());
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * A Redis template that serialises values as JSON using our shared {@link ObjectMapper}.
     * Use this template for complex domain objects; use {@code StringRedisTemplate}
     * for primitive string values (counters, flags, simple markers).
     */
    @Bean("jsonRedisTemplate")
    public RedisTemplate<String, Object> jsonRedisTemplate(
            LettuceConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        Jackson2JsonRedisSerializer<Object> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
