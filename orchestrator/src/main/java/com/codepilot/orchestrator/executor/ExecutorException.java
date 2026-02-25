package com.codepilot.orchestrator.executor;

/**
 * Thrown when the Python executor service returns an error or is unreachable.
 */
public class ExecutorException extends RuntimeException {

    public ExecutorException(String message) {
        super(message);
    }

    public ExecutorException(String message, Throwable cause) {
        super(message, cause);
    }
}
