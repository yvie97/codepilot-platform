package com.codepilot.orchestrator;

import com.codepilot.orchestrator.claude.ClaudeClient;
import com.codepilot.orchestrator.claude.ClaudeClient.Message;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    /**
     * Step 1 smoke test: call Claude once and print the reply.
     *
     * This is a CommandLineRunner — Spring runs it once at startup, then the
     * application keeps running as a web server. We'll remove this runner once
     * the real agent loop is wired in.
     *
     * To run:
     *   ANTHROPIC_API_KEY=sk-ant-... mvn spring-boot:run
     */
    @Bean
    CommandLineRunner smokeTest(ClaudeClient claude) {
        return args -> {
            System.out.println("=== CodePilot Step 1: Claude API smoke test ===");

            // This is exactly the agent loop from §5.4 — one turn:
            //   1. Build a conversation with a single user message
            //   2. Send to Claude, get a response
            //   3. Print it (later we'll parse it as a code action)
            String reply = claude.complete(
                    "claude-sonnet-4-6",
                    List.of(new Message("user", "Say 'CodePilot is alive!' and nothing else."))
            );

            System.out.println("Claude says: " + reply);
            System.out.println("=== Smoke test complete ===");
        };
    }
}
