# CodePilot — Multi-Agent Code Repair & Scaffolding Platform

> **Goal**: A production-style platform using **multi-agent orchestration** and **CodeAct-inspired executable code actions** (Wang et al., 2024) to automate Java software engineering tasks — **bug fixing** and **code scaffolding** — producing **CI-verified artifacts** (build/test/lint pass) with strong **reliability**, **security**, and **observability**.
>
> **Key idea** (from CodeAct): agents don't output JSON tool calls — they emit **executable Python code** that runs in a sandbox. Execution results (stdout, stderr, exit code, tracebacks) feed back as observations into the next agent turn, enabling **autonomous self-debugging** without human intervention.

**Language decision**
- **Java (Spring Boot)** for the control plane: REST API, state machine, step scheduling, reliability logic.
- **Python (FastAPI)** for the execution plane: sandboxed code runner, tool function library, build/test integration.

The contract between the two planes is a stable HTTP API — either side can be reimplemented independently.

---

## 0) Success Criteria

A run is **successful** iff:
1. Patch applies cleanly to the workspace at the known base commit.
2. Verification gates pass: compile + targeted tests (+ lint if configured).
3. Reviewer agent approves: no policy violations, no regressions, patch size within limits.
4. Artifacts saved: unified diff, test report (before/after), execution transcript, run timeline, provenance metadata.

Platform-level success:
- Supports concurrent runs with workspace isolation and per-run resource budgets.
- Crash-safe: resumes correctly after pod/process restarts; idempotent step execution; bounded retries.
- Secure-by-default: sandboxed executor, K8s RBAC, NetworkPolicy, command allowlist.
- Measurable quality: **pass@1**, **pass@3**, **time-to-green**, **iterations-to-green** — evaluated on a curated benchmark of 25 real Java bug-fix tasks.

---

## 1) Requirements

### 1.1 Functional
- Submit tasks:
  - **BUGFIX**: given a failing test, produce a patch that makes it pass + adds a regression test.
  - **SCAFFOLD**: generate a new Maven module with build config, a CI stub, and skeleton unit tests.
- Multi-agent workflow: RepoMap → Plan → Implement → Test → Review → Finalize.
- Outputs: unified diff patch, test report (before/after), full execution transcript, run timeline JSON.

### 1.2 Non-functional
- **Reliability**: persistent state, idempotent step retries, bounded backtracking, crash recovery.
- **Security**: sandboxed execution, command allowlist, filesystem/network confinement.
- **Observability**: structured JSON logs, Prometheus metrics, immutable artifact store.
- **Scalability**: executor scales with workload (HPA); orchestrator handles concurrent runs via thread pool.
- **Extensibility**: pluggable tool functions; new agent roles added without changing the state machine.

---

## 2) Architecture (Two-Service, K8s-Native, Solo-Feasible)

### 2.1 Component Overview

```
Client
  │
  ▼
┌─────────────────────────────────────────────────┐
│           Orchestrator  (Spring Boot)            │  Deployment + HPA
│                                                  │
│  ┌──────────────┐    ┌──────────────────────┐   │
│  │  REST API    │    │  Agent Worker Pool   │   │
│  │  /v1/tasks   │    │  (thread pool,       │   │
│  │  /v1/runs    │    │   in-process)        │   │
│  └──────┬───────┘    └──────────┬───────────┘   │
│         │                       │               │
│         └──────────┬────────────┘               │
│                    │                            │
│         ┌──────────▼──────────────┐             │
│         │  State Machine +        │             │
│         │  Step Scheduler         │             │
│         │  (DB-row claiming)      │             │
│         └──────────┬──────────────┘             │
└────────────────────┼────────────────────────────┘
                     │ HTTP (internal ClusterIP)
                     ▼
┌────────────────────────────────────┐
│       Executor  (FastAPI / Python) │  Deployment + NetworkPolicy + HPA
│       Sandboxed code action runner │
└─────────────┬──────────────────────┘
              │
     ┌────────┴──────────┐
     ▼                   ▼
PostgreSQL           MinIO / S3
(state store)        (artifact store)
```

### 2.2 Key Design Decisions

**Why two services, not three?**
Agent workers run as a thread pool within the orchestrator JVM. This eliminates the overhead of a third deployment, a message queue, and inter-service serialization — while still demonstrating clean separation between the control plane (Java) and execution plane (Python). The internal interface is identical to what a separate worker service would consume, so extraction is straightforward if scale requires it.

**Why no message queue?**
For this project, an in-process thread pool with **DB-row claiming** (optimistic lock on step rows) provides at-least-once semantics with crash recovery — without the operational overhead of running and maintaining Kafka or RabbitMQ. The scheduling contract is cleanly defined, so a queue can be swapped in as a future extension. Your existing RabbitMQ experience (Graduate Assistant role) already demonstrates that skill; this project complements it by showing you can design the scheduling layer correctly without that dependency.

**Why shared executor pods instead of per-run ephemeral Jobs?**
Ephemeral per-run Jobs require a K8s controller (operator pattern) to manage — that is enterprise-grade scope. Shared executor pods with per-run workspace subdirectories + DB row-level locks provide adequate isolation, are clearly production-safe, and are implementable solo. The workspace snapshot/restore mechanism provides idempotent recovery without needing pod-level isolation.

---

## 3) Platform API & Contracts

### 3.1 Public API (Orchestrator)
- `POST /v1/tasks` — submit task (`type`, `repoUrl`, `repoRef`, `prompt`, `constraints`)
- `GET /v1/runs/{runId}` — status, current state, step timeline, budget consumption
- `GET /v1/runs/{runId}/artifacts` — list artifacts with pre-signed download links
- `POST /v1/runs/{runId}/cancel` — cancel with safe workspace cleanup

### 3.2 Internal Step Scheduling (DB-Row Claiming)

