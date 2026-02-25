package com.codepilot.orchestrator.executor.dto;

/**
 * Response from POST /workspace/run_code.
 * Must match models.ExecutionResult in executor/models.py.
 */
public record ExecutionResult(
        int exit_code,
        String stdout,
        String stderr,
        double elapsed_sec,
        String error_type    // "TIMEOUT" | "POLICY_VIOLATION" | null
) {
    /** True if the code ran successfully. */
    public boolean success() {
        return exit_code == 0;
    }

    /**
     * Format as the observation string the agent reads on its next turn.
     * Must match ExecutionResult.to_observation() in executor/models.py.
     */
    public String toObservation() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isBlank()) {
            sb.append("stdout:\n").append(stdout.stripTrailing());
        }
        if (stderr != null && !stderr.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("stderr:\n").append(stderr.stripTrailing());
        }
        if (sb.isEmpty()) sb.append("(no output)");
        sb.append("\n\nexit_code: ").append(exit_code);
        if (error_type != null) {
            sb.append("\nerror_type: ").append(error_type);
        }
        return sb.toString();
    }
}
