package com.codepilot.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the CodePilot orchestrator service.
 *
 * Start with:
 *   cd orchestrator
 *   docker compose up -d   # start PostgreSQL
 *   mvn spring-boot:run    # start the orchestrator on port 8080
 *
 * Requires the ANTHROPIC_API_KEY environment variable to be set before
 * the Claude agent loop is exercised (§5.1).
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
