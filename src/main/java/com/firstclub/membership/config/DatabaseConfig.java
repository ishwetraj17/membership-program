package com.firstclub.membership.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Primary;

/**
 * Database configuration class
 * 
 * Sets up H2 in-memory database for demo purposes.
 * In production, this would be switched to PostgreSQL or MySQL.
 * 
 * Implemented by Shwet Raj
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.firstclub.membership.repository")
@EnableJpaAuditing
public class DatabaseConfig {

    /**
     * DataSource configuration for H2 database
     * 
     * Using H2 for now since it's easier for demo and testing.
     * TODO: Switch to PostgreSQL for production deployment
     */
    @Bean
    @Primary
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url("jdbc:h2:mem:membershipdb")
                .username("sa")
                .password("password")
                .build();
    }
}