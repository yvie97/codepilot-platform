package com.codepilot.orchestrator.agent;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Claude's text responses to extract:
 *   1. Python code blocks  — sent to the executor sandbox for execution
 *   2. <result> tags       — the agent's final answer for this step
 *
 * This is the parsing layer of the CodeAct loop (§5.1):
 *   Claude response → extract code → execute → observation → Claude response → ...
 *   Until Claude writes <result>...</result> to signal it's done.
 */
public class ResponseParser {

    // Matches ```python ... ``` or ``` ... ``` (with optional language label)
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:python)?\\s*\\n(.*?)\\n```",
            Pattern.DOTALL
    );

    // Matches <result>...</result> (the agent's terminal output for this step)
    private static final Pattern RESULT_TAG = Pattern.compile(
            "<result>(.*?)</result>",
            Pattern.DOTALL
    );

    private ResponseParser() {}

    /**
     * Extract the first Python code block from a Claude response.
     *
     * Returns Optional.empty() if the agent's response contains no code block
     * (e.g. it's still reasoning, or it wrote the final <result> directly).
     */
    public static Optional<String> extractCodeBlock(String response) {
        Matcher m = CODE_BLOCK.matcher(response);
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }

    /**
     * Extract the content of the first <result>...</result> tag.
     *
     * The agent writes this tag when it has finished its role and is ready
     * to pass its output to the next agent in the pipeline.
     *
     * Example Claude output:
     *   "I have analyzed the repository. Here is my summary:
     *    <result>{"files": 201, "entryPoint": "src/main/..."}</result>"
     */
    public static Optional<String> extractResult(String response) {
        Matcher m = RESULT_TAG.matcher(response);
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }
}