Steps are rows in PostgreSQL. Workers claim them atomically:

```sql
-- Worker claims next available step
UPDATE steps
SET    status = 'RUNNING', worker_id = ?, claimed_at = NOW()
WHERE  step_id = ? AND status = 'PENDING' AND attempt = ?
-- Uses SELECT FOR UPDATE SKIP LOCKED for concurrent safety
```

**Step row (simplified schema):**
```json
{
  "runId": "r-123",
  "stepId": "s-045",
  "role": "IMPLEMENTER",
  "attempt": 1,
  "status": "PENDING",
  "inputArtifactIds": ["a-repomap", "a-plan"],
  "conversationHistory": null,
  "budgets": { "toolCalls": 10, "wallSec": 600 },
  "createdAt": "...",
  "claimedAt": null,
  "heartbeatAt": null
}
```

Workers write a heartbeat every 30 seconds. A reaper thread in the orchestrator reclaims steps where `heartbeatAt` is stale (> 90s), re-setting them to `PENDING` for retry — this is the crash recovery mechanism.

---

## 4) Core Data Model

### 4.1 Entities
- **Task**: `taskId`, `type` (BUGFIX|SCAFFOLD), `prompt`, `repoUrl`, `repoRef`, `constraints`
- **Run**: `runId`, `taskId`, `status`, `currentState`, `budget`, `workspaceRef`, `iterationCount`, timestamps
- **Step**: `stepId`, `runId`, `role`, `status`, `attempt`, `inputArtifactIds`, `outputArtifactIds`, `workerId`, `heartbeatAt`, `conversationHistory` (JSON), timestamps
- **Artifact**: `artifactId`, `runId`, `type` (PATCH|LOG|REPORT|PLAN|MAP|TRANSCRIPT|TIMELINE), `uri`, `sha256`, `sizeBytes`, `metadata`
- **Workspace**: `workspaceRef`, `runId`, `baseCommit`, `snapshotRef`, `lockHeldBy`

### 4.2 Run State Machine (crash-safe)

```
INIT → MAP_REPO → PLAN → IMPLEMENT → TEST → REVIEW → FINALIZE → DONE
                            ↑__________________________|
                            (backtrack to PLAN after 2 failed IMPLEMENT→TEST cycles)

Terminals:
  ABORTED  — budget exceeded or user-cancelled
  FAILED   — unrecoverable error (e.g., repo unreachable, schema violation)
```

**Failure handling:**
- Per-step bounded retry: `attempt <= maxAttempts` (default: 2)
- Backtracking: after 2 consecutive failed TEST steps → re-enter PLAN with failure context appended to prompt (failing tests, error log tail, iteration count)
- Crash recovery: heartbeat reaper reclaims orphaned steps; orchestrator on startup re-queues any `RUNNING` steps with no recent heartbeat
- Idempotency key: `(runId, stepId, attempt)` — duplicate step completions are ignored

### 4.3 End-to-End Sequence Diagrams

**Happy Path (BUGFIX — single iteration, bug fixed on first attempt):**

```
Client        Orchestrator      Worker           Executor       MinIO / PG
  │                │               │                 │               │
  │─POST /tasks───>│               │                 │               │
  │<── {runId} ────│               │                 │               │
  │                │─dispatch ────>│                 │               │
  │                │  MAP_REPO     │─/workspace/────>│               │
  │                │               │   create        │               │
  │                │               │<─workspaceRef ──│               │
  │                │               │─Claude loop ───>│               │
  │                │               │  /run_code      │               │
  │                │               │  (list_files,   │               │
  │                │               │   read_file×3)  │               │
  │                │               │<─observations ──│               │
  │                │               │──────────── save RepoMap ──────>│
  │                │<─step DONE ───│                 │               │
  │                │  (repeat: PLAN → IMPLEMENT → TEST → REVIEW → FINALIZE)
  │─GET artifacts─>│               │                 │               │
  │<─ patch+report─│               │                 │               │
```

**Retry / Backtrack Path (test fails twice → state machine re-enters PLAN):**

```
Worker               Executor            PostgreSQL
  │                     │                    │
  │──[IMPLEMENT #1]────>│  apply_patch()     │
  │<── patch OK         │                    │
  │──snapshot──────────>│                    │  ← base saved (idempotent retry point)
  │──[TEST #1]─────────>│  mvn test          │
  │<── FAIL: FooTest    │                    │
  │────────────── record failure ───────────>│
  │                     │                    │
  │──restore snapshot──>│                    │  ← clean slate for attempt 2
  │──[IMPLEMENT #2]────>│  apply_patch v2    │
  │   (+ failure obs.)  │                    │
  │<── patch OK         │                    │
  │──[TEST #2]─────────>│  mvn test          │
  │<── FAIL: FooTest    │                    │
  │                     │                    │
  │  2 consecutive failures:                 │
  │─────────── BACKTRACK → PLAN ────────────>│  state=PLAN, iter++
  │                     │                    │
  │──[PLAN re-entry]───>│  search_code()     │
  │   (+ both failures, │  read_file()       │
  │    both bad patches)│                    │
  │<── revised Plan     │                    │
  │──[IMPLEMENT #3]────>│  apply_patch v3    │
  │──[TEST #3]─────────>│  mvn test          │
  │<── PASS ✓           │                    │
  │──[REVIEW]──────────>│  read_file()       │
  │<── APPROVE          │                    │
  │──[FINALIZE] → DONE  │                    │
```

---

## 5) Multi-Agent Design with CodeAct-Inspired Execution

### 5.1 Core Principle: Executable Code Actions

Directly implementing the CodeAct framework (Wang et al., 2024): each agent emits **executable Python code** as its action rather than a pre-defined JSON tool call. The code is sent to the executor sandbox, and the result (stdout, stderr, exit code, or traceback) is returned as the agent's next **observation**. The agent's conversation history carries both actions and observations.

