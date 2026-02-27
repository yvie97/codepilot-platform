package com.codepilot.orchestrator.agent;

import com.codepilot.orchestrator.claude.ClaudeClient;
import com.codepilot.orchestrator.claude.ClaudeClient.Message;
import com.codepilot.orchestrator.executor.dto.ExecutionResult;
import com.codepilot.orchestrator.executor.WorkspaceClient;
import com.codepilot.orchestrator.model.AgentRole;
import com.codepilot.orchestrator.model.Step;
import com.codepilot.orchestrator.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The CodeAct agent loop (§5.1 + §5.4).
 *
 * For a given Step, this class:
 *   1. Builds a system prompt based on the agent's role
 *   2. Feeds prior agents' results as context
 *   3. Runs a multi-turn conversation with Claude:
 *        → Claude emits a Python code block
 *        → We run it in the sandbox, return the observation
 *        → Claude reads the observation and decides what to do next
 *        → Repeat until Claude writes <result>...</result>
 *   4. Saves the result and advances the job to the next stage
 *
 * This loop is what makes CodePilot an "agent" rather than a
 * simple pipeline: Claude can self-correct based on real execution
 * output (compile errors, test failures, wrong file contents, etc.)
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    // Maximum turns per step to prevent infinite loops.
    // At ~$0.01/turn this caps per-step cost at ~$0.20.
    private static final int MAX_TURNS = 20;

    // How often to update the heartbeat (every N turns).
    private static final int HEARTBEAT_EVERY = 3;

    private static final String MODEL = "claude-sonnet-4-6";

    private final ClaudeClient    claude;
    private final WorkspaceClient executor;
    private final JobService      jobService;

    public AgentLoop(ClaudeClient claude,
                     WorkspaceClient executor,
                     JobService jobService) {
        this.claude     = claude;
        this.executor   = executor;
        this.jobService = jobService;
    }

    // ------------------------------------------------------------------
    // Entry point — called by StepScheduler for each claimed step
    // ------------------------------------------------------------------

    /**
     * Run the full agent loop for one pipeline step.
     *
     * This method blocks until the agent finishes (result found),
     * gives up (max turns), or an error occurs. It is called from
     * a worker thread in the StepScheduler's thread pool.
     */
    public void run(Step step) {
        log.info("Starting agent loop: job={} role={} attempt={}",
                step.getJob().getId(), step.getRole(), step.getAttempt());

        String workspaceRef = step.getJob().getWorkspaceRef();

        // Collect outputs from all previously completed steps.
        // This gives each agent the full context of what came before it.
        Map<AgentRole, String> priorResults =
                jobService.completedResults(step.getJob().getId());

        // Build the conversation history starting with the system + initial user message.
        List<Message> history = new ArrayList<>();
        history.add(new Message("user", buildInitialPrompt(step.getRole(), priorResults)));

        // Multi-turn loop
        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            log.debug("Turn {}/{} for step {}", turn, MAX_TURNS, step.getId());

            // --- Call Claude ---
            String response;
            try {
                response = claude.complete(MODEL, history, SystemPrompts.get(step.getRole()));
            } catch (Exception e) {
                jobService.failStep(step, "Claude API error: " + e.getMessage());
                return;
            }
            history.add(new Message("assistant", response));

            // --- Check for terminal result ---
            Optional<String> result = ResponseParser.extractResult(response);
            if (result.isPresent()) {
                log.info("Step {} completed after {} turns", step.getId(), turn);
                jobService.completeStep(step, result.get());
                return;
            }

            // --- Check for code action ---
            Optional<String> code = ResponseParser.extractCodeBlock(response);
            String observation;
            if (code.isPresent()) {
                observation = executeCode(workspaceRef, code.get());
            } else {
                // Agent is reasoning without code — nudge it to continue.
                observation = "Continue. Use a code block to take an action, " +
                              "or write <result>...</result> when you are done.";
            }

            history.add(new Message("user", "Observation:\n" + observation));

            // --- Heartbeat: tell DB this worker is still alive ---
            if (turn % HEARTBEAT_EVERY == 0) {
                jobService.heartbeat(step);
            }
        }

        // Max turns reached without a <result> tag.
        jobService.failStep(step,
                "Max turns (" + MAX_TURNS + ") reached without producing a <result> tag.");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Execute a Python code block in the sandbox and return the observation string.
     * The observation is what Claude reads on its next turn.
     */
    private String executeCode(String workspaceRef, String code) {
        try {
            ExecutionResult result = executor.runCode(workspaceRef, code, 300);
            return result.toObservation();
        } catch (Exception e) {
            return "error_type: EXECUTOR_UNREACHABLE\nstderr: " + e.getMessage();
        }
    }

    /**
     * Build the first user message for a given agent role.
     *
     * This message contains:
     *  - What the agent needs to do (its specific task)
     *  - The outputs of all previously completed agents (for context)
     */
    private String buildInitialPrompt(AgentRole role, Map<AgentRole, String> priorResults) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are starting your task as the ").append(role).append(" agent.\n\n");

        if (!priorResults.isEmpty()) {
            sb.append("=== CONTEXT FROM PREVIOUS AGENTS ===\n");
            priorResults.forEach((r, json) ->
                    sb.append("[ ").append(r).append(" result ]\n").append(json).append("\n\n")
            );
            sb.append("=== END CONTEXT ===\n\n");
        }

        sb.append(switch (role) {
            case REPO_MAPPER ->
                "Explore the repository in the workspace and produce the required JSON summary.";
            case PLANNER -> {
                // Detect backtrack scenario: a prior TESTER step reported tests_passed=false.
                String testerResult = priorResults.get(AgentRole.TESTER);
                boolean isReplan = testerResult != null &&
                        (testerResult.contains("\"tests_passed\":false") ||
                         testerResult.contains("\"tests_passed\": false"));
                if (isReplan) {
                    yield "The previous implementation FAILED the tests (see TESTER result above). " +
                          "Study the failure details and produce a REVISED repair plan that correctly " +
                          "addresses the root cause.";
                }
                yield "Using the repository map above, analyse the codebase and produce a repair plan.";
            }
            case IMPLEMENTER ->
                "Follow the repair plan above. Apply the changes using apply_patch() and verify.";
            case TESTER ->
                "Run the test suite with run_command([\"mvn\", \"-q\", \"test\"]) and report results.";
            case REVIEWER ->
                "Review the repair. Run git_diff(\"HEAD\") and assess the changes.";
        });

        return sb.toString();
    }
}
