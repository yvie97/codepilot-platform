package com.codepilot.orchestrator.skill;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process skill registry (§6.3).
 *
 * All {@link Skill} beans declared as Spring {@code @Component}s are
 * automatically collected at startup via constructor injection.  No external
 * service or distributed coordination is required.
 *
 * <p>Key responsibilities:
 * <ol>
 *   <li>Lookup by name ({@link #get}).</li>
 *   <li>Metrics-instrumented execution ({@link #execute}) — every call is
 *       timed and counted automatically, with no per-skill boilerplate.</li>
 *   <li>Tool documentation generation ({@link #buildToolDocumentation}) —
 *       produces the AVAILABLE TOOLS section injected into every agent's
 *       system prompt, always in sync with the registered skill set.</li>
 * </ol>
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, Skill<?, ?>> skills = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    /**
     * Spring collects every {@code Skill<?,?>} bean and passes the list here.
     * Adding a new skill only requires declaring it as {@code @Component}.
     */
    public SkillRegistry(List<Skill<?, ?>> allSkills, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (Skill<?, ?> skill : allSkills) {
            skills.put(skill.manifest().name(), skill);
            log.info("Registered skill '{}' v{} [{}]",
                    skill.manifest().name(),
                    skill.manifest().version(),
                    skill.manifest().target());
        }
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    public Skill<?, ?> get(String name) {
        Skill<?, ?> skill = skills.get(name);
        if (skill == null) {
            throw new SkillNotFoundException(name);
        }
        return skill;
    }

    /** Returns all registered skill names (sorted). */
    public List<String> skillNames() {
        return skills.keySet().stream().sorted().toList();
    }

    // ------------------------------------------------------------------
    // Metrics-instrumented execution
    // ------------------------------------------------------------------

    /**
     * Execute a named skill with full observability (§6.4).
     *
     * Every call is automatically timed and counted:
     * <pre>
     *   codepilot.skill.calls{skill, status="success|error|timeout|policy_violation"}
     *   codepilot.skill.duration{skill, target="java_local|python_executor"}
     * </pre>
     *
     * @throws SkillException       on controlled execution failure
     * @throws ClassCastException   if the caller passes the wrong input type
     */
    @SuppressWarnings("unchecked")
    public <I, O> O execute(String skillName, I input, SkillExecutionContext ctx) {
        Skill<I, O> skill = (Skill<I, O>) get(skillName);
        String targetTag = skill.manifest().target().name().toLowerCase();

        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";
        try {
            O result = skill.execute(input, ctx);
            return result;
        } catch (SkillException e) {
            status = e.getKind().name().toLowerCase();
            throw e;
        } catch (Exception e) {
            status = "error";
            throw new SkillException(SkillException.Kind.EXECUTOR_ERROR,
                    "Unexpected error in skill '" + skillName + "': " + e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("codepilot.skill.duration",
                    "skill", skillName, "target", targetTag));
            meterRegistry.counter("codepilot.skill.calls",
                    "skill", skillName, "status", status).increment();
        }
    }

    // ------------------------------------------------------------------
    // Tool documentation generation (§6.3)
    // ------------------------------------------------------------------

    /**
     * Generate the AVAILABLE TOOLS documentation block injected into every
     * agent's system prompt.
     *
     * Because this is derived from live skill manifests, the documentation
     * is always in sync with the registered skill set — adding a new skill
     * automatically makes it visible to agents.
     */
    public String buildToolDocumentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You have access to the following tool functions. Call them by writing
                Python code blocks (```python ... ```) which will be executed in a
                sandbox and the output returned to you as an observation.

                AVAILABLE TOOLS:
                """);

        // Emit PYTHON_EXECUTOR skills first (agents use these directly),
        // then JAVA_LOCAL skills (informational only — invoked by the orchestrator).
        skills.values().stream()
                .map(Skill::manifest)
                .sorted(Comparator
                        .comparing((SkillManifest m) -> m.target() == ExecutionTarget.JAVA_LOCAL ? 1 : 0)
                        .thenComparing(SkillManifest::name))
                .forEach(m -> {
                    sb.append("  ").append(m.signature()).append("\n");
                    sb.append("      ").append(m.description()).append("\n\n");
                });

        sb.append("""
                RULES:
                  - Write one code block per turn; wait for the observation before continuing.
                  - Use print() to output information you want to see.
                  - When you have gathered enough information, write your final answer
                    inside <result>...</result> tags. This ends your turn.
                """);

        return sb.toString();
    }
}