```
Agent turn N:
  Input:   [system prompt] + [prior (action, observation) pairs] + [current task]
  Output:  Python code block  ← the "action"

Executor:
  Validates imports and subprocess calls against allowlist
  Injects workspace context (tool functions, working directory)
  Executes with resource limits + wall-clock timeout
  Returns: stdout, stderr, exit_code, elapsed_sec

Agent turn N+1:
  Input:   [prior context] + ["Observation:\n" + execution result]
  Output:  next action (more code) OR structured result wrapped in <result>...</result>
```

**Self-debugging in practice**: if the agent's `apply_patch()` call fails because the diff context doesn't match, it receives the full error message as an observation and can revise the diff — without any human intervention or hard-coded error handlers. This is the central advantage over JSON-based tool dispatch, and it is what the CodeAct paper demonstrates empirically.

> **Concept mapping — CodeAct (paper) vs CodePilot Skills (this project):**
> - **CodeAct** defines *how* agents act: executable Python code as a unified action space that expands reach to any library and enables control/data flow across tool calls in a single turn.
> - **CodePilot Skills** define *what* agents can do and *under what policy*: versioned, policy-enforced, observable tool plugins (`apply_patch`, `run_command`, `search_code`, …) that agents invoke *via* executable code actions — combining CodeAct's execution model with a formal plugin architecture (§6).
>
> The distinction avoids buzzword overloading: CodeAct is the execution paradigm; the Skills Layer is the platform capability contract built on top of it.

### 5.2 Tool Library (Python functions injected into sandbox)

```python
# File operations
read_file(path: str) -> str
write_file(path: str, content: str) -> None
list_files(path: str, pattern: str = "**/*") -> list[str]
search_code(pattern: str, path: str = ".") -> list[dict]  # ripgrep wrapper

# Git operations
git_status() -> str
git_diff(base: str = "HEAD") -> str
apply_patch(diff: str) -> ExecutionResult   # returns success/failure + message
git_reset(to_ref: str) -> None

# Build/test (allowlisted subprocess)
run_command(cmd: list[str], timeout: int = 300) -> ExecutionResult
# Allowed: mvn, ./gradlew, java, git, rg — nothing else
```

All tool functions are Python callables injected into the `exec()` context — no imports required. Subprocess calls inside `run_command` are validated against the allowlist before execution.

### 5.3 Agent Roles

**1. RepoMapper**
- Emits code actions: `list_files()`, `read_file()` on `pom.xml`, `build.gradle`, `README`, top-level source dirs
- Output artifact: `RepoMap` JSON (build system, test command, lint command, key modules, test file locations)

**2. Planner**
- Input: RepoMap + task prompt (+ failure context if backtracking)
- Emits code actions: `search_code()` to find relevant classes, `read_file()` for context
- Output artifact: `Plan` JSON (files to examine, hypothesized root cause, fix approach, acceptance criteria, regression test strategy)

**3. Implementer** ← *core CodeAct agent*
- Input: RepoMap + Plan + TestResult (if retrying with failure observation)
- Emits code actions: reads relevant source files, constructs and applies unified diff via `apply_patch()`, verifies with `git_diff()`
- Self-debugs: if `apply_patch()` fails, observes the error and revises the diff in the next turn
- Output artifact: `PatchCandidate` (unified diff + list of touched files + rationale)

**4. Tester**
- Input: PatchCandidate (already applied to workspace at base snapshot)
- Emits code actions: `run_command(["mvn", "-q", "test"])`, parses Surefire XML reports
- Output artifact: `TestResult` (passed, failedTestsBefore, failedTestsAfter, exitCode, durationSec, logTail)

**5. Reviewer**
- Input: PatchCandidate + TestResult + RepoMap
- Emits code actions: `read_file()` on changed files, `search_code()` for anti-patterns (hardcoded strings, `@Ignore` annotations, TODO comments in new code)
- Policy checks: no disabled tests, no hardcoded secrets, patch LOC within limit, no new compilation warnings in changed files
- Output: `Review` (`APPROVE` | `REQUEST_CHANGES` + reason)

### 5.4 LLM Integration (Claude API)

**Model**: `claude-sonnet-4-6` — chosen for strong code reasoning, large context window (needed for multi-turn agent conversations with code), and cost efficiency at this scale.

**Agent loop (Java worker):**
```java
List<Message> conversation = new ArrayList<>();
conversation.add(Message.system(buildSystemPrompt(role, repoMap, plan)));
conversation.add(Message.user(buildInitialTaskMessage(step)));

// Persist conversation start for crash recovery
stepRepo.saveConversation(step.stepId(), conversation);

while (toolCallsUsed < budget.maxToolCalls() && !isDone) {
    ClaudeResponse response = claudeClient.complete(conversation, temperature(role));

    String codeAction = extractCodeBlock(response.text());
    if (codeAction != null) {
        // Execute in sandbox, get observation
        ExecutionResult result = executorClient.runCode(
            RunCodeRequest.of(codeAction, step.workspaceRef(), budget.commandTimeoutSec())
        );
        conversation.add(Message.assistant(response.text()));
        conversation.add(Message.user("Observation:\n" + result.toObservationString()));
        toolCallsUsed++;
        // Persist updated conversation for crash recovery
        stepRepo.saveConversation(step.stepId(), conversation);
    } else {
        // Agent signals completion with <result> tag
        artifact = parseStructuredOutput(response.text(), role.outputSchema());
        isDone = true;
    }
}
```

**Context management:**
- Each agent receives only the artifacts it needs (RepoMap, Plan, TestResult) — not full conversation history of other agents
- Conversation history (actions + observations) is persisted to PostgreSQL per step — a resumed agent continues from the last observation
- Token budget: estimated via tiktoken-equivalent before each turn; step transitions to `ABORTED` if approaching model context limit

