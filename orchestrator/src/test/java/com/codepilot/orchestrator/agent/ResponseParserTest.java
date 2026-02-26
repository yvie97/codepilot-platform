package com.codepilot.orchestrator.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ResponseParser.
 *
 * ResponseParser is a pure utility class (only static methods, no I/O),
 * so these tests need zero Spring context and zero mocks — they run fast.
 */
class ResponseParserTest {

    // ------------------------------------------------------------------
    // extractCodeBlock
    // ------------------------------------------------------------------

    @Test
    void extractCodeBlock_withPythonFence_returnsCode() {
        String response = """
                I will list files first.
                ```python
                import os
                print(os.listdir('.'))
                ```
                """;
        Optional<String> code = ResponseParser.extractCodeBlock(response);
        assertThat(code).isPresent();
        assertThat(code.get()).contains("import os");
        assertThat(code.get()).contains("os.listdir");
    }

    @Test
    void extractCodeBlock_withUnlabelledFence_returnsCode() {
        // Agents sometimes omit the "python" label
        String response = """
                ```
                x = 1 + 1
                ```
                """;
        assertThat(ResponseParser.extractCodeBlock(response)).isPresent();
    }

    @Test
    void extractCodeBlock_withNoFence_returnsEmpty() {
        String response = "I will now think about the problem.";
        assertThat(ResponseParser.extractCodeBlock(response)).isEmpty();
    }

    @Test
    void extractCodeBlock_withMultipleFences_returnsFirst() {
        String response = """
                ```python
                first_block()
                ```
                ```python
                second_block()
                ```
                """;
        Optional<String> code = ResponseParser.extractCodeBlock(response);
        assertThat(code).isPresent();
        assertThat(code.get()).contains("first_block");
        assertThat(code.get()).doesNotContain("second_block");
    }

    @Test
    void extractCodeBlock_multilineCode_returnsFull() {
        String response = """
                ```python
                def fix():
                    x = 1
                    y = 2
                    return x + y
                ```
                """;
        Optional<String> code = ResponseParser.extractCodeBlock(response);
        assertThat(code).isPresent();
        assertThat(code.get()).contains("def fix()");
        assertThat(code.get()).contains("return x + y");
    }

    // ------------------------------------------------------------------
    // extractResult
    // ------------------------------------------------------------------

    @Test
    void extractResult_withResultTag_returnsContent() {
        String response = """
                After analysing the code I found the bug.
                <result>{"fixed": true, "description": "Off-by-one in loop"}</result>
                """;
        Optional<String> result = ResponseParser.extractResult(response);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("Off-by-one");
    }

    @Test
    void extractResult_withNoResultTag_returnsEmpty() {
        String response = "Still working on it.";
        assertThat(ResponseParser.extractResult(response)).isEmpty();
    }

    @Test
    void extractResult_withMultilineResult_returnsFullContent() {
        String response = """
                <result>
                {
                  "passed": true,
                  "tests_run": 42,
                  "failures": 0
                }
                </result>
                """;
        Optional<String> result = ResponseParser.extractResult(response);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("\"passed\": true");
        assertThat(result.get()).contains("\"tests_run\": 42");
    }

    @Test
    void extractResult_resultTagWithSurroundingText_extractsOnlyContent() {
        String response = "Done! <result>success</result> That's all.";
        Optional<String> result = ResponseParser.extractResult(response);
        assertThat(result).isPresent();
        assertThat(result.get().strip()).isEqualTo("success");
    }
}
