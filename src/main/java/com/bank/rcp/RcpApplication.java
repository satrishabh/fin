package com.bank.rcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Regulatory Control Plane (POC).
 *
 * <p>One deployable that hosts the deterministic core services, the platform services
 * (tool gateway, model gateway, A2A registry) and the advisory agent layer. Each
 * package maps to a microservice in the specification so it can be split out later.
 */
@SpringBootApplication
@EnableScheduling
public class RcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(RcpApplication.class, args);
    }
}