**Prompt structure:**
- System prompt: role description + available tool signatures + docstrings + output format spec
- Task message: task prompt + relevant artifact content
- No few-shot examples needed — tool docstrings + structured output instructions are sufficient grounding for claude-sonnet-4-6
- Completion signal: agent wraps final output in `<result>...</result>` tags containing a JSON object matching the role's output schema

---

## 6) Skills Layer (Plugin-Based Tooling)

Each tool available to agents is formalized as a **Skill** — a versioned, policy-enforced, observable execution unit. This decouples *what a skill does* from *how and where it runs*, letting the platform enforce per-skill policies and collect per-skill telemetry uniformly across all agent roles.

### 6.1 Skill Interface (Java)

```java
// Every tool capability agents can invoke is registered as a Skill
interface Skill<I, O> {
    SkillManifest manifest();    // identity + schema
    SkillPolicy   policy();      // execution constraints
    O execute(I input, SkillExecutionContext ctx) throws SkillException;
}

record SkillManifest(
    String   name,         // e.g. "apply_patch" — injected into agent system prompt
    String   version,      // e.g. "1.0.0"
    String   description,  // docstring shown to the agent as tool documentation
    Class<?> inputType,
    Class<?> outputType) {}

record SkillPolicy(
    ExecutionTarget target,           // JAVA_LOCAL | PYTHON_EXECUTOR
    boolean         networkAllowed,   // always false for executor-routed skills
    boolean         filesystemWrite,  // true only for patch/write skills
    int             commandTimeoutSec,
    int             maxMemoryMb) {}
```

### 6.2 Skill Execution Routing

Skills are categorised by execution target. The orchestrator routes them accordingly at call time — no agent-side awareness of routing required.

| Skill | Target | Rationale |
|---|---|---|
| `read_file`, `list_files`, `search_code` | `PYTHON_EXECUTOR` | Filesystem access + ripgrep subprocess |
| `apply_patch`, `git_reset`, `git_diff` | `PYTHON_EXECUTOR` | Git subprocess; workspace mutation |
| `run_command` (mvn / gradle / java / rg) | `PYTHON_EXECUTOR` | Build tools; strict timeout + resource limits |
| `parse_surefire_xml` | `JAVA_LOCAL` | Pure XML parsing; no subprocess risk |
| `score_patch_quality` | `JAVA_LOCAL` | Static diff heuristics; deterministic |
| `check_policy` (no secrets, no `@Ignore`) | `JAVA_LOCAL` | Regex/AST analysis; no filesystem access |

`JAVA_LOCAL` skills execute in the orchestrator JVM with zero sandbox overhead. `PYTHON_EXECUTOR` skills are forwarded to the executor service via the internal HTTP API defined in §7. The concept mapping to CodeAct is described in §5.1.

### 6.3 Skill Registry (In-Process)

A lightweight in-process registry — no external service, no distributed coordination:

```java
@Component
class SkillRegistry {
    private final Map<String, Skill<?, ?>> skills = new ConcurrentHashMap<>();

    // Skills register themselves via @PostConstruct
    void register(Skill<?, ?> skill) {
        skills.put(skill.manifest().name(), skill);
    }

    Skill<?, ?> get(String name) {
        return Optional.ofNullable(skills.get(name))
            .orElseThrow(() -> new SkillNotFoundException(name));
    }

    // Generates the tool documentation string injected into agent system prompts,
    // keeping available skills and their descriptions always in sync.
    String buildToolDocumentation() { ... }
}
```

### 6.4 Per-Skill Observability

Every skill execution is instrumented automatically at the registry call site, with no per-skill boilerplate:

```
codepilot_skill_calls_total{skill, status="success|error|timeout|policy_violation"}
codepilot_skill_duration_seconds{skill, target="java_local|python_executor"}
```

This makes it trivial to identify which skills are the latency bottleneck and which are most error-prone — precision that matters when tuning benchmark pass rates.

---

## 7) Executor Service (Python / FastAPI)

### 7.1 API
```
POST /workspace/create     → clone repo at ref into workspace subdir; return workspaceRef
POST /workspace/run_code   → execute Python code action in sandbox; return ExecutionResult
POST /workspace/snapshot   → tar workspace, upload to MinIO; return snapshotRef
POST /workspace/restore    → restore workspace from snapshotRef (for idempotent retry)
POST /workspace/delete     → clean up workspace directory
```

### 7.2 Code Action Sandbox

The `run_code` endpoint executes agent-emitted Python code with strict controls:

```python
ALLOWED_MODULES = frozenset({
    "os", "subprocess", "pathlib", "json", "re", "shutil",
    "difflib", "textwrap", "xml.etree.ElementTree"
})
ALLOWED_COMMANDS = frozenset({"mvn", "./gradlew", "java", "git", "rg"})

def run_code(code: str, workspace_dir: str, timeout_sec: int) -> ExecutionResult:
    # 1. Static validation: check imports against ALLOWED_MODULES
    validate_imports(code)  # raises ToolNotAllowedError on violation

    # 2. Inject tool functions + workspace context
    ctx = build_sandbox_context(workspace_dir, ALLOWED_COMMANDS)

    # 3. Execute with wall-clock timeout
    with timeout(timeout_sec):
        exec(compile(code, "<agent_action>", "exec"), ctx)

    return ctx.get("__result__", ExecutionResult.empty())
```

**Sandbox constraints:**
- `exec()` runs with a restricted globals dict: `__builtins__` filtered to safe subset, no `__import__`
- `open()` replaced with a workspace-scoped wrapper (raises `SecurityError` for paths outside workspace)
- All `subprocess` calls inside `run_command()` are validated against `ALLOWED_COMMANDS` before `Popen`
- Working directory locked to workspace subdir for the lifetime of the call
- Wall-clock timeout enforced via `signal.SIGALRM`
- Memory limit enforced via `resource.setrlimit(resource.RLIMIT_AS, ...)`

