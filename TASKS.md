
# Active focus — TC quality validation triage (2026-05-11)

Post-philosophy-iteration TC run against `jb/main 2f21517a` (the merge
that synced 20 commits of accumulated `origin/main` onto `jb/main` for
the first time since 2026-05-05):

| TC build | Result | Triage |
|---|---|---|
| `mcp_steroid_BuildPlugin` (#946345314) | ✅ SUCCESS 5m12s | — |
| `mcp_steroid_IjPluginTest` (#946345319, Mac/Linux/Win matrix) | ✅ SUCCESS 24m40s | — |
| `mcp_steroid_PromptTest` (#946345329, 8-IDE matrix) | ❌ FAILURE 33m43s | **New regression — fix this iteration.** |
| `mcp_steroid_IntegrationTests_TestIntegrationBuild` (#946345331) | ❌ FAILURE 11m01s | **Documented baseline — leave failing.** |

## Problem 1 — `PromptTest` cascade: kotlinc classpath missing `:mcp-steroid-server` (NEW REGRESSION)

- 8/8 IDE matrix builds failed identically (177–308 `*KtBlocksCompilationTest.testBlock00X CompilesOnIdea` per build).
- Root error in every failure: `ApplyPatch.kt:15:32: error: unresolved reference 'server'. import com.jonnyzzz.mcpSteroid.server.ApplyPatchHunk` — the per-block kotlinc subprocess can't find `ApplyPatchHunk`, which now lives in `:mcp-steroid-server`.
- Caused by commits `acc5650b` ("move tools and resources definitions up to a parent module") + `b1942f1b` ("move classes in packages"), landed on `origin/main` 2026-04-19. Last green PromptTest on TC was build 941393378 on 2026-05-05 (built from `aa1d166d`) — *before* the extraction series reached `jb/main`'s tracked branch. My 2026-05-10 jb-merge brought all of it across in one step.
- Fix: add `:mcp-steroid-server` to whatever classpath the `KtBlocksCompilationTest` kotlinc subprocess is given (likely `prompts/build.gradle.kts` or `prompt-generator`'s codegen wiring).
- Validation: `./gradlew :prompts:test` locally must pass before pushing.

## Problem 2 — `test-integration` Gemini-skip-as-failure (PRE-EXISTING BASELINE — do not touch in this iteration)

- 6 `CliGeminiIntegrationTest.*` tests fail with `AssumptionViolatedException: Gemini API key not found`. Per CLAUDE.md MEMORY, these should report as ignored (`DockerGeminiSession.skipTestWhenKeyMissing = true`) but `BasePlatformTestCase` → `JUnit38ClassRunner` routes the throwable through `fireTestFailure` instead of recognising the assumption-as-ignore semantics that JUnit 4's runner provides.
- Pre-existing: the exact "Tests failed: 6, passed: 100, ignored: 1" line has been the result of *every* `test-integration` run on TC since at least 2026-04-28. Identical today. Not caused by the philosophy iteration; not in scope for this iteration per user direction.
- Tracked separately for a future cycle that's willing to restructure the test-class hierarchy off `BasePlatformTestCase` for the Gemini variant.

## Problem 3 — EAP-IDE `OpenProjectTask { }` target-25 vs. test target-21 (PRE-EXISTING BASELINE)

- After the Problem 1 fix landed (TC build #946643358), PromptTest dropped from 1682 failures → 4. The 4 remaining are all in EAP IDEs (2 in `idea_eap`, 2 in `clion_eap`) and share the same error: `cannot inline bytecode built with JVM target 25 into bytecode that is being built with JVM target 21`. The two files involved are `prompts/src/main/prompts/openProject/open-via-code.md` and `prompts/src/main/prompts/openProject/overview.md` — both reference the inline function `OpenProjectTask { }`, which the EAP IDE compiles with JVM target 25.
- History on `mcp_steroid_PromptTests_PromptTest_idea_eap_Linux_amd64`: this surfaced as "1 new failure" on the 2026-05-04 run (#940678137), got buried under the ApplyPatchHunk cascade on 2026-05-10, and is now visible after the classpath fix unblocked everything else. Not caused by the philosophy iteration and not caused by the classpath fix — pre-existing IDE-EAP drift.
- Fix when scope allows: bump `KotlincCommandLineBuilder.DEFAULT_JVM_TARGET` from `21` to match the EAP, OR feature-flag the EAP-specific tests off until the test-target catches up. Both are non-trivial.

# Active focus — codify the agent-first design tenets across all .md (2026-05-10)

Goal: every CLAUDE.md / AGENTS.md / agent-facing prompt resource in the repo
explicitly reinforces three tenets that today are only implicit. After this
iteration, an agent (or a human contributor) can read any one of these files
and recover the same design philosophy.

## Tenets (canonical wording will land in `docs/PHILOSOPHY.md`)

1. **Minimal MCP tool surface.** MCP Steroid intentionally maintains a small
   set of `steroid_*` tools (today: 10). New tools are not the lever for
   "agents deliver more" — better prompts and better recipes are. A new tool
   is added only when the IntelliJ-API path is genuinely intractable AND
   reviewer quorum agrees.
2. **Power lives in prompts and direct IntelliJ API usage.** Improvements
   come from richer tool descriptions, richer `mcp-steroid://` skill
   resources, and teaching the agent to call IntelliJ's native APIs
   (`FilenameIndex`, `JavaPsiFacade`, `ProjectTaskManager`, `XDebuggerUtil`,
   …) inside `steroid_execute_code`. Don't wrap APIs in helpers; teach the
   API as IntelliJ exposes it.
3. **`McpScriptContext` methods are last-resort.** The helpers exposed
   inside `steroid_execute_code`'s Kotlin runtime (`project`, `params`,
   `disposable`, `println()`, `printJson()`, `progress()`,
   `waitForSmartMode()`) shall not grow casually. A new context method
   requires (a) the IntelliJ-native path is genuinely intractable, (b)
   explicit reviewer consensus.

These harmonise the blog post ("comprehensive bridges to existing systems",
"agents follow the same processes as humans, no shortcuts") with the
strategy page ("Give AI the whole IDE, not just the files"): the **MCP tool**
surface stays minimal; the **IntelliJ capability** surface stays full,
exposed via `steroid_execute_code` + prompt resources.

## Decisions (locked 2026-05-10)

- Canonical home: **new** `docs/PHILOSOPHY.md`. Linked from root CLAUDE.md +
  AGENTS.md, from each per-folder agent guide, and mirrored as
  `mcp-steroid://skill/design-philosophy` so agents can read it at runtime
  via `steroid_fetch_resource`.
- Voice: **AI agents first, humans second** — imperative ("Don't propose new
  tools casually. Teach the agent to call IntelliJ APIs directly.") with a
  trailing rationale paragraph for human contributors.
- Cadence: per-batch `run-agent.sh` quorum (codex + claude + gemini), commit
  per batch. Same pattern as the rest of TASKS.md.
- Scope: docs + prompt resources only. Tool-count contract test deferred —
  see "Follow-ups" below.

## RLM-style iteration plan

Each batch: small set of related edits → 3-agent quorum review (codex /
claude / gemini via `run-agent.sh`) → adjust → commit. Each batch is
independently revertable.

### Batch 1 — Canonical tenets
- [ ] Create `docs/PHILOSOPHY.md` (agent-first imperative voice + human
  rationale + cross-links to RLM, blog, strategy page).
- [ ] Create `prompts/src/main/prompts/skill/design-philosophy.md` mirroring
  it (so `steroid_fetch_resource` can deliver it).
- [ ] Cross-link from root `CLAUDE.md` + `AGENTS.md` (one paragraph + the
  link).
- [ ] Quorum review.
- Success: an agent reading any of the three locations recovers the same
  three tenets. Quorum approves wording.

### Batch 2 — Per-folder agent guides
- [ ] `ij-plugin/CLAUDE.md` — preface "Adding new MCP tools" with the
  T1 question ("Can this be done via `steroid_execute_code` + direct
  IntelliJ APIs?"), and the McpScriptContext-expansion gate.
- [ ] `prompts/AGENTS.md` — add the *why* under the ProcessBuilder ban
  (it's not just a rule, it's the IntelliJ-API tenet) and an explicit
  "McpScriptContext stays narrow" note.
- [ ] `test-integration/AGENTS.md` — promote the existing "Configuring the
  IDE — always via `mcpExecuteCode`, never via XML" section into a top-level
  "Design principles" block that names the three tenets.
- [ ] `test-experiments/CLAUDE.md` — minimal cross-link only; this module
  is short and already well-aligned in spirit.
- [ ] Quorum review.
- Success: every per-folder guide states the tenets that apply to its
  scope, with a back-link to `docs/PHILOSOPHY.md`.

### Batch 3 — Runtime prompt resources (delivered to agents at runtime)
- [ ] `prompts/src/main/prompts/skill/mcp-steroid-info.md` — short paragraph
  that names the tenets and points to the new design-philosophy resource.
- [ ] `prompts/src/main/prompts/skill/execute-code-tool-description.md` —
  reinforce: prefer direct IntelliJ APIs over inventing new context methods;
  prefer `steroid_apply_patch` over multi-file `Edit` chains; prefer richer
  prompts over new tools.
- [ ] `prompts/src/main/prompts/ide/apply-patch.md` — reorder so the
  dedicated `steroid_apply_patch` tool leads, and the script-context
  `applyPatch { }` DSL is demoted to "fallback when patch + other API
  work share a script."
- [ ] Update `prompts/src/main/prompts/skill/coding-with-intellij.md`
  if it carries any wording that could be read as encouraging context-method
  expansion (audit-first, edit only if needed).
- [ ] Quorum review.
- Success: agents that read these via `steroid_fetch_resource` recover the
  tenets; recipes still teach IntelliJ APIs directly with no new wrappers.

### Batch 4 — Public + meta docs
- [ ] `README.md` — fix the "9 tools" → 10; add a one-paragraph design
  preamble that links to `docs/PHILOSOPHY.md`.
- [ ] `website/CLAUDE.md` — add a one-line note distinguishing site docs
  (end-user) from agent docs (this iteration's scope).
- [ ] Verify the link graph: every `.md` updated above resolves and
  cross-links bidirectionally.
- [ ] Final quorum review against the whole branch (codex + claude +
  gemini, 200-word verdict each).
- Success: net new contributor or agent can land on README, AGENTS.md, or
  any prompt resource and recover the philosophy.

## Follow-ups (deferred from this iteration)

- [ ] Add a contract test (`McpToolSurfaceContractTest` in
  `:mcp-steroid-server` or `:ij-plugin`) that asserts the registered
  `steroid_*` tool surface matches `EXPECTED_STEROID_TOOL_NAMES` exactly,
  so a stray addition fails CI. Codifies T1 mechanically.
- [ ] Same shape for `McpScriptContext` method count (e.g. assert the
  public method set is unchanged unless intentional).
- [ ] Consider porting the `mcp2` Executor/Request-DTO refactor (see
  earlier section in this file) — orthogonal but reinforces "narrow
  surfaces" tenet.

# MCP4

## Generic stdio MCP server — extract from npx-kt

Goal: consolidate stdio MCP transport + protocol code in `mcp-stdio` (or `mcp-core`).
Introduce a generic, transport-pluggable MCP server that wraps `McpServerCore`,
mirroring how `mcp-http`'s `McpHttpTransport` does it for HTTP.
`npx-kt` proxy logic stays as-is for now — breaking changes there are acceptable
later but not in scope here.


### Not in scope (per user)
- npx-kt's `StdioServer.kt` left untouched. Migrating the proxy to the new
  `McpStdioServer + McpServerCore` model would require dynamic registration of
  proxy-discovered tools/resources into `McpServerCore.toolRegistry` on every
  registry refresh — a real proxy refactor, not a transport one.


### npx-kt — real stdio MCP server (in progress, 2026-05-10)

Goal: turn npx-kt into a first-class stdio MCP server backed by `:mcp-stdio` +
`:mcp-steroid-server`'s `McpSteroidTools`. The legacy proxy/registry/beacon
stack stays in tree but unreachable from `main()` until real handlers land.

#### Done
- [x] **Step 1 — boot `McpStdioServer` from `npx-kt/main()`** with tools
  registered via `StubMcpSteroidTools` (every `handler<T>()` throws
  `TODO("not yet ready: …")`). Old proxy startup is moved to `legacyProxyMain`
  and unreferenced. (`npx-kt/build.gradle.kts`,
  `npx-kt/.../proxy/server/StubStdioMcpServer.kt`,
  `npx-kt/.../proxy/server/StubMcpSteroidTools.kt`,
  `npx-kt/.../proxy/Main.kt`)
- [x] **Step 2 — `integrationTest` source set** mirroring `:ij-plugin`. Spawns
  `bin/mcp-steroid-proxy` from `installDist`, exchanges NDJSON JSON-RPC over
  stdio. Asserts: initialize handshake, `tools/list` covers all 10 steroid_*
  tools, `prompts/list`, `resources/list`, `ping`, method-not-found error code,
  notification silence. Run via `./gradlew :npx-kt:integrationTest`. (7/7 pass.)
- [x] **Logback as the slf4j impl (stderr-only).** `runtimeOnly` on
  `logback-classic`. `npx-kt/src/main/resources/logback.xml` pins a single
  ConsoleAppender → System.err. No more "No SLF4J providers found" noise.
- [x] **`main()` — swap `System.out` → `System.err`**. First action of
  `main()`: capture `System.in` + `System.out` into local refs, run
  `System.setOut(System.err)`, pass the saved refs to
  `runStubStdioMcpServer(input = …, output = …)`. Stdout is now exclusively
  MCP NDJSON frames; logback + stray prints land on stderr.
- [x] **stdout-cleanliness integration test** (host + Docker variants).
  Asserts every non-blank stdout line parses as a JSON-RPC 2.0 envelope.
  Host variant covers whichever OS the test JVM runs on (Mac/Linux/Windows).
  Docker variant uses a dedicated `mcp-cli` Dockerfile under
  `test-helper/src/main/docker/` so all test containers go through the same
  test-helper Docker pipeline. Windows coverage TODO when a Windows runner
  exists.
- [x] **`Cli{Claude,Codex,Gemini}IntegrationTest`** — Docker AI agent
  registers npx-kt as a stdio MCP, runs a "list MCP tools" prompt, asserts the
  agent enumerated every tool in `EXPECTED_STEROID_TOOL_NAMES`. Tools list
  only — no invocations (handlers TODO). Required infra changes:
  - `temurin-21-jre` added to `claude/codex/gemini-cli` Dockerfiles via the
    Adoptium APT repo (matches `:test-integration:ide-base`'s pattern).
  - `AiAgentSession.containerDriver: ContainerDriver` exposed so tests can
    `copyToContainer(installDist, "/tmp")` before registering the stdio MCP.
  - `DockerGeminiSession` now sets `GEMINI_CLI_TRUST_WORKSPACE=true` —
    Gemini CLI's new trusted-folder check otherwise rejects `--approval-mode
    yolo` in headless mode (exit 55).
- [x] **Codex review feedback applied** (run-agent.sh review on 2026-05-10):
  - `StubMcpSteroidTools.handler()` throws `UnsupportedOperationException`
    instead of `TODO()` so it goes through `McpToolRegistry`'s
    `catch (Exception)` path as `ToolCallResult(isError=true)` rather than
    escaping as a `NotImplementedError` and tearing down the stdio server.
  - `StdioMcpProcess.drainNoMore(timeoutMs)` lets the
    "notifications-without-id receive no response" test fail loudly on stray
    frames; the previous version silently discarded them.
  - `EXPECTED_STEROID_TOOL_NAMES` shared between protocol-level and
    agent-level integration tests so a missing tool surfaces from both sides.
  - Empty `catch (_: Exception) {}` in `StdioMcpProcess.close()` replaced
    with a `System.err.println` (project policy: no empty catch).
  - Stale "default-jre-headless" comment in `NpxKtMcpInstaller` updated to
    Temurin 21.

#### Step 3 — move/consolidate npx-kt → mcp* modules (deferred)

The npx-kt module mixes (a) MCP transport/framing, already covered by
`:mcp-core` + `:mcp-stdio`, with (b) proxy/discovery/aggregation. After the
real handlers land, prefer this layout:

| Class / file                              | Move to                | Notes                                      |
|-------------------------------------------|------------------------|--------------------------------------------|
| `npx-kt/.../proxy/StdioServer.kt`         | **delete**             | Superseded by `:mcp-stdio` `McpStdioServer`. |
| `npx-kt/.../proxy/Protocol.kt`            | rewrite as `McpTool` impls in `:mcp-steroid-server` (or new `:mcp-steroid-proxy`) | Aggregator tools become real `McpTool`s; the per-method `when (method)` branch goes away. |
| `npx-kt/.../proxy/UpstreamClient.kt`      | `:mcp-http` (as a *client*) or new `:mcp-http-client` | HTTP MCP client (talks to `localhost:NNNN/mcp`) is reusable beyond the proxy. |
| `npx-kt/.../proxy/SseParser.kt`           | `:mcp-http`            | Generic SSE framing.                       |
| `npx-kt/.../proxy/ServerRegistry.kt`      | stays in `:npx-kt`     | Discovery is proxy-specific.               |
| `npx-kt/.../proxy/NpxBeacon.kt`           | stays in `:npx-kt`     | Telemetry is proxy-specific.               |
| `npx-kt/.../proxy/TrafficLogger.kt`       | stays in `:npx-kt`     | Traffic capture is proxy-specific.         |
| `npx-kt/.../proxy/UpdateCheck.kt`         | stays in `:npx-kt`     | Self-update is proxy-specific.             |
| `npx-kt/.../proxy/Config.kt`              | stays in `:npx-kt`     | Proxy config — only npx-kt reads it.       |
| `npx-kt/.../proxy/Constants.kt`           | split — keep `BeaconEvents` / `AGGREGATE_TOOL_*` here, drop `SESSION_HEADER` (lives in `:mcp-http`). | |

Once `UpstreamClient` lives in a shared module, the `handler()` overrides in
`StubMcpSteroidTools` are replaced one-by-one with concrete handlers that
delegate to upstream IDEs discovered by `ServerRegistry`. At that point delete
`legacyProxyMain` from `Main.kt`.

### Pending — port `mcp2` Executor/Request DTO refactors

`origin/mcp2` (34 commits ahead, last touch 2026-04-27) carries an
architectural pattern that did not land on main during the
`:mcp-steroid-server` extraction series. Worth porting in a separate PR
before the branch is deleted.

| mcp2 commit | Idea |
|---|---|
| `3ca06d65` | Per-tool `Request` data classes + `parse(args)` helpers — typed parsing at the call boundary, replacing inline `args["foo"]?.jsonPrimitive?.content` plumbing inside every `call()`. |
| `74851ffa` | Per-tool `fun interface FooExecutor` with a tool-specific signature (`ListWindowsExecutor.execute(): ToolCallResult`, `ExecuteCodeExecutor.execute(req, rawArgs, progress): …`) so the metadata stays decoupled from the IDE-dependent body. |
| `57332c39` | Co-locate schema + description + `parseRequest` + `Request` in each handler; `parse(args)` takes non-null `JsonObject` (one missing-arguments check at the call site). |
| `1cd12280` | Split each handler into `FooToolHandler(executor: FooExecutor)` (metadata + delegation) + `FooExecutorImpl` (IntelliJ-platform body). |
| `6a90433d` | Mock-executor tests in `:mcp-steroid-server/src/test` — plain JUnit 5 + `runBlocking`, no `BasePlatformTestCase`, fast delegation coverage that runs without the IDE fixture. |
| `26ef4a90` | "expose root resource index only" — narrow the `:mcp-steroid-server` resource API to a single root index entry point. |

Once these patterns are ported, `origin/mcp2` and the local `mcp2` branch
can be deleted. Until then keep the branch reachable.

### Backlog (carried over)
- [ ] add assert that mcp-core coroutines library is the same as in IntelliJ
- [ ] add check that slf4j works in IntelliJ and logs are not lost
- [ ] com.jonnyzzz.mcpSteroid.thisLogger should be internal to avoid usage from IntelliJ plugin code
- [ ] `withTimeoutOrNull(5_000L) { Observation.awaitConfiguration(project) }` -- no need for timeout around patch application handler
- [ ] `CLAUDE_FETCH_RESOURCE_TOOL = "mcp__mcp-steroid__steroid_fetch_resource"` the incorrect named entities, use Spec to refer to them
- [ ] project name to project resolution and error handling
- [ ] process cancelled exception must be handled in the HTTP server level
- [ ] review TODO-APPLY-PATCH.md records
- [ ] deprecate ActionDiscoveryToolHandler
- [ ] VisionService -> IntelliJ Service
- [ ] OpenProject does not log to execution service
- [x] include `--scope user` to Claude default configuration suggestion
- [ ] declutter VcsRefresh and related features which may cause problems with tests
- [ ] carefully review apply patch code with respect to threading, locks, VFS, EDT (or just drop it)
- [ ] Input should use direct window_id instead of screenshot_execution_id
- [ ] generate the necessary indexes around the Prompts to avoid linear scan

# TASKS

Current focus: make MCP Steroid measurably better than vanilla agent runs on DPAIA Maven and Gradle projects by reducing tokens, tool errors, and wall-clock time.

## Guardrails

- Do not add methods to `McpSteroid*` interfaces.
- Do not add MCP tools.
- Prefer MCP server behavior, prompt resources, and DPAIA prompt/case improvements.
- Keep changes measurable with DPAIA metrics: tokens, tool calls, tool errors, native edit calls, apply-patch usage, and wall time.
- Run Docker-backed `:test-integration` / `:test-experiments` tests one at a time.

## Methodology

- Use Karpathy-style autoresearch: one narrow hypothesis, one change, one measured run, one evidence note.
- Use RLM-style context control: grep/partition first, then read only the relevant files.
- Use `run-agent.sh` reviews for non-trivial direction changes. Require three reviews and consensus before selecting the next low-hanging fruit.
- Keep run-agent artifacts outside this repository unless explicitly asked to preserve them.

## Pending — IMPROVEMENTS.md harness rollout (separate PR)

The two-task prompt + per-agent reflection harness landed on
`FindDuplicatesPromptTest` in the issue-33 PR (see CLAUDE.md → "IMPROVEMENTS.md
harness — agent self-feedback for prompt tuning"). The pattern is generic; we
should extend it across every test-integration prompt-style test so each one
produces a per-agent reflection artifact.

- [ ] **Audit prompt-style tests in `:test-integration:test`.** Likely
  candidates: `ReferencesSearchPromptTest`, `FilenameIndexPromptTest`,
  `PsiClassLookupPromptTest`, `MavenRunnerAdoptionTest`,
  `ResourceReadingTest`, `WhatYouSeeTest`'s `toolPreference`. Confirm which
  of them run an agent against an open prompt (vs. infrastructure smoke).
- [ ] **For each prompt test, add Task 2 (reflection)** to the prompt and
  snapshot the `<<<IMPROVEMENTS>>>...<<<END_IMPROVEMENTS>>>` block to
  `test-integration/build/improvements/IMPROVEMENTS-<test>-<agent>.md`.
  Reuse the helper pattern in `FindDuplicatesPromptTest` (`extractImprovementsBlock`,
  `saveImprovements`) — likely worth promoting to a shared helper in
  `test-integration/src/main/.../integration/tests/`.
- [ ] **Iteration cadence per test**: 5×Claude → 3×Codex → 3×Claude → 3×Codex
  (the `find-duplicates` cadence). Each cycle reads the IMPROVEMENTS files,
  applies prompt-only tweaks, re-runs.
- [ ] **Land in a separate PR.** Wait until the issue-33 PR ships so the
  harness contract has stabilized; then propose the rollout as one self-contained
  change per prompt test (or one umbrella PR if the helper extraction is cohesive).
- [ ] Update CLAUDE.md when rollout completes to drop the "currently wired into
  FindDuplicatesPromptTest" caveat.

## Pending — Reflection audit follow-up (issue #33)

Audit on 2026-05-07 across `prompts/src/main/prompts/**/*.md` for `Class.forName`,
`getDeclaredField`, `getDeclaredMethod`, `setAccessible`, `Method.invoke`,
`java.lang.reflect.*`, `kotlin.reflect.full.*`. All hits in **prose** are policy
text and are intentional (the new reflection-policy guidance added in
`mcp-steroid-info.md`, `skill/coding-with-intellij.md`,
`skill/coding-with-intellij-patterns.md`, and `ide/find-duplicates.md`). The hits
that need investigation are in fenced ` ```kotlin ``` ` blocks — actual recipes
that ship reflection to agents. Each gets its own dedicated `run-agent.sh`
investigation (research only; no fixes applied here).

- [ ] **`lsp/hover.md` lines 88, 93** — uses
  `targetElement.javaClass.methods.find { it.name == "getType" }?.invoke(targetElement)`
  to read the element's type for `KtProperty`/`KtParameter`/`PsiVariable`. Agent
  ships duck-typed reflection across language plugins.
  - Investigation prompt: research the typed alternatives in `~/Work/intellij`.
    For Kotlin: `org.jetbrains.kotlin.psi.KtProperty.typeReference?.text`,
    `KtParameter.typeReference?.text`, or the analysis-API
    `KaSession.getReturnKtType(symbol)`. For Java: `PsiVariable.type.canonicalText`.
    Confirm both APIs are on the IDEA Ultimate `steroid_execute_code` classpath
    (Kotlin K2 analysis API may need `org.jetbrains.kotlin` plugin module). Report
    the recommended typed branch per language and a single combined snippet that
    drops `javaClass.methods.find`.
  - Out of scope here: editing `lsp/hover.md`. Land the recipe replacement in a
    separate change with its own KtBlock + in-process tests.

- [ ] **`lsp/signature-help.md` lines 82, 88, 96, 112** — uses the same
  `javaClass.methods.find { ... }?.invoke(...)` pattern for `getParameterList`,
  `getValueParameterList`, `getParameters`, `getType`, `getReturnType`,
  `getReturnTypeReference`. This is more pervasive than `hover.md`: four
  reflection sites in one snippet, all wrapped in `try { ... } catch (e: Exception)`
  that hide failures.
  - Investigation prompt: research `~/Work/intellij` for the right typed APIs:
    Kotlin `KtNamedFunction.valueParameters`, `KtNamedFunction.typeReference`;
    Java `PsiMethod.parameterList.parameters`, `PsiMethod.returnType`. Decide
    whether the recipe should branch on `is KtNamedFunction` / `is PsiMethod`
    or use `PsiMethodCallExpression`-style resolution. Confirm the Kotlin K2
    analysis API surface (if needed) for type rendering and that `try/catch`
    can be removed once the typed path covers both languages.
  - Out of scope here: editing `lsp/signature-help.md`. Replacement recipe goes
    in a separate change with KtBlock + in-process tests.

The two recipes above are the ONLY reflection-using `kotlin` blocks in
`prompts/src/main/prompts/`. The `coding-with-intellij-patterns.md` "Path 2"
fallback example was removed during issue #33 cleanup; the only surviving
fallback is now the prose recommendation to use `required_plugins` instead of
reflection.

## Completed This Iteration

- [x] DPAIA arena prompt cleanup: remove stale `applyPatch {}` DSL guidance and duplicate MCP-mode prompt rules from `ArenaTestRunner.buildPrompt()`.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaTestRunner.kt`
  - Consensus: Claude and Codex reviewers selected this as the next low-hanging fruit; Gemini selected Gradle prompt resources as a follow-up.
  - Expected effect: lower input tokens, fewer contradictory edit-path choices, fewer slow `steroid_execute_code` patch attempts.
  - Validation: `./gradlew :test-experiments:compileTestKotlin --warning-mode all` passed.

- [x] Measured one Claude+MCP DPAIA scenario after the prompt cleanup.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 111s.
  - Tool mix: 2 agent `steroid_execute_code` calls in raw metrics, 3 Read, 1 Glob, 1 Grep, 2 native Edit, 2 Bash, `steroid_apply_patch` not used.
  - Lesson: correctness is good, but the edit prompt still needs a stronger dedicated `steroid_apply_patch` cue for import+method edits.

- [x] Tightened the next low-hanging arena edit guidance from the measured run.
  - Kept the `steroid_apply_patch` JSON example on `file_path`, matching `ApplyPatchToolHandler` and the generated tool description.
  - Explicitly classified "imports plus method" as a multi-hunk patch.
  - Review: first 3-agent pass blocked on an accidental `path`/`file_path` mismatch; after correction, Claude/Codex/Gemini approved the diff and agreed the next low-hanging fruit is to re-run the same scenario and measure native Edit reduction.

- [x] Measured the corrected `steroid_apply_patch` prompt on the same Claude+MCP DPAIA scenario.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-001705-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 123s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 12 total calls, 3 MCP calls, 9 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 2 Grep, 0 native Edit, 3 Bash, 0 errors.
  - Delta versus the prior run: native Edit 2 -> 0 and apply-patch false -> true; runtime 111s -> 123s because verification used 3 Bash Maven calls instead of 2.
  - Lesson: edit-path guidance worked. The next low-hanging prompt issue is verification: avoid duplicate Maven/Bash checks after a successful IDE build plus targeted test.

- [x] Route prompt resources to the dedicated `steroid_apply_patch` tool for multi-site literal edits.
  - Review artifacts: `/tmp/mcp-steroid-review/runs-next-20260427/`.
  - Consensus: Claude, Codex, and Gemini selected `update-apply-patch-tool-description-routing` before Gradle-resource work.
  - Files: `prompts/src/main/prompts/skill/execute-code-tool-description.md`, `prompts/src/main/prompts/skill/execute-code-overview.md`, `prompts/src/main/prompts/skill/coding-with-intellij.md`, `prompts/src/main/prompts/ide/apply-patch.md`, `prompts/src/test/kotlin/com/jonnyzzz/mcpSteroid/prompts/PromptRoutingContractTest.kt`.
  - Expected effect: stop teaching the slower `steroid_execute_code` + script-context `applyPatch` route as the default after DPAIA showed the dedicated MCP tool applies multi-file patches in tens of ms.
  - Validation: `:prompts:test --tests '*PromptRoutingContractTest*' --tests '*MarkdownArticleContractTest*' --tests '*ExecuteCodeToolDescriptionKtBlocksCompilationTest*' --warning-mode all` passed via IntelliJ Gradle runner.

- [x] Make `steroid_apply_patch` save touched documents before returning and cover disk persistence with integration tests.
  - Reference checked: after updating `~/Work/intellij`, IntelliJ's `ApplyTextFilePatch.updateDocumentContent()` uses `Document.setText(...)` followed by `FileDocumentManager.saveDocument(document)`.
  - Files: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ApplyPatch.kt`, `ApplyPatchTest.kt`, `ApplyPatchToolIntegrationTest.kt`.
  - Coverage: single hunk, same-file multi-hunk descending offsets, multiple files, empty hunks, missing file, missing old string, non-unique old string, read-only/save failure, and direct disk reads after the MCP HTTP tool returns.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ApplyPatchTest' --tests 'com.jonnyzzz.mcpSteroid.server.ApplyPatchToolIntegrationTest' --rerun-tasks --warning-mode all` passed.
  - Review: three-agent pass approved the persistence fix direction and requested explicit save-failure/read-only coverage; the hardening was implemented.

- [x] Configure DPAIA Microshop cases to use JDK 25 during IDE setup and compile warmup. Superseded for Gradle 8.14.3 Microshop cases by the later JDK 24 compatibility fix below.
  - Files: `DpaiaCuratedCases.kt`, DPAIA runner/comparison setup call sites, `intelliJ-container.kt`, `mcp-steroid.kt`, `DpaiaConfigTest.kt`, `IdeTestHelpersTest.kt`.
  - Fixes: project SDK replacement now updates mismatched SDKs, compile warmup receives the case's configured JDK, and JAVA_HOME lookup fails fast unless a JDK path with `bin/javac` is emitted.
  - Validation: `:test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IdeTestHelpersTest' --rerun-tasks --warning-mode all` passed.

- [x] Add a real IntelliJ Ultimate monorepo `thisLogger` lookup regression test.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/IntelliJThisLoggerLookupTest.kt`.
  - Research: Marinade's MCP Steroid guide waits for `projectInitialized`, `indexingInProgress == false`, and no modal/background indexing, but IntelliJ's own API docs warn that `waitForSmartMode()` has no guarantee another dumb mode will not start before the next statement.
  - Fix direction from `~/Work/intellij`: use `Observation.awaitConfiguration(project)` for initial import/configuration readiness, then run the whole indexed lookup inside `smartReadAction(project)`.
  - Validation: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij ./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJThisLoggerLookupTest' --rerun-tasks --warning-mode all` passed in 8m41s with 4,191 references across 1,526 files.

- [x] Route MCP indexing guidance away from `waitForSmartMode()` as an indexed-read handoff.
  - Files: `ExecutionSuggestionService.kt`, `McpScriptContext.kt`, `prompt/skill.md`, `coding-with-intellij-{intro,patterns,psi,threading}.md`, `SkillReferenceHintTest.kt`, `IndexingGuidanceContractTest.kt`.
  - Fix: `IndexNotReadyException` and smart-mode hints now recommend `Observation.awaitConfiguration(project)` after import/sync/configuration and `smartReadAction { }` around the whole indexed PSI query.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.SkillReferenceHintTest' --rerun-tasks --warning-mode all` passed; scoped `:prompts:test` for `IndexingGuidanceContractTest`, `MarkdownArticleContractTest`, and changed Kt blocks passed after forced prompt regeneration.

- [x] Harden IDE exception capture for JUL records with null parameters.
  - Evidence: the green IntelliJ monorepo `thisLogger` lookup logged Kotlin FIR severe errors, then `ExceptionCaptureService` crashed while reading a nullable `LogRecord.parameters` array.
  - Files: `ExceptionCaptureService.kt`, `ExceptionCaptureServiceTest.kt`.
  - Fix: capture failures now log to stderr instead of breaking the original IDE error path, `ProcessCanceledException` is still rethrown, and null parameters are handled explicitly.
  - Validation: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ExceptionCaptureServiceTest' --rerun-tasks --warning-mode all` passed.
  - Review: initial Codex pass caught hidden `ProcessCanceledException` handling in plugin-id lookup; follow-up Claude/Codex/Gemini pass approved.

- [x] Make IntelliJ monorepo test setup prefer explicitly configured local checkouts over stale cached ZIPs.
  - Evidence: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij` still reused an older cached TeamCity ZIP before updating in-container.
  - Files: `intelliJ-git.kt`, `IntelliJGitCloneZipTest.kt`.
  - Fix: configured ZIPs and configured checkout directories now win before cache reuse; local checkout ZIP creation uses a proper `file:///` clone URI and preserves the source checkout's real `origin` remote for in-container fetches.
  - Validation: `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJGitCloneZipTest' --rerun-tasks --warning-mode all` passed.
  - Review: initial Codex pass caught the container-unusable `file:///Users/...` origin in generated ZIPs; follow-up Claude/Codex/Gemini pass approved.

## Next Candidates

- [x] Reduce redundant Maven verification in the DPAIA arena prompt.
  - Evidence: the latest successful run compiled with `steroid_execute_code`, then ran targeted Maven tests, then ran the full Maven suite. That kept correctness but increased Bash calls from 2 to 3 and runtime from 111s to 123s.
  - Implemented wording: do not rerun a completed Maven/Gradle target solely to recover `BUILD SUCCESS` hidden by `tail`/`grep`; explicitly allow reruns after code changes, real failures, incomplete runs, or Gradle skipped-test behavior.
  - Review: first 3-agent pass requested narrowing the wording; follow-up pass under `/tmp/mcp-steroid-review/runs-current-4/` approved with Claude/Codex/Gemini consensus.
  - Measurement: `DpaiaPetclinicRest37Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-003310-dpaia__spring__petclinic__rest-37-mcp` passed 184/184 tests in 101s with 0 native Edit, 1 `steroid_apply_patch`, 2 Bash, and 0 tool errors.
  - Delta versus the prior 123s run: Bash 3 -> 2, total tool calls 12 -> 11, runtime 123s -> 101s, pass rate unchanged.

- [x] Measure the dedicated apply-patch routing resource change.
  - Scenario: repeat `DpaiaPetclinicRest37Test.claude with mcp` first because it is the stable measured case.
  - Target: keep 184/184 tests, 0 native Edit, `steroid_apply_patch` used, 0 tool errors, and no regression versus the 101s run.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-073953-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 116s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 10 total calls, 3 MCP calls, 7 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 1 Grep, 0 native Edit, 2 Bash, 0 errors.
  - Delta versus the 101s run: `steroid_apply_patch` stayed true, native Edit stayed 0, Bash stayed 2, total calls 11 -> 10, runtime 101s -> 116s. This is within the observed PetclinicRest37 variance and confirms the global prompt-resource routing is not regressing the stable Maven case.

- [x] Add a prompt-size or prompt-shape regression check for the DPAIA arena MCP block.
  - Files likely under `test-experiments/src/test/kotlin/.../arena/`.
  - Expected effect: prevent reintroducing large contradictory prompt blocks.
  - Consensus note: Codex specifically recommended asserting that the prompt does not reintroduce broad "run at most once" wording and still contains explicit rerun-required cases.
  - Measured prerequisite: the verification-guidance tweak succeeded on the 101s run, so this is now the next low-hanging fruit.
  - Implemented in `ArenaPromptContractTest`; validation: `./gradlew :test-experiments:test --tests '*ArenaPromptContractTest*' --warning-mode all` passed.

- [x] Pick and measure one Gradle DPAIA scenario before changing Gradle guidance.
  - Goal: establish a concrete baseline for Gradle cold-starts, skipped-test behavior, and IntelliJ Gradle runner errors before editing prompt resources.
  - Scenario: `DpaiaMicroshop2Test.claude with mcp`.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-090258-dpaia__spring__boot__microshop-2-mcp`.
  - Result: fix claimed, MCP used, full Gradle suite passed, agent time 171s.
  - Raw metrics: 12 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 8 patch hunks, 0 native Edit, 0 Read, 3 Write for new files, 4 Bash, 0 tool errors, 1.05M tokens.
  - Delta versus the stale-disk failure run: 248s -> 171s, tool errors 7 -> 0, native Edit 14 -> 0, Read 11 -> 0. The "apply_patch did not persist" fallback disappeared.

- [x] Add a Gradle-focused MCP prompt resource modeled after the Maven patterns.
  - Files likely under `prompts/src/main/prompts/skill/`.
  - Consensus status: the follow-up `thisLogger` FIR review selected this by 2/3 reviewers (Codex + Gemini); Maven fallback JDK guidance remains Claude's candidate.
  - Expected effect: fewer Bash Gradle cold starts and fewer hand-rolled IntelliJ Gradle snippets.
  - Implemented files: `prompts/src/main/prompts/skill/execute-code-gradle.md`, links from `execute-code-overview.md`, `execute-code-tool-description.md`, and `coding-with-intellij.md`, plus `GradlePromptContractTest`.
  - Validation: IntelliJ Gradle runner passed `:prompts:generatePrompts :prompts:test --tests 'com.jonnyzzz.mcpSteroid.prompts.GradlePromptContractTest' --tests '*ExecuteCodeGradleKtBlocksCompilationTest*' --tests 'com.jonnyzzz.mcpSteroid.prompts.MarkdownArticleContractTest' --warning-mode all` after fixing the initial non-kotlin fence contract failure.
  - Review: `/tmp/mcp-steroid-review/gradle-prompt-resource-20260427/runs/`; Claude, Codex, and Gemini approved.
  - Measurement: `DpaiaMicroshop2Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-135940-dpaia__spring__boot__microshop-2-mcp` passed, but the agent fetched 0 resources and still used Bash Gradle 5 times after an aborted IDE build. Agent time was 170.8s versus the 136s JDK-fixed baseline.
  - Lesson: the resource content is valid, but discoverability/routing is still weak for arena agents.

- [x] Improve Gradle resource discovery/routing from DPAIA/MCP prompts.
  - Evidence: the first post-resource Microshop-2 measurement did not fetch `mcp-steroid://skill/execute-code-gradle`.
  - Expected effect: fewer Bash Gradle calls after an IDE build abort and fewer hand-rolled Gradle snippets.
  - Review: `/tmp/mcp-steroid-review/gradle-resource-measurement-20260427/runs/`; all reviewers approved the interpretation. Claude recommended high-impact arena prompt routing, Codex recommended execute-result abort guidance, and Gemini recommended both prompt/resource-table abort guidance and edited a rough version.
  - Implemented direction: route Gradle arena prompts through `steroid_fetch_resource` for `mcp-steroid://skill/execute-code-gradle`, keep Maven prompts on `mcp-steroid://skill/execute-code-maven`, and add prompt-resource abort guidance with full resource URIs.
  - Validation: `ArenaPromptContractTest` passed via IntelliJ Gradle runner; prompt resource generation plus `ExecuteCodeToolDescriptionKtBlocksCompilationTest`, `ExecuteCodeOverviewKtBlocksCompilationTest`, and `MarkdownArticleContractTest` passed via IntelliJ Gradle runner.
  - Note: an initial combined multi-module Gradle run was stopped after a thread dump showed the `--tests` filter leaked and launched `DpaiaArenaTest.codex without mcp`; the scoped module reruns above are the valid validation signal.

- [x] Measure Gradle resource routing on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1` for `mcp-steroid://skill/execute-code-gradle`, fewer Bash Gradle calls than the 5-call measurement, and no new tool errors.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-142637-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 142.0s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 10 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Write, 2 Bash, 0 tool errors, 764,238 tokens, and 0 resource fetches.
  - Delta versus the 170.8s post-resource run: total calls 28 -> 10, Bash 5 -> 2, tool errors 2 -> 0, tokens 1,458,578 -> 764,238, runtime 170.8s -> 142.0s. Delta versus the 136s JDK-fixed baseline: Bash stayed 2, tool errors stayed 0, but tokens stayed higher and runtime was 6s slower.
  - Lesson: arena/prompt routing reduced waste but still failed the primary resource-use criterion. The decoded log shows the agent considered fetching the Gradle skill after `Build errors: false, aborted: true`, but used Bash instead; prompt-only routing is not enough at this decision point.

- [x] Add result-boundary guidance for aborted IDE builds from `steroid_execute_code`.
  - Evidence: both Microshop-2 routing runs hit `Build errors: false, aborted: true` after an IDE build; the agent then fell back to Bash without calling `steroid_fetch_resource`, even when the arena prompt told it to fetch the Gradle resource.
  - Scope: `ExecuteCodeToolHandler` now appends a concise next-action hint to `steroid_execute_code` results when build output indicates `errors=false, aborted=true`, using generated Maven/Gradle prompt article classes rather than hardcoded `mcp-steroid://...` string literals.
  - Expected effect: make the resource URI visible exactly at the failure boundary, before the agent chooses Bash.
  - Review: `/tmp/mcp-steroid-review/build-abort-guidance-20260427/runs/`; Claude, Codex, and Gemini approved. Optional fallback-case coverage requested by Claude/Codex was added.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all` passed.

- [x] Measure aborted-build result-boundary guidance on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1`, no more than 2 Bash Gradle calls, and no new tool errors.
  - First attempt `run-20260427-144355-dpaia__spring__boot__microshop-2-mcp` failed before the agent ran because the Docker IDE container disappeared during repository setup; this is not a benchmark result.
  - Valid run dir: `test-experiments/build/test-logs/test/run-20260427-150914-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 169.6s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 15 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Read, 3 Write, 1 Glob, 3 Bash, 0 tool errors, 985,678 tokens, and 0 resource fetches.
  - Lesson: the result-boundary hint was visible in the tool result, but Claude still ignored `steroid_fetch_resource` and used Bash. A URI-only/fetch-only hint is insufficient.

- [x] Review the failed fetch-resource boundary measurement and pick the next low-hanging correction.
  - Review artifacts: `/tmp/mcp-steroid-review/build-abort-boundary-measurement-20260427/runs/`.
  - Consensus: Claude, Codex, and Gemini all selected the cheapest next change first: make the aborted-build boundary guidance forceful, name Claude's actual `mcp__mcp-steroid__steroid_fetch_resource` tool, and keep resource URIs generated from prompt article classes.
  - Follow-up rule from reviewers: if this still produces 0 fetches, stop iterating on fetch-only wording and reconsider inline Gradle sync guidance or removing the failed hint.

- [x] Make aborted-build boundary guidance explicit and line-separated.
  - Scope: `ExecuteCodeBuildAbortGuidance` now emits `REQUIRED ACTION` / `NEXT TOOL CALL` wording with `mcp__mcp-steroid__steroid_fetch_resource`, and appends the guidance with a leading newline so decoded logs do not collapse `aborted: true` into the hint.
  - Tests: `ExecuteCodeBuildAbortGuidanceTest` now asserts the exact Claude-visible tool name, compiler-error no-op behavior, and separate-line formatting.
  - Validation: IntelliJ Gradle runner passed `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all`.

- [x] Measure explicit aborted-build boundary guidance on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1`, no more than 2 Bash Gradle calls, and no new tool errors.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-151926-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 174.0s.
  - Decoded evidence: line 743 showed `Build errors: false, aborted: true`; line 744 showed the separate-line `REQUIRED ACTION ... NEXT TOOL CALL must be mcp__mcp-steroid__steroid_fetch_resource ...`; line 747 immediately used Bash Gradle anyway.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 19 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 5 Read, 2 Glob, 4 Write, 2 Bash, 1 native Read error, 1,255,211 tokens, and 0 resource fetches.
  - Lesson: even an exact Claude tool-name boundary instruction is ignored in this scenario. The fetch-only boundary hypothesis has failed; the next low-hanging correction should be reviewed as either inline minimal Gradle sync guidance in the tool result or removal/replacement of the hint.

- [x] Review the explicit-hint failure and choose the next low-hanging correction.
  - Candidate: append a short inline Gradle sync/configuration recipe at the aborted-build boundary instead of requiring `steroid_fetch_resource`, or revert the fetch-only hint if the inline recipe would cost too many tokens.

- [x] Fix the real Gradle abort root causes found while reviewing the explicit-hint failure.
  - Review artifacts: `/tmp/mcp-steroid-review/gradle-jdk24-finaltasks-20260427/runs/`.
  - Consensus: Claude, Codex, and Gemini approved the patch with no blockers. All agreed `ProjectDataImportListener.onFinalTasksFinished` is the right Gradle import boundary and JDK 24 is the right Microshop fix for Gradle 8.14.3.
  - Files: `test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/infra/mcp-steroid.kt`, `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/GradleCompileTest.kt`, `test-integration/src/test/docker/ide-base/Dockerfile`, and Microshop DPAIA config/contract/metrics tests.
  - Fixes: Gradle import setup now sets the linked Gradle JVM from the configured project SDK, waits for `onFinalTasksFinished` before smart mode, and rethrows import failures. Docker IDE base installs Temurin 24. Microshop Gradle cases now use JDK 24 because Gradle 8.14.3 rejects Java 25 as a daemon JVM.
  - Validation: `:test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.GradleCompileTest' --rerun-tasks --warning-mode all` passed with `GRADLE_JVM=25`, `BUILD_ERRORS=false`, `BUILD_ABORTED=false`; DPAIA config/contract/metrics tests passed; `DpaiaMicroshop2Test.claude with mcp` passed with `Recommended JAVA_HOME: /usr/lib/jvm/temurin-24-jdk-arm64` and `Build errors: false, aborted: false`.
  - Measurement: post-fix Microshop-2 used 1,773,570 tokens, 36 calls, 3 MCP calls, 33 native calls, 0 errors, and 5 Bash calls. Correctness improved, but token/tool cost regressed.
  - Next low-hanging consensus: move Gradle agents away from native exploration now that the IDE path works. The concrete follow-up is to inline/update IDE-native Gradle build/sync guidance, including the `onFinalTasksFinished` wait in `execute-code-gradle.md`, then remeasure Microshop-2.

- [x] Next low-hanging Gradle improvement: make JDK choice harder to miss in DPAIA prompts.
  - Evidence: Microshop-2 eventually chose JDK 25 and passed, but first tried `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-arm64` and hit `invalid source release: 24`.
  - Fix: the arena prompt now exposes the case-configured JDK version, has the first MCP call print `Recommended JAVA_HOME`, and tells agents to use the exact printed value instead of wildcard assignments or lower JDKs.
  - Consensus: follow-up Claude and Codex review selected this over Kotlin FIR investigation as the next low-risk item; Gemini selected Kotlin FIR, so this is a 2/3 consensus.
  - Review: initial Codex pass caught the invalid copyable `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-*` assignment; follow-up Claude/Codex/Gemini pass approved the wildcard-free prompt.
  - Validation: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --rerun-tasks --warning-mode all` passed.
  - Measurement: `DpaiaMicroshop2Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-115129-dpaia__spring__boot__microshop-2-mcp` passed in 136s agent time. First MCP call printed `Recommended JAVA_HOME: /usr/lib/jvm/temurin-25-jdk-arm64`; both Bash Gradle calls used `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64`; no `invalid source release: 24`.
  - Delta versus the 171s baseline: Bash 4 -> 2, agent time 171s -> 136s, native Edit stayed 0, tool errors stayed 0, full Gradle suite passed.

- [x] Add a decoded-log regression check for Microshop-2 JDK usage.
  - Evidence: the measured run's decoded log shows both Bash Gradle calls used `/usr/lib/jvm/temurin-25-jdk-arm64`; reviewers suggested pinning this so `temurin-21` or literal `*` does not return.
  - Fix: `AgentOutputMetrics` now extracts decoded Bash commands and flags Gradle commands that omit `JAVA_HOME`, use a literal wildcard, or do not start with the expected JDK prefix, including absolute `gradlew` wrapper paths.
  - Regression coverage: `ExtractDecodedLogMetricsTest.microshop gradle bash commands use configured jdk without wildcard` and `ExtractDecodedLogMetricsTest.detects gradle bash commands with lower jdk or wildcard java home`.
  - Validation: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ExtractDecodedLogMetricsTest' --rerun-tasks --warning-mode all` passed.

- [x] Investigate severe Kotlin FIR resolve logs from the IntelliJ monorepo `thisLogger` lookup.
  - Evidence: the green lookup run logged `KaFirReferenceResolver` / `Expected FirResolvedContractDescription but FirLazyContractDescriptionImpl` errors, followed by an `ExceptionCaptureService` null-pointer while capturing the IDE error.
  - Fix: the monorepo test now asserts no lookup-time FIR severe logs, and the MCP script avoids the Kotlin FIR resolve path by using `CacheManager.getVirtualFilesWithWord(..., UsageSearchContext.IN_CODE, ...)` plus Kotlin PSI `KtCallExpression` filtering for real `thisLogger()` calls.
  - Consensus: final Gradle/JDK review selected this as the next low-hanging task by 2/3 reviewers; Maven fallback JDK guidance remains the other candidate.
  - Validation: the old `ReferencesSearch` TDD run failed on the new FIR log assertion; the fixed run passed with `THISLOGGER_LOOKUP_STRATEGY=INDEXED_WORD_PLUS_KOTLIN_PSI`, `THISLOGGER_REFERENCE_COUNT=2670`, and `THISLOGGER_FILE_COUNT=1522`.
  - Review: first pass had Gemini/Claude approve and Codex request a strategy-marker assertion plus stale `CLAUDE.md` note cleanup; follow-up Claude/Codex/Gemini pass approved.

- [x] Make IntelliJ monorepo test setup prefer an explicitly configured local checkout when appropriate.
  - Evidence: the `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij` run still reused an existing cached TeamCity ZIP before updating the checkout in-container.
  - Fix: `ensureIntelliJGitCloneZipInCache()` honors configured ZIPs/checkouts before cache reuse, preserves the source checkout's real `origin` remote in generated ZIPs, and has explicit regression coverage.

- [x] Update Gradle arena/resource guidance to use the now-working IDE-native Gradle path.
  - Review: `/tmp/mcp-steroid-review/gradle-ide-guidance-20260427/runs/`; Claude, Codex, and Gemini approved. The shared next step was to remeasure Microshop-2.
  - Files: `prompts/src/main/prompts/skill/execute-code-gradle.md`, `GradlePromptContractTest.kt`, `ArenaTestRunner.kt`, `ArenaPromptContractTest.kt`.
  - Fix: `execute-code-gradle.md` now uses `ProjectDataImportListener.onFinalTasksFinished` for Gradle sync readiness instead of `Observation.awaitConfiguration(project)`, and the Gradle arena prompt inlines an IDE build recipe before Bash fallback.
  - Validation: IntelliJ Gradle runner passed the scoped prompt generation/tests and `ArenaPromptContractTest`.

- [x] Measure updated Gradle IDE guidance on Microshop-2.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-185422-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, IDE build printed `Build errors: false, aborted: false`, and the full Gradle suite passed. Agent time was 145s.
  - Raw metrics: 1,370,218 tokens, 26 total calls, 4 MCP calls, 22 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 12 Read, 4 Glob, 3 Write, 2 Bash, 0 errors, 0 resource fetches.
  - Delta versus the post-JDK24/final-tasks baseline: tokens 1,773,570 -> 1,370,218, calls 36 -> 26, native calls 33 -> 22, Bash 5 -> 2, Read 17 -> 12, Glob 7 -> 4, errors stayed 0.
  - Measurement review: `/tmp/mcp-steroid-review/gradle-ide-guidance-measurement-20260427/runs/`; Claude, Codex, and Gemini approved with no blockers.

- [ ] Next low-hanging Gradle improvement: batch source discovery and related file reads through IDE/VFS APIs.
  - Evidence: after the IDE Gradle build fix, the remaining waste is concentrated in 12 native `Read` and 4 native `Glob` calls.
  - Consensus: the measurement review unanimously recommended a narrow prompt/resource recipe that reads 3+ related project files in one `steroid_execute_code` call using IDE/VFS APIs before falling back to native `Glob`/`Read`.
  - Target: reduce native source exploration calls while keeping Microshop-2 green, `steroid_apply_patch` used, Bash <=2, and 0 tool errors.

## Recent PR Follow-up

- [ ] PR #26 needs fixes before merge.
  - `npx/build.gradle.kts`: include `package-lock.json` and likely `tsconfig.json` as `npmBuild` inputs.
  - `ocr-tesseract/build.gradle.kts`: make tessdata download skipping version-aware.

## RLM Code Review (v0.93.0..HEAD, 2026-04-28)

Scope: 195 files / ~14K LOC / 125 commits between tag `v0.93.0` and commit
`47e03ef2` (the disable-apply-patch commit on `main`). Review followed RLM
partition+map+reduce: 4 parallel sub-agents each owned a slice (production,
tests, integration/experiments, prompts+kotlin-cli); orchestrator verified
each non-trivial claim with `grep`/file reads before logging.

Apply-patch–specific findings live in `TODO-APPLY-PATCH.md` so the main
DPAIA / autoresearch flow stays focused.

### Fixed (2026-04-28)

- [x] **`test-experiments/.../arena/DpaiaArenaTest.kt`** — removed the
  `Assumptions.assumeTrue` filter and the matching `arena.test.agents`
  system-property kdoc. Subset selection now runs through Gradle's
  `--tests` pattern matching, which already covered the same surface
  (`--tests '*DpaiaArenaTest.claude with mcp'` etc.). No more runtime
  test-skip pattern. (was MAJOR.)
- [x] **`test-integration/.../infra/intelliJ-container.kt`** —
  `waitForIdeWindow` now emits a `console.writeInfo` heartbeat every
  ~10 s (`elapsed=Ns`, last status). Long polls are no longer
  indistinguishable from hangs — the CLAUDE.md "1-minute investigate"
  rule now sees diagnostic output between polls. (was MAJOR.)
- [x] **`ij-plugin/.../execution/DialogWindowsLookup.kt`** — added a
  three-line comment in `canPumpEdtNonModal` explaining that the outer
  `withContext(CoroutineName(...))` deliberately stays on the caller's
  dispatcher; the inner `async(Dispatchers.EDT)` does the dispatch. No
  behavioral change. (was MINOR.)
- [x] **`ij-plugin/.../test/.../ScriptExecutorTest.kt`** — refreshed the
  stale "10-second timeout versus 60-second exec timeout" comment near
  line 73 to describe the current 60 s `timeoutRunBlocking` rationale.
  (was MINOR.)
- [x] **`ij-plugin/.../test/.../ExecuteFeedbackToolHandlerTest.kt`** —
  `assertEquals(true, err!!.contains(...))` → `assertTrue(...)`; dropped
  the now-unused `assertEquals` import. (was MINOR.)

Verification: `:ij-plugin:compileKotlin :ij-plugin:compileTestKotlin
:test-experiments:compileTestKotlin :test-integration:compileKotlin` all
green; `:ij-plugin:test --tests '*ScriptExecutor*' '*ExecuteFeedbackToolHandler*'
'*DialogKiller*' '*VfsRefreshService*'` ran 25/25 (8+7+6+4) on the touched
suites, fresh timestamps.

### Re-evaluated, no longer flagged

- `test-integration/.../OpenProjectTrustIntegrationTest.kt:101` —
  re-reading the code: line 88 already passes `mcpListWindows(timeoutSeconds = 120)`,
  so the per-iteration MCP call IS bounded; the agent's claim of
  unbounded silent hang was wrong. The outer poll uses `Thread.sleep`
  with a fixed 180 s budget, which is acceptable for a JUnit poll
  (not a coroutine).
- `test-integration/.../IntelliJContainerTest.kt:28` — `Thread.sleep(3000)`
  is a deliberate post-creation hold to let the IDE settle; the
  `@Timeout(value = 15, unit = MINUTES)` already protects against hang.
  Pre-existing and harmless.
- `ij-plugin/.../test/.../VfsRefreshServiceTest.kt:68-73` — the test
  documents its own limitation (cannot null out `basePath` on a light
  fixture without reflection). The null-base-path branch is exercised
  by integration tests that close projects. Coverage is acceptable.

### IntelliJ inspections — automated run blocked

- [ ] **N/A automated.** Three approaches tried via `steroid_execute_code`:
  (1) `InspectionEngine.runInspectionOnFile` — failed with API
      signature mismatch (it now takes a single `InspectionToolWrapper` and
      a `GlobalInspectionContext`, not a list + `ProgressIndicator`).
  (2) `DaemonCodeAnalyzerImpl.runMainPasses` directly — fails the
      `assertUnderDaemonProgress()` check at
      `community/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/DaemonCodeAnalyzerImpl.java:485`
      because the script runs without a `DaemonProgressIndicator`.
  (3) `MainPassesRunner.runMainPasses(files, severity)` — the canonical
      wrapper IntelliJ itself uses (e.g. `CodeSmellDetectorImpl.java:130`).
      Compiles and is the right API per the IntelliJ source agent
      reviewed at `~/Work/intellij`, but times out from
      `steroid_execute_code` even with 3 small files at 240 s. Likely
      cause: it pumps EDT internally and the suspend script holds
      resources that prevent forward progress.
  Manual workaround for full IntelliJ-inspection coverage of the
  changed Kotlin files: open each in the IDE, then
  **Code → Inspect Code… → Custom Scope: changed files since v0.93.0**,
  and triage results into this section. The agent-review findings above
  already covered the banned-pattern + stability surface that IntelliJ
  inspections would flag.

### Verification notes

- All 9 `ApplyPatchTest` engine-level tests pass on `main` after disable
  (verified: `:ij-plugin:test --tests '*ApplyPatchTest*'` 9/9).
- All 30 `McpServerIntegrationTest` cases pass on `main`; `tools/list`
  results no longer include `steroid_apply_patch`. No other test referenced
  the removed tool.
- The `apply-patch` branch retains the full feature surface for re-enable.
- No force-push was used; `origin/main` and `jb/main` are fast-forwards
  from `349d649c` to `47e03ef2`.

# Parked — Cluster A: MCP transport, cancellation, indexing (#46, #52) (2026-05-15)

Six tasks parked for a future iteration. Re-iterate later — direction is set,
but the boundary design and the `kind`-error shapes need a second pass.
**Ordering below is easiest-first**, so the re-iteration starts at the top.

## Design constraints carried over from the 2026-05-15 review

- **The webserver must keep functioning regardless of what scripts/handlers
  do.** There is exactly one outer boundary in the HTTP layer that catches
  everything (including `CancellationException`, `Throwable`, panics from
  handler code) and converts it into a well-formed MCP tool result. The
  inner code is free to throw; the boundary is the contract that protects
  the server. No exception escapes the boundary into ktor.
- **No new MCP tools, no new HTTP endpoints** for readiness. Surface
  indexing state through a failing tool response on the existing calls. See
  `memory/feedback_narrow_tool_surface.md`.
- **Cancellation does not kill `kotlinc`.** A3 waits for the worker to
  finish; we release the request slot and the boundary returns a structured
  `cancelled` response to the client, but the compiler runs to completion.
  Justification: forcibly killing the JDK21 worker is flaky on macOS and the
  saved cycles are small relative to the rest of cleanup.

## A0 — Boundary catch-all in `McpHttpTransport.handlePost` (foundation, easiest)

Single `try { … } catch (Throwable) { … }` wrapping the entire handler body in
`mcp-http/.../McpHttpTransport.kt:99–182`. Inside the catch:
- Match `CancellationException` first — build a structured `cancelled` tool
  result, do **not** log, do **not** rethrow.
- Match every other `Throwable` — build a structured `internal_error` tool
  result with the executionId, log once at `Logger.error`.
- The boundary itself must never throw; ktor sees only a normal 200 response
  with `isError: true` in the body. Server-side state stays clean.

Acceptance: a script that throws OOM, a script that calls `error("boom")`,
and a client that disconnects mid-request all produce a single 200 response
with the appropriate `kind`, and the next request on the same server
succeeds.

## A2c — Single terminal HTTP log per request

Remove the per-handler logging at
`mcp-http/.../McpHttpTransport.kt:159` (the 500 line) and `:176` (the 200
line). Keep only the global `requestLoggingPlugin` line at
`ij-plugin/.../server/SteroidsMcpServer.kt:334–348`. The middleware sees
whatever status the boundary set, exactly once.

## A2b — Logger discipline for `CancellationException`

In every `catch (t: Throwable)` under `mcp-http/` and
`ij-plugin/.../execution/` (`McpHttpTransport.kt`, `ExecutionManager.kt:94`,
`ScriptExecutor.kt:158`), match `CancellationException` first and rethrow
without logging. Cite the `c.i.openapi.diagnostic.Logger` Javadoc in a
short code comment. Add a banned-pattern test:
`Logger.error(CancellationException)` must fail the lint, alongside the
existing empty-catch rule in `CLAUDE.md → Banned patterns`.

## A1 — `indexing_in_progress` structured error after ⌊2T/3⌋

Centralize the wait in
`ij-plugin/.../execution/McpScriptContextImpl.kt:153–174` as
`waitForSmartMode(maxWait: Duration)` and call it from every handler that
currently waits silently (`steroid_execute_code`, `steroid_apply_patch`,
`steroid_action_discovery`, `steroid_list_windows`). Bound = ⌊2T/3⌋
seconds where T is the per-call timeout. On timeout, return:
```
{
  "isError": true,
  "kind": "indexing_in_progress",
  "message": "Project '<name>' is still indexing. Wait and repeat the same command again.",
  "project": "<name>", "elapsedMs": …, "dumbMode": true
}
```
- Add registry key `mcp.steroid.smart.mode.wait.fraction` (default 0.66).
- Add one line to the `steroid_execute_code` and `steroid_apply_patch` tool
  descriptions: *"Returns `kind: indexing_in_progress` while the project is
  still being indexed — wait a few seconds and retry."*

## A2a — `cancelled` structured tool result

Build on A0. When the boundary catches a `CancellationException`, classify
the reason:
- `client_closed` if the request channel reports closed.
- `script_timeout` if our own `withTimeout` fired.
- `server_shutdown` otherwise.

Return body:
```
{ "isError": true, "kind": "cancelled", "reason": "...",
  "executionId": "...", "elapsedMs": ..., "message": "The call was interrupted. You may retry." }
```
HTTP status 200, no 408/499. Update the tool descriptions to mention the
`cancelled` kind so agents know to retry.

## A3 — Cooperative cancellation (without killing `kotlinc`)

Wait for `kotlinc` to finish naturally; do **not** call `destroyForcibly`.
Make these call sites cancellation-aware:
- `waitForSmartMode` — `suspendCancellableCoroutine { cont -> runWhenSmart { cont.resume(Unit) }; cont.invokeOnCancellation { /* no-op */ } }`.
  Cancellation returns instantly; smart-mode callback resolves later
  harmlessly.
- Long IDE operations that accept a `ProgressIndicator`
  (`ProjectTaskManager.buildAllModules` etc.) — hand in an indicator
  linked to the coroutine `Job` via
  `com.intellij.openapi.progress.coroutineToIndicator` so `Job.cancel()`
  propagates through `indicator.cancel()`.
- The `kotlinc` worker step — leave running; the boundary already returned
  to the client. Worker completion releases its own resources.

Acceptance: a forcibly-cancelled `steroid_execute_code` returns its
`cancelled` body within ~500 ms regardless of what the inner script is
doing. The `kotlinc` worker may continue and complete silently.

## Integration tests (when A re-opens)

One new test class in `:test-integration` (Docker IDE):
1. **Indexing-in-progress** — heavy project, immediate
   `steroid_execute_code` with T=15s and fraction=0.66 → response at ~10 s
   with `kind=indexing_in_progress`.
2. **Client cancellation** — 600 s script, close connection at 1 s →
   response within 2 s with `kind=cancelled, reason=client_closed`. No
   `SEVERE` logged.
3. **Script timeout** — script sleeps past timeout → `kind=cancelled,
   reason=script_timeout`. Single HTTP log line.
4. **Boundary stability** — fire a sequence of misbehaving scripts (OOM,
   `error(...)`, infinite loop+cancel, valid call) and assert the *last*
   valid call succeeds — the server survives every failure mode.

Plus one unit test: scan caught `Throwable` in `mcp-http/` and
`ij-plugin/.../execution/` and assert each has a `CancellationException`
rethrow before any logging.

## Order of execution (when A re-opens)

1. A0 → A2c → A2b (boundary + log cleanup; mechanical).
2. A1 (indexing error; independent, deliverable on its own).
3. A2a (cancelled error shape; depends on A0).
4. A3 (cooperative cancellation; last, optimisation).

# Active — Cluster B: prompt corpus hardening (#47, #48, #51) (2026-05-15)

Five prompt-only changes to steer `steroid_execute_code` away from invented
helpers, threading misuse, and low-level daemon-highlighting APIs. Format
recap: each `.md` in `prompts/src/main/prompts/` is `[line 1: title]`,
`[line 3: description]`, then body. No hardcoded `mcp-steroid://` URI
literals — use `XxxPromptArticle().uri` in production Kotlin. Fence
annotations like `` ```kotlin[RD] `` per IDE.

**Ordered easiest-first.** Each task is independent; can ship as
separate commits or bundled per the user's preference (validated in a
prior session: bundled is fine for related prompt-corpus refactors).

## B3 — Expand daemon-highlighting warning (smallest)

File: `prompts/src/main/prompts/skill/coding-with-intellij-context-api.md`.
The existing NOTE about `isEditorHighlightingCompleted()` already warns
against stale results. Expand it into a boxed warning that names the
specific symbols that cause the failures observed in #51:

> **Do not call `DaemonCodeAnalyzerImpl`, `HighlightingSession`, or
> `DaemonProgressIndicator` directly.** These APIs require running under a
> `DaemonProgressIndicator` and a stored `HighlightingSession` — neither of
> which exists in a `steroid_execute_code` script context. Symptoms:
> `must be run under DaemonProgressIndicator, but got: null` and
> `No HighlightingSession stored in …`.
>
> For inspection diagnostics, use the supported recipes:
> see `[Inspect and fix](mcp-steroid://ide/inspect-and-fix)` and
> `[Inspection summary](mcp-steroid://ide/inspection-summary)`.

Use article-Kotlin link syntax (the generator resolves `mcp-steroid://...`
in markdown to article references at build time — confirmed in existing
see-also blocks).

## B1 — "Real helpers vs invented names" subsection

File: `prompts/src/main/prompts/skill/coding-with-intellij-context-api.md`,
inserted near the top of the body (after the description, before the
existing helper inventory).

```
## Real helpers vs invented names

These names exist on `McpScriptContext` / standard imports:
`readAction`, `writeAction`, `smartReadAction`, `writeIntentReadAction`,
`findProjectFile`, `runInspectionsDirectly`, `projectScope()`,
`allScope()`, `waitForSmartMode()`, `project`.

These names **do not exist** — do not write them:

| Invented | Use instead |
|---|---|
| `buildProject()`, `compileProject()` | `ProjectTaskManager.getInstance(project).buildAllModules().await()` |
| `createProjectFile(...)` | `findProjectFile("...")` for existing files, or `writeAction { VfsUtil.saveText(virtualFile, text) }` after creating with `LocalFileSystem` |
| `context.project` | Just `project` — it is in scope already |
| `projectDir`, `findProjectDir()` | `project.basePath` or `project.guessProjectDir()` |
```

Each invented name appears verbatim so future agents grep-find this
table on first failure.

## B2 — Threading decision table + failure→fix patches

File: `prompts/src/main/prompts/skill/coding-with-intellij-threading.md`.
Insert a compact table at the very top of the body (existing prose
moves below). Then add three named failure→fix patches.

```
## Quick decision

| You're doing… | Wrap in |
|---|---|
| VFS write (`saveText`, create, delete) | `writeAction { … }` |
| PSI read, `FilenameIndex`, search | `readAction { … }` (or `smartReadAction` if may be dumb) |
| Refactoring processor, intention action | `writeIntentReadAction { … }` |
| Background write (newer platform APIs) | `backgroundWriteAction { … }` |
| EDT-only API (UI, action invocation) | `withContext(Dispatchers.EDT) { … }` |

## Failure → fix

- `Access is allowed from write thread only` → wrap the offending call in
  `writeAction { … }`.
- `Access is allowed from Event Dispatch Thread (EDT) only` → wrap in
  `withContext(Dispatchers.EDT) { … }`.
- `Background write action is not permitted on this thread` → use
  `backgroundWriteAction { … }`, or switch to EDT if the API requires it.
```

## B4 — Tool description distillation (`steroid_execute_code`)

File: the tool handler for `steroid_execute_code` — locate via
`Grep -r "steroid_execute_code"` under `ij-plugin/src/main/kotlin/.../tools/`.
The tool description is always in agent context (unlike articles, which
are loaded on demand), so 4 compact lines pay off.

Add to the tool description:

> Available helpers in scope: `project`, `readAction`, `writeAction`,
> `smartReadAction`, `writeIntentReadAction`, `findProjectFile`,
> `waitForSmartMode`. Use `project` (not `context.project`).
> Wrap VFS writes in `writeAction`; PSI reads in `readAction`.
> Do not call `DaemonCodeAnalyzerImpl` or `HighlightingSession` directly —
> use the inspection resources.

Constraint: don't blow the description over the soft limit (check
existing `*ToolDescription.md` files for length convention). If the
addition pushes over, route via `XxxPromptArticle().uri` reference
instead.

## B5 — DPAIA regression prompts in `:test-experiments`

New test class under `test-experiments/src/test/kotlin/...` that fires
each invented-helper / threading-misuse / daemon-API snippet from #47,
#48, #51 verbatim and asserts the agent self-corrects within one retry.

- Gemini-key-aware: use `DockerGeminiSession` with
  `skipTestWhenKeyMissing = true` per CLAUDE.md.
- Not parallel (CLAUDE.md: "never run `:test-experiments` tests in
  parallel" — each starts a Docker container).
- One test per pattern; minimal fixtures.

Patterns to cover (one test each, names suggested):
- `BuildProjectInventedHelper` — script calling `buildProject()`
- `CreateProjectFileInventedHelper` — `createProjectFile(...)`
- `ContextDotProject` — `val p = context.project`
- `ProjectDirInvented` — `val dir = projectDir`
- `WriteFromWrongThread` — VFS save without `writeAction`
- `EdtRequiredAccess` — UI/action call without `Dispatchers.EDT`
- `DirectDaemonIndicator` — `DaemonCodeAnalyzerImpl.runMainPasses(...)`

This is the largest task in B; defer until B1–B4 land if scope is tight.

# Active — Cluster C: structured apply_patch recovery hints (#49, #50) (2026-05-15)

Three changes (plus one deferred). C depends on Cluster A's `kind`-error
shape only loosely — C can ship before or after A, but reusing A's body
shape is cleaner. **C runs last in the accepted B → A → C order.**

## C3 — One-line nudge in `steroid_apply_patch` tool description (smallest)

Locate the `steroid_apply_patch` tool description (likely
`ApplyPatchToolDescription.md` or similar under
`ij-plugin/src/main/kotlin/.../tools/`). Add:

> On error, do not retry blindly. Inspect current file text via
> `steroid_execute_code` with `FilenameIndex` first. See
> `[Anchor-safe editing](mcp-steroid://skill/anchor-safe-editing)`.

## C2 — New article: `skill/anchor-safe-editing.md`

New file: `prompts/src/main/prompts/skill/anchor-safe-editing.md`.
Article format (title line, blank, description line, blank, body).
Fence annotations `[IU,RD]` on each Kotlin block since the recipe is
IDE-product-agnostic.

Body covers:
1. Locate file with
   `FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))`
   — handle empty result and multiple-match cases.
2. Print 5 lines of context around the intended anchor (use
   `VfsUtil.loadText` and substring around the match).
3. Apply the patch only when occurrence count == 1; if 0 or >1, abort
   and surface the count.
4. After apply, re-read and verify replacement count.

Add to the relevant `seeAlso` chain (likely
`prompts/src/main/prompts/skill/overview.md` or similar TOC).

## C1 — Structured `apply_patch` errors with candidates

File: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ApplyPatch.kt`.
At lines 128 (`Hunk #$index: file not found`) and 143 (`old_string not
found`), replace bare strings with structured bodies. Keep the
human-readable first line so existing tests pass; append structured
fields.

**File-not-found body:**
```
{
  "kind": "file_not_found",
  "hunk": N,
  "file": "...",
  "candidates": [up to 5 paths from FilenameIndex.getVirtualFilesByName(basename)],
  "parentExists": bool,
  "parentListing": [up to 10 entries if parentExists]
}
```

**`old_string not found` body:**
```
{
  "kind": "anchor_not_found",
  "hunk": N,
  "file": "...",
  "fileExists": true,
  "fileLines": N,
  "fileBytes": N,
  "fuzzyCandidates": [up to 3 lines that share the longest stable token from old_string, with line numbers]
}
```

Token-bag scoring: tokenize `old_string` on `[A-Za-z0-9_]+`, pick the
longest token that appears uniquely (or rarely) in the file, return
its line. If no useful token, omit `fuzzyCandidates`.

Tests: extend the existing `ApplyPatchTest` suite with three cases:
- file_not_found returns at least one basename candidate
- anchor_not_found returns fileLines + fileBytes when file exists
- existing error messages remain unchanged on first line (backward compat)

## C4 — `dryRun` parameter (deferred)

Add `dryRun: Boolean = false` to the `steroid_apply_patch` tool args.
When true, run preflight + return structured candidates (C1's shape)
without applying. Lower priority — ship C1+C2+C3 first.

## Order of execution (B → A → C)

1. **B3** → **B1** → **B2** → **B4** (prompt edits, mechanical). Single
   commit OK.
2. **B5** (regression tests; can ship in a follow-up).
3. **A** (when re-opened — see Parked section above).
4. **C3** → **C2** → **C1** (recovery hints).
5. **C4** (dryRun, deferred).