### 7.3 K8s Deployment Controls

```yaml
# executor/deployment.yaml (key security fields)
securityContext:
  runAsNonRoot: true
  readOnlyRootFilesystem: true   # workspace PVC is the only writable mount
  allowPrivilegeEscalation: false
  seccompProfile: { type: RuntimeDefault }
resources:
  limits:   { cpu: "1", memory: "2Gi", ephemeral-storage: "5Gi" }
  requests: { cpu: "250m", memory: "512Mi" }
volumeMounts:
  - name: workspaces
    mountPath: /workspaces       # RWX PVC, per-run subdirs
  - name: maven-cache
    mountPath: /root/.m2/repository  # pre-populated in image; read-only mount
    readOnly: true
```

Maven dependencies for the benchmark repos are baked into the executor Docker image (`/root/.m2` cache). This eliminates the need for egress to Maven Central during runs, making NetworkPolicy's deny-egress rule clean and unconditional.

---

## 8) Reliability Design

### 8.1 Idempotency

Every step execution follows this protocol:
1. **Before** executing: snapshot current workspace state to MinIO as `step_{stepId}_base`
2. **Execute** agent loop against workspace
3. **On failure / retry**: restore from `step_{stepId}_base`, then re-execute from scratch
4. Artifact writes are atomic: upload to a temp key first, then rename to the final key

This guarantees that retrying a step always starts from the identical workspace state, regardless of how far the previous attempt got.

### 8.2 Retries & Backtracking

- **Tool-level**: transient executor errors (HTTP 5xx, timeout) → retry up to 3 times with exponential backoff before failing the step
- **Step-level**: IMPLEMENT or TEST step failed → retry up to `maxAttempts` (default: 2) with the failure observation appended to the agent's initial prompt
- **Backtracking**: after 2 consecutive failed `IMPLEMENT→TEST` cycles (4 failed steps total) → transition back to `PLAN` state, with the full failure history (failing tests, log tails, patch that was attempted) appended to the planning prompt

### 8.3 Crash Recovery

- Steps are written as `PENDING` before dispatch to any worker thread
- Worker threads write a heartbeat row update every 30 seconds during execution
- A reaper `@Scheduled` task (runs every 60s) reclaims steps where `heartbeat_at < NOW() - 90s`, resetting them to `PENDING` with `attempt + 1` (up to `maxAttempts`)
- On orchestrator restart: `@EventListener(ApplicationReadyEvent)` scans for `RUNNING` steps with no recent heartbeat and reclaims them
- Conversation history is persisted to the step row after every agent turn — a resumed worker re-loads the full history and continues from the last observation

### 8.4 Budget Enforcement

- **Per-step wall clock**: a `ScheduledExecutorService` future cancels the worker thread if `wallSec` exceeded
- **Per-step tool call counter**: incremented in the agent loop; step transitions to `ABORTED` if exceeded
- **Per-run iteration counter**: incremented each time IMPLEMENT is entered; run transitions to `ABORTED` if `maxIterations` exceeded
- All budget states are persisted — a resumed run respects remaining budget

### 8.5 Failure Classification and Handling

| Failure | Detection Point | Handling Strategy |
|---|---|---|
| Patch apply conflict (context mismatch) | `apply_patch()` returns non-zero; traceback in observation | Agent self-debugs: re-reads target file, reconstructs diff from updated context |
| Compilation error after patch | `run_command(["mvn","compile"])` exits non-zero | Compiler error output fed as next observation; Implementer retry |
| Test still failing after patch | `TestResult.passed == false` | Failure log tail appended to Implementer retry prompt; attempt counter incremented |
| Test suite timeout (scope too broad) | `run_command` hits `commandTimeoutSec` | Retry with narrowed scope: `-Dtest=<specific failing class>` |
| Build tool missing / wrong version | `run_command` exits 127 ("command not found") | RepoMapper re-runs and updates repo profile; step retried |
| Hallucinated file path | `read_file()` raises `FileNotFoundError` → traceback observation | Agent self-debugs: falls back to `list_files()` + `search_code()` to locate correct path |
| Sandbox policy violation | `ToolNotAllowedError` raised before subprocess executes | Step immediately → `POLICY_VIOLATION`; logged with skill name + attempted command; not retried; run → `ABORTED` |
| Executor pod crash (mid-step) | Worker heartbeat goes stale (> 90s) | Reaper reclaims step; workspace restored from `base_snapshot`; retried from clean state |
| Context window limit approaching | Token estimate exceeds threshold before Claude API call | Step → `ABORTED` with `BUDGET_EXCEEDED`; run budget marked exhausted |
| 2 consecutive IMPLEMENT→TEST failures | State machine consecutive-failure counter | Backtrack to PLAN state with full failure history; iteration counter incremented |

---

## 9) Security Model

### 9.1 K8s RBAC

```yaml
# orchestrator-sa: can read/write PostgreSQL secrets, read/write MinIO
# Cannot create pods, jobs, or access cluster resources
orchestrator-sa:
  rules:
    - apiGroups: [""]
      resources: ["secrets"]
      resourceNames: ["postgres-credentials", "minio-credentials", "claude-api-key"]
      verbs: ["get"]

# executor-sa: no K8s API access at all; filesystem access via PVC mount only
executor-sa:
  rules: []   # empty — executor needs no cluster permissions
```

### 9.2 NetworkPolicy

```yaml
# executor pods: deny all egress unconditionally
# (Maven dependencies are pre-installed in the image)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: executor-deny-egress
spec:
  podSelector:
    matchLabels: { app: codepilot-executor }
  policyTypes: [Egress]
  egress: []   # empty = deny all
```

Orchestrator egress: allow only PostgreSQL (port 5432), MinIO (port 9000), and the Claude API endpoint (port 443). All other egress denied.

### 9.3 Secret Handling
- Claude API key: K8s Secret, mounted as env var in the orchestrator only — never reaches the executor
- Repo credentials (for private repos): not needed for the benchmark (all public); for future use, short-lived tokens scoped to read-only repo access
- No secrets mounted in executor pods — by design

### 9.4 Tool Allowlist Enforcement (Defense in Depth)
Command allowlist is enforced at **two levels**:
1. Python-level: `run_command()` validates `cmd[0]` against `ALLOWED_COMMANDS` before calling `subprocess.Popen`
2. Pod-level: executor pod runs as non-root with no `sudo`/`su`; even if allowlist is bypassed, privilege escalation is impossible

---

## 10) Observability

### 10.1 Metrics (Prometheus + Grafana)

Exposed via Spring Boot Actuator + Micrometer (Java) and `prometheus_client` (Python):

```
# Platform quality
codepilot_run_total{status="success|failed|aborted"}
codepilot_pass_at_1_ratio          ← updated after each benchmark sweep
codepilot_run_duration_seconds{quantile="0.5|0.95|0.99"}

# Step-level
codepilot_step_duration_seconds{role, status}
codepilot_step_retries_total{role}
codepilot_backtrack_total
codepilot_agent_turns_total{role}

# Executor
codepilot_tool_calls_total{tool, status="success|error|timeout"}
codepilot_sandbox_violations_total{violation_type}
codepilot_workspace_snapshot_duration_seconds

# Scheduling
codepilot_step_queue_depth          ← count of PENDING steps
codepilot_step_heartbeat_reclaim_total
```

**Grafana dashboard (5 panels):** pass@1 over time, run duration distribution, step retry rates by role, tool call breakdown, executor timeout rate.

### 10.2 Structured Logging

Every log line is JSON with mandatory correlation fields:
```json
{
  "ts": "2025-10-15T14:23:01.123Z",
  "level": "INFO",
  "runId": "r-123",
  "stepId": "s-045",
  "role": "IMPLEMENTER",
  "attempt": 1,
  "event": "tool_call",
  "tool": "apply_patch",
  "exitCode": 0,
  "durationMs": 312
}
```

Full agent execution transcripts (every action + observation) are saved as immutable artifacts to MinIO for post-run analysis and debugging.

### 10.3 Run Timeline Artifact

Every completed run produces a `timeline.json` saved to MinIO:
```json
{
  "runId": "r-123",
  "task": "Fix NPE in StringUtils.strip() when input is null",
  "status": "DONE",
  "totalDurationSec": 287,
  "iterationsUsed": 2,
  "steps": [
    { "role": "MAP_REPO",   "durationSec": 14, "agentTurns": 3, "status": "SUCCEEDED" },
    { "role": "PLAN",       "durationSec": 22, "agentTurns": 4, "status": "SUCCEEDED" },
    { "role": "IMPLEMENT",  "durationSec": 91, "agentTurns": 7, "status": "SUCCEEDED", "attempt": 1 },
    { "role": "TEST",       "durationSec": 58, "exitCode": 0,   "status": "SUCCEEDED" },
    { "role": "REVIEW",     "durationSec": 29, "agentTurns": 3, "verdict": "APPROVE" },
    { "role": "FINALIZE",   "durationSec": 8,  "status": "SUCCEEDED" }
  ],
  "patch": "diff --git a/...",
  "testResult": {
    "passed": true,
    "failedTestsBefore": ["StringUtilsTest#testStripNull"],
    "failedTestsAfter":  []
  }
}
```

---

## 11) Evaluation Harness

### 11.1 Benchmark: BugFixBench-Java-25

A curated set of **25 real bug-fix tasks** from well-maintained open-source Java libraries with Maven build systems. All repositories have fast test suites (< 2 min for targeted test runs).

| Source Repo | Tasks | Bug Categories |
|---|---|---|
| Apache Commons Lang 3.12 | 8 | Null handling, string edge cases, boundary conditions |
| Apache Commons Collections 4.4 | 7 | Iterator contract, map behavior, null keys |
| Google Guava 31 (simple modules) | 6 | Preconditions, ranges, optional |
| Hand-crafted mutations (5 real repos) | 4 | Injected NPE + `@Test` that reproduces it |

**Task definition format** (one JSON file per task, committed to the repo):
```json
{
  "taskId": "lang-001",
  "repo":   "https://github.com/apache/commons-lang",
  "ref":    "commons-lang-3.12.0",
  "prompt": "Fix the NullPointerException in StringUtils.strip() when the input string is null. Add a regression test.",
  "failingTest": "org.apache.commons.lang3.StringUtilsTest#testStrip_StringString",
  "verificationCmd": ["mvn", "-q", "test", "-Dtest=StringUtilsTest#testStrip_StringString"],
  "knownFixSummary": "Add null guard before calling charAt(0)"
}
```

`knownFixSummary` is used only for human evaluation of patch quality — it is not given to the agent.

**Grading (automated):**
- **PASS**: `verificationCmd` exits 0 and the produced diff is non-empty
- **FAIL**: any other outcome (compilation error, test still fails, no patch produced, timeout)

### 11.2 Evaluation Script (`eval/run_bench.py`)

```python
for task in load_tasks("bench/tasks/"):
    run_id = api.submit_task(task)
    result = api.poll_until_done(run_id, timeout=1800)
    grade = grade_task(result, task)
    results.append({"taskId": task.taskId, "grade": grade, **result.metrics()})

print_report(results)   # generates eval_report.html + prints summary table
```

Each task is run **3 times independently** (different random seeds) to compute pass@1 and pass@3.

### 11.3 Target Metrics (Realistic for Solo Implementation)

| Metric | Target | Rationale |
|---|---|---|
| **Pass@1** | ≥ 50% (13/25) | Competitive with published Java bug-repair baselines |
| **Pass@3** | ≥ 65% (17/25) | Self-debugging across retries should improve coverage |
| **Median time-to-green** | < 8 min | Acceptable for a dev tool; dominated by test execution time |
| **Avg iterations to green** | ≤ 2.5 | Demonstrates effective first-attempt planning |
| **Backtrack rate** | < 20% | Planner quality check |

### 11.4 Resume-Ready Evidence Artifacts

After evaluation, the repo will include:
- `eval/eval_report.html`: per-task results table (pass/fail, duration, iterations, patch size)
- `eval/sample_runs/`: 3 timeline JSONs (one easy, one requiring backtracking, one SCAFFOLD)
- `eval/dashboard_screenshot.png`: Grafana dashboard during benchmark run
- `README.md`: architecture diagram, benchmark results table, one-command local demo setup

---

## 12) K8s Deployment

### 12.1 Manifests Structure

```
k8s/
├── orchestrator/
│   ├── deployment.yaml      (Spring Boot, resources: 500m/1Gi, replicas: 2)
│   ├── service.yaml         (ClusterIP + LoadBalancer for external access)
│   └── hpa.yaml             (minReplicas:1, maxReplicas:5, targetCPU:70%)
├── executor/
│   ├── deployment.yaml      (FastAPI, resources: 1cpu/2Gi)
│   ├── service.yaml         (ClusterIP only — not externally reachable)
│   ├── networkpolicy.yaml   (deny all egress)
│   ├── pvc.yaml             (RWX, 20Gi, for shared workspace volume)
│   └── hpa.yaml             (minReplicas:2, maxReplicas:10, targetCPU:60%)
├── postgres/
│   └── (managed service preferred; StatefulSet YAML provided for local)
├── minio/
│   └── statefulset.yaml     (dev only; swap to S3 endpoint in prod)
└── rbac/
    ├── orchestrator-sa.yaml
    └── executor-sa.yaml
```

### 12.2 Helm Chart

Single chart `charts/codepilot` covering both services:

```yaml
# values.yaml (key fields)
orchestrator:
  replicaCount: 2
  image: { repository: your-registry/codepilot-orchestrator, tag: latest }
  hpa: { minReplicas: 1, maxReplicas: 5, targetCPUUtilizationPercentage: 70 }
  env:
    CLAUDE_API_KEY_SECRET: claude-api-key

executor:
  replicaCount: 3
  image: { repository: your-registry/codepilot-executor, tag: latest }
  hpa: { minReplicas: 2, maxReplicas: 10, targetCPUUtilizationPercentage: 60 }
  networkPolicy: { denyEgress: true }
  resources:
    limits: { cpu: "1", memory: "2Gi" }

minio:
  enabled: true    # set false in prod; provide s3.endpoint instead

postgres:
  external: true
  jdbcUrl: "jdbc:postgresql://..."
```

### 12.3 Kustomize Overlays

- `overlays/dev`: MinIO + Postgres in-cluster, 1 replica each, relaxed resource limits — for `kind` or `minikube`
- `overlays/prod`: external managed Postgres + S3, strict NetworkPolicy, full HPA, resource limits enforced

---

## 13) Implementation Plan (10 Weeks, Solo)

### Phase 1 — Weeks 1–4: Working End-to-End MVP

**Goal**: a complete run that fixes a simple Java bug and produces a passing test report.

**Week 1–2: Control plane**
- Spring Boot project: REST API (`POST /v1/tasks`, `GET /v1/runs/{id}`, `GET /v1/runs/{id}/artifacts`)
- PostgreSQL schema: all 5 entities (Task, Run, Step, Artifact, Workspace)
- State machine: `INIT → MAP_REPO → PLAN → IMPLEMENT → TEST → REVIEW → FINALIZE → DONE`
- Worker thread pool: DB-row claiming with `SELECT FOR UPDATE SKIP LOCKED`, heartbeat writer

**Week 2–3: Execution plane**
- FastAPI executor: `/workspace/create`, `/workspace/run_code`, `/workspace/snapshot`, `/workspace/restore`
- Sandbox: restricted `exec()` + tool function injection (read_file, write_file, apply_patch, run_command, search_code, git_*)
- Command allowlist enforcement (Python-level + argument validation)
- MinIO client for workspace snapshots

**Week 3–4: Agent loop**
- Claude API client (Java `HttpClient` with retry on 429/503)
- System prompts for all 5 roles (RepoMapper, Planner, Implementer, Tester, Reviewer)
- Code block extraction (`\`\`\`python ... \`\`\`` parser) + structured output parsing (`<result>` tags)
- Conversation history persistence to step row
- Full end-to-end test: fix 3 hand-crafted bugs running locally (no K8s yet)

### Phase 2 — Weeks 5–7: Reliability + K8s Deployment

**Goal**: crash-safe, K8s-deployed, 2 concurrent runs without interference.

**Week 5: Reliability**
- Snapshot/restore protocol for idempotent step retries
- Backtracking logic: PLAN re-entry with failure context after 2 failed TEST steps
- Budget enforcement: wall-clock timer + tool call counter + iteration counter
- Heartbeat reaper: `@Scheduled` task + `@EventListener(ApplicationReadyEvent)` recovery scan
- Test: kill the orchestrator mid-run, restart, verify it resumes correctly

**Week 6: K8s deployment**
- Docker images for both services (multi-stage builds; Maven cache baked into executor image)
- K8s manifests: Deployments, Services, NetworkPolicy, RBAC, PVC, HPA
- Helm chart with `overlays/dev` and `overlays/prod`
- Deploy to local `kind` cluster; verify concurrent runs with workspace isolation

**Week 7: Observability**
- Prometheus metrics (Micrometer in Java; `prometheus_client` in Python)
- Grafana dashboard (5 panels as specified in §9.1)
- Structured JSON logging with mandatory correlation fields
- Run timeline artifact generation

### Phase 3 — Weeks 8–10: Benchmark + Polish

**Goal**: benchmark numbers on 25 tasks; public GitHub repo with demo.

**Week 8: Benchmark setup**
- Curate and commit all 25 task JSON files to `bench/tasks/`
- Pre-populate executor Docker image with Maven cache for all 4 benchmark repos
- Implement `eval/run_bench.py` evaluation script
- Run a baseline sweep (first-attempt only, no backtracking) to establish a floor

**Week 9: Tuning**
- Analyze failure patterns from baseline: which roles fail most? what error types?
- Refine system prompts for the 2 most-failing roles
- Enable backtracking; re-run benchmark; measure improvement
- Add SCAFFOLD task type (Maven module + CI stub + skeleton test)

**Week 10: Polish**
- Generate `eval_report.html` from evaluation results
- Write README: architecture diagram (Mermaid), benchmark table, one-command local demo (`make demo`)
- Add unit tests for: state machine transitions, heartbeat reaper, sandbox validator, structured output parser
- Final benchmark run; record metrics for resume bullets

---

## 14) Resume Positioning

### 14.1 What This Project Demonstrates

| Skill Area | Concrete Evidence |
|---|---|
| **Multi-agent systems** | 5-role pipeline with CodeAct-inspired executable code actions; agents self-debug by observing sandbox execution results across multi-turn interactions |
| **Plugin-based platform design** | Skills Layer with typed manifest, policy enforcement, execution routing (JAVA_LOCAL vs PYTHON_EXECUTOR), and per-skill Prometheus metrics |
| **Distributed systems (application layer)** | Crash-safe state machine; at-least-once step execution via DB-row claiming; heartbeat + reaper; idempotent snapshot/restore |
| **Cloud-native platform engineering** | K8s Deployments + HPA + NetworkPolicy + RBAC; Helm chart; Kustomize overlays for dev/prod |
| **Reliability engineering** | Budget enforcement (wall clock + tool calls + iterations); backtracking; `SELECT FOR UPDATE SKIP LOCKED`; `ApplicationReadyEvent` recovery; 10-category failure classification |
| **Observability** | Prometheus metrics with Micrometer + per-skill telemetry; Grafana dashboard; structured correlation logs; immutable artifact provenance |
| **Polyglot systems design** | Java control plane + Python execution plane with a versioned HTTP contract |
| **Quantitative evaluation** | pass@1, pass@3, time-to-green on 25 real Java bug-fix tasks from Apache Commons + Guava |
| **Research to engineering** | Translates an ICML 2024 paper (CodeAct) into a working engineering system with clear concept mapping |

### 14.2 Resume Bullets (fill in numbers after benchmark)

```
• Built a K8s-native multi-agent platform (Java + Python) that automates Java bug repair
  using CodeAct-inspired executable code actions; agents autonomously self-debug by observing
  sandbox execution results across multi-turn Claude interactions, achieving X% pass@1 on a
  curated benchmark of 25 real Apache Commons / Guava bug-fix tasks.

• Designed a crash-safe, idempotent orchestration layer: DB-row step claiming with
  SELECT FOR UPDATE SKIP LOCKED, heartbeat-based crash detection and recovery,
  snapshot/restore for idempotent retries, and backtracking to re-planning after repeated
  test failures — deployed on Kubernetes with HPA-scaled executor pods.

• Enforced defense-in-depth security: NetworkPolicy deny-egress on executor pods, K8s RBAC
  with least-privilege service accounts, Python-level command allowlist, and restricted exec()
  sandbox — Claude API key never reachable from the execution plane.

• Instrumented with Prometheus + Grafana (pass@1 trend, step retry rates, tool call
  breakdown) and structured correlation logs; median time-to-green: Y min, avg iterations
  to green: Z.
```

---

## Appendix A: Structured Artifact Schemas

**RepoMap:**
```json
{
  "buildSystem": "maven",
  "buildCmd":    ["mvn", "-q", "compile"],
  "testCmd":     ["mvn", "-q", "test"],
  "lintCmd":     ["mvn", "-q", "checkstyle:check"],
  "modules":     ["core", "api"],
  "keyFiles":    ["src/main/java/org/apache/commons/lang3/StringUtils.java"],
  "testFiles":   ["src/test/java/org/apache/commons/lang3/StringUtilsTest.java"]
}
```

**PatchCandidate:**
```json
{
  "diff": "diff --git a/src/main/java/.../StringUtils.java b/...\n@@...",
  "touchedFiles": ["src/main/java/.../StringUtils.java",
                   "src/test/java/.../StringUtilsTest.java"],
  "rationale": "Add null guard before charAt(0) call; add regression test testStripNull"
}
```

**TestResult:**
```json
{
  "passed": true,
  "cmd": ["mvn", "-q", "test", "-Dtest=StringUtilsTest#testStrip_StringString"],
  "exitCode": 0,
  "failedTestsBefore": ["StringUtilsTest#testStrip_StringString"],
  "failedTestsAfter":  [],
  "durationSec": 43
}
```

**Review:**
```json
{
  "verdict": "APPROVE",
  "patchLOC": 12,
  "policyChecks": {
    "noDisabledTests": true,
    "noHardcodedSecrets": true,
    "patchWithinSizeLimit": true,
    "noNewCompilationWarnings": true
  },
  "notes": "Clean fix; regression test covers both null and empty string cases."
}
```

---

## Appendix B: Default Budgets (Configurable per Task)

```
maxIterations:         6     (IMPLEMENT+TEST cycles before ABORTED)
maxWallSec:         1800     (30 min per run)
maxToolCallsPerStep:  10     (agent turns per step)
commandTimeoutSec:   300     (per run_command() call)
maxPatchLOC:         300     (Reviewer policy limit)
heartbeatIntervalSec: 30
heartbeatStaleSec:    90
```
