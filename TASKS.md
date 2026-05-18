
# Active focus — npx-kt testing and stabilization plan (2026-05-18)

Goal: turn npx-kt/devrig `mpc` mode from "implemented" into a stable,
diagnosable replacement for the direct IDE HTTP MCP server.

Why this exists: the first full `AI_NPX` batch proved the fast/fake-IDE path
is useful, but the long real-IDE/agent path is still fragile. We hit two
separate infrastructure failures before a clean agent result:

- fixed: container file writes used `cat > $containerPath` and broke on Java
  prefs paths containing shell metacharacters;
- open blocker: IDE archive download repeatedly failed moving
  `idea-2026.1.2-aarch64.tar.gz.tmp` to the final archive path with
  `NoSuchFileException`, leaving the download directory empty.

Related GitHub issue:
- [#54 Improve IDE-first Gradle verification workflow for agents](https://github.com/jonnyzzz/mcp-steroid/issues/54)
  tracks why this batch fell back to command-line Gradle and what needs to
  improve so agents can use IntelliJ/Gradle runner first.

Plan-review quorum:
- [x] Gemini review via `run-agent.sh gemini`
  `run_20260518-071443-9948`: `NO-GO` for stability because downloader,
  lifecycle, cancellation, and apply-patch/screenshot coverage are missing.
- [x] Codex review via `run-agent.sh codex`
  `run_20260518-071443-9949`: `NO-GO` until downloader and deterministic
  bridge/fake-IDE gaps are closed.
- [x] Claude review via `run-agent.sh claude`
  `run_20260518-071443-9947`: `REVIEW_NO_GO_WITH_CHANGES`; specifically
  requires real tool-calling AI_NPX smoke, downloader diagnosis, SSE error
  tests, and two-IDE duplicate-name routing tests.
- [ ] Re-review with 3x quorum after the Phase 0/2/3 blockers below are
  implemented.

## Stabilization order

Do not start with the AI agent. The order is:

1. Phase 0: harness and infrastructure health.
2. Phase 1: fast compile/static gates.
3. Phase 2: pure npx-kt unit tests.
4. Phase 3: fake-IDE stdio integration tests.
5. Phase 4: real IDE bridge tests without AI.
6. Phase 5: one AI_NPX agent smoke at a time.
7. Phase 6: release-readiness gates and final quorum review.

## Phase 0 — harness and infrastructure health

- [x] Diagnose the IDE downloader `.tmp -> final` failure with a concrete
  stack/log entry and failing path. Current observed stack:
  `IdeDownloader.moveDownloadedFile` from
  `test-integration/build/ide-download/idea-2026.1.2-aarch64.tar.gz.tmp` to
  `idea-2026.1.2-aarch64.tar.gz`.
  Root cause: concurrent in-process callers shared the same deterministic
  `<archive>.tmp`; the first caller moved it to the final path while another
  caller still expected the temp path to exist.
- [x] Add a focused downloader regression for the root cause if it is
  downloader concurrency, stream lifecycle, or temp-file reuse.
  Added `IdeDownloaderTest.resolveAndDownload serializes concurrent downloads
  for the same archive`.
- [x] Decide whether the integration harness should use a per-test archive
  download directory or a downloader-level file lock to prevent concurrent
  archive downloads from sharing the same `.tmp` path.
  Decision: use a minimal per-destination in-process lock in
  `resolveAndDownload`. Do not add per-test archive directories or sidecar
  file locks unless a future failure proves cross-JVM contention.
  Verification:
  `./gradlew :intellij-downloader:test --tests 'com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloaderTest'`
  passed. MCP Steroid inspections on touched files passed with
  `INSPECTION_TOTAL: 0` in
  `eid_20260518T094303-npx-kt-stabilization-downloader-lock`.
  Review quorum passed:
  Claude `run_20260518-074341-21893`, Codex
  `run_20260518-074352-22060`, Gemini `run_20260518-074358-22279`.
- [x] Make long-test run directories easy to find from failure output:
  run dir, screenshot dir, video dir, agent raw/decoded logs, and IDE log.
  Added `IntelliJContainer.diagnosticsSummary()` and threaded it into
  readiness/modal/snapshot failures plus `NpxKtAgentRoutingIntegrationTest`
  assertion failures. Verification:
  `./gradlew :test-integration:compileKotlin :test-integration:compileTestKotlin`
  passed. MCP Steroid inspections: `NpxKtAgentRoutingIntegrationTest.kt`
  clean; `intelliJ-container.kt` still has pre-existing whole-file shell
  string/Grazie warnings and no broad suppressions were added
  (`eid_20260518T095948-npx-kt-stabilization-run-dir-diagnostics`).
  Review quorum passed:
  Claude `run_20260518-080354-33015`, Codex
  `run_20260518-080402-33156`, Gemini `run_20260518-080407-33338`.
- [ ] Keep credential checks out of fast phases. AI-only phases may require
  `~/.anthropic`, `~/.openai`, and `~/.vertex`; export both
  `GEMINI_API_KEY` and `GOOGLE_API_KEY` from `~/.vertex` for Gemini.
- [ ] Keep the Gemini missing-key skip exception limited to Gemini on CI.
  Anthropic/OpenAI keys must continue to fail hard when missing.

## Phase 1 — fast compile/static gates

Preferred execution path is IntelliJ/Gradle runner via MCP Steroid once issue
#54 is addressed. Until then, if shell Gradle is used, record that in the
final report.

- [ ] MCP Steroid inspections on all touched Kotlin files.
- [ ] `:mcp-core:compileKotlin`
- [ ] `:mcp-stdio:compileKotlin`
- [ ] `:mcp-stdio:test`
- [ ] `:mcp-steroid-server:compileKotlin`
- [ ] `:npx-kt:compileKotlin`
- [ ] `:npx-kt:compileTestKotlin`
- [ ] `:npx-kt:compileIntegrationTestKotlin`
- [ ] `:test-helper:compileKotlin`
- [ ] `:test-integration:compileKotlin`
- [ ] `:test-integration:compileTestKotlin`
- [ ] Never run root `./gradlew test` for this work.

## Phase 2 — npx-kt unit coverage

Routing and naming:
- [x] Same real project path + IDE pid produces a stable hash suffix.
- [x] Different IDE pid produces a different hash suffix.
- [x] Different canonical project path produces a different hash suffix.
- [x] Duplicate original IDE project names in two IDEs produce distinct
  exposed names.
- [x] Exposed project names map back to original IDE project names without
  suffix parsing.
- [x] Stale exposed project name returns an actionable "call
  steroid_list_projects to refresh" error.
  Completed routing-name coverage with existing tests for stable hash,
  different PID hash, reverse mapping, and stale-name errors, plus new tests
  for different canonical project homes and duplicate original project names
  across IDEs. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T143416-npx-routing-naming`.
  Plan review quorum passed:
  Claude `run_20260518-123118-18695`, Codex
  `run_20260518-123118-18696`, Gemini `run_20260518-123118-18697`.
  Final review quorum passed:
  Claude `run_20260518-123518-20509`, Codex
  `run_20260518-123518-20510`, Gemini `run_20260518-123518-20511`.
- [x] `singleIdeOrNull()` covers zero, one, and multiple IDE states.
  Added zero-state coverage and tied it to the existing one-IDE and
  multiple-IDE tests. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T131643-npx-kt-single-ide-policy`.
  Plan review quorum passed:
  Claude `run_20260518-111353-97845`, Codex
  `run_20260518-111353-97846`, Gemini `run_20260518-111353-97847`.
  Final review quorum passed:
  Claude `run_20260518-111734-202`, Codex
  `run_20260518-111734-203`, Gemini `run_20260518-111734-204`.

Window, screenshot, and input routing:
- [x] Window `projectName` is rewritten with the same project suffix.
- [x] Window routing disambiguates same-name projects by project home/pid.
- [x] `rewriteWindow` handles null `projectName` and null `projectPath`.
- [x] Screenshot `execution_id` is remembered and follow-up `steroid_input`
  routes to the same IDE.
- [x] `steroid_input` rejects screenshot ids from another IDE with an
  actionable error.
  Completed window/input routing coverage with existing tests for basic
  window suffixing and screenshot execution-id memory, plus new tests for
  same-name project window disambiguation, null window metadata, same-IDE
  `steroid_input` forwarding, and cross-IDE screenshot rejection.
  Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test files returned
  `{}` in `eid_20260518T144639-npx-window-input-routing`.
  Plan review quorum passed:
  Claude `run_20260518-123809-22691`, Codex
  `run_20260518-123809-22690`, Gemini `run_20260518-123809-22715`.
  Final review quorum passed:
  Claude `run_20260518-124746-28200`, Codex
  `run_20260518-124746-28199`, Gemini `run_20260518-124746-28201`.

Bridge client and handler behavior:
- [x] Bearer token is sent when marker token is present.
- [x] Authorization header is absent when marker token is empty.
  Positive bearer forwarding was already covered by
  `bridge client sends bearer token and rewritten original project name`; added
  focused empty-token coverage asserting the Authorization header is omitted.
  Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T103131-npx-kt-bridge-auth-coverage`.
  Review quorum passed:
  Claude `run_20260518-083302-49403`, Gemini
  `run_20260518-083302-49404`, Claude
  `run_20260518-083523-50945`. Codex
  `run_20260518-083302-49405` was not counted because it blocked before
  reading the patch on a missing marinade `/tmp` path.
- [x] `project_name` is rewritten to the original IDE project name.
- [x] `steroid_execute_code` forwards `timeout` and `dialog_killer`.
- [x] `steroid_execute_code` forwards progress SSE events.
  Existing handler tests already asserted original project-name rewriting for
  execute-code and apply-patch routes and progress SSE forwarding. Extended the
  execute-code handler test to assert `dialog_killer` forwarding alongside the
  existing timeout assertion. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T123439-npx-kt-bridge-forwarding`.
  Review quorum passed:
  Claude `run_20260518-103628-71075`, Codex
  `run_20260518-103628-71076`, Gemini `run_20260518-103628-71077`.
- [x] `steroid_apply_patch` forwards task id, dry-run, hunks, and original
  project name.
  Extended the existing apply-patch handler test to assert `dry_run` plus the
  forwarded hunk `file_path`, `old_string`, and `new_string` fields alongside
  the existing task id and original project-name assertions. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T124014-npx-kt-bridge-apply-patch`.
  Review quorum passed:
  Claude `run_20260518-104137-73671`, Codex
  `run_20260518-104137-73672`, Gemini `run_20260518-104137-73673`.
- [x] `steroid_execute_feedback` forwards rating, explanation, and code.
  Added focused execute-feedback handler coverage asserting the original
  project name, task id, success rating, explanation, and code are forwarded to
  the IDE bridge. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T124518-npx-kt-bridge-feedback`.
  Review quorum passed:
  Claude `run_20260518-104859-84310`, Codex
  `run_20260518-104859-84311`, Gemini `run_20260518-104859-84312`.
- [x] `steroid_action_discovery` forwards action groups, caret offset, and
  max actions.
  Added focused action-discovery handler coverage asserting the original
  project name, file path, caret offset, action groups, max actions per group,
  and task id are forwarded to the IDE bridge. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T125551-npx-kt-action-discovery`.
  Plan review quorum passed:
  Claude `run_20260518-105230-86075`, Codex
  `run_20260518-105230-86076`, Gemini `run_20260518-105230-86077`.
  Final review quorum passed:
  Claude `run_20260518-105621-87871`, Codex
  `run_20260518-105621-87870`, Gemini `run_20260518-105621-87872`.
- [x] `steroid_take_screenshot` remembers execution ids.
  Added focused screenshot handler coverage asserting a returned `eid_...`
  from the IDE bridge is remembered for later input routing, while the
  original project name, task id, and reason are forwarded to
  `steroid_take_screenshot`. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T130238-npx-kt-screenshot-memory`.
  Plan review quorum passed:
  Claude `run_20260518-110003-89798`, Codex
  `run_20260518-110003-89799`, Gemini `run_20260518-110003-89800`.
  Final review quorum passed:
  Claude `run_20260518-110307-91830`, Codex
  `run_20260518-110307-91829`, Gemini `run_20260518-110307-91831`.
- [x] `steroid_open_project` covers zero/one/multiple IDE routing policy.
  Simplified the handler to use the bridge routing service directly and added
  focused coverage for zero IDEs, multiple IDEs, and the singleton IDE forward
  path. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed on rerun; the first attempt failed before assertions on a transient
  embedded-server `BindException`. MCP Steroid inspections on touched Kotlin
  files returned `{}` in `eid_20260518T130940-npx-kt-open-project-policy`.
  Plan review quorum passed:
  Claude `run_20260518-110618-94077`, Codex
  `run_20260518-110618-94078`, Gemini `run_20260518-110618-94079`.
  Final review quorum passed:
  Claude `run_20260518-111023-95891`, Codex
  `run_20260518-111023-95890`, Gemini `run_20260518-111023-95892`.
- [x] SSE `error` event returns a `ToolCallResult` error.
- [x] HTTP 4xx/5xx returns a `ToolCallResult` error with enough upstream
  context.
  Added focused `NpxToolBridgeClientTest` coverage for upstream 401 and 500
  responses, asserting the returned tool error includes HTTP status and
  upstream body context. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T102517-npx-kt-bridge-http-hardening`.
  Review quorum passed:
  Claude `run_20260518-082728-46323`, Codex
  `run_20260518-082728-46324`, Gemini `run_20260518-082728-46325`.
- [x] Channel closes with no `result` returns a no-result error.
- [x] Malformed SSE `data:` returns an actionable tool error instead of
  throwing out of the MCP call.
- [x] `event: result` without `result` field returns an actionable tool
  error.
- [x] Multi-line `data:` SSE frames are concatenated and decoded correctly.
  Added focused `NpxToolBridgeClientTest` coverage for malformed SSE JSON,
  SSE error events, missing result fields, no-result stream closure, and
  multi-line SSE data frames. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on touched Kotlin files passed with
  `INSPECTION_TOTAL: 0` in
  `eid_20260518T101622-npx-kt-bridge-sse-hardening`.
  Review quorum passed:
  Claude `run_20260518-081642-38276`, Codex
  `run_20260518-081647-38362`, Gemini `run_20260518-081653-38569`.
- [x] Progress tokens are isolated across concurrent routed calls.
- [x] Cancellation/timeout behavior is covered at the bridge boundary.
  Added `McpToolRegistryTest` coverage for two concurrent tool calls with
  distinct `_meta.progressToken` values and separate progress notifications.
  Added `NpxToolBridgeClientTest` coverage that coroutine cancellation while
  waiting for an SSE result propagates as `CancellationException` instead of a
  tool error. Timeout behavior is covered at the bridge boundary by the
  existing execute-code handler test that forwards the tool-level `timeout` to
  the IDE; the npx bridge intentionally uses an infinite HTTP timeout and does
  not enforce a second client-side tool timeout. Verification:
  `./gradlew :mcp-core:test --tests 'com.jonnyzzz.mcpSteroid.mcp.McpToolRegistryTest' --rerun-tasks --console=plain`
  passed on sequential rerun. The first attempt overlapped with another
  Gradle invocation and failed in `:prompts:jar` on a generated class
  `NoSuchFileException`; no test assertions had run.
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on touched Kotlin test files passed with
  `INSPECTION_TOTAL: 0` in
  `eid_20260518T150204-npx-progress-cancellation`.
  Plan review quorum passed:
  Claude `run_20260518-125210-29927`, Codex
  `run_20260518-125210-29928`, Gemini `run_20260518-125210-29929`.

Prompt/resource behavior:
- [x] Prompt context maps IDE build to product code and baseline.
- [x] Malformed/unknown IDE build falls back to `PromptsContext.Generic`.
  Existing prompt-context tests cover routed IDE build parsing, singleton
  route selection, and known product-code baseline parsing. Added fallback
  coverage for malformed builds and unknown product prefixes, and hardened
  `NpxPromptsContextHandler` to return `PromptsContext.Generic` for product
  codes outside the supported set. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on touched Kotlin files passed with
  `INSPECTION_TOTAL: 0` in `eid_20260518T152848-npx-prompt-context`.
  Plan review quorum passed:
  Codex `run_20260518-131055-37520`, Gemini
  `run_20260518-131055-37521`, replacement Codex
  `run_20260518-131234-38415`. Claude plan review hit an external rate limit
  in `run_20260518-131055-37519`.
  Final review quorum passed on the corrected diff:
  Claude `run_20260518-132950-46268`, Codex
  `run_20260518-132950-46267`, Gemini `run_20260518-132950-46269`.
- [x] Prompt and resource rendering stays local to npx-kt and is not routed
  to the IDE.
  Extended `CliMcpStdioFakeIdeIntegrationTest` so `resources/list`,
  `resources/read`, `prompts/list`, `prompts/get`, and local
  `steroid_fetch_resource` run before any routed IDE tool call, with the fake
  `/npx/v1/tools/call/stream` counter still at zero. The same test then calls
  `steroid_execute_code` and asserts the counter increments, proving the
  counter observes real routed tool calls. Verification:
  `./gradlew :npx-kt:integrationTest --tests 'com.jonnyzzz.mcpSteroid.proxy.cli.CliMcpStdioFakeIdeIntegrationTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T153835-npx-local-prompts`.
  Plan review quorum passed:
  Claude `run_20260518-133436-48895`, Codex
  `run_20260518-133436-48894`, Gemini `run_20260518-133436-48896`.
  Final review quorum passed:
  Claude `run_20260518-133910-51964`, Codex
  `run_20260518-133910-51965`, Gemini `run_20260518-133910-51970`.
- [ ] devrig stdio tool/resource/prompt descriptors match the direct IDE MCP
  server for the supported surface.

CLI/runtime behavior:
- [ ] `devrig mpc` starts a clean stdio MCP server and exits cleanly on stdin
  close.
- [ ] No stdout leaks before MCP frames in `mpc` mode.
- [ ] Non-MCP commands restore stdout before printing user output.
- [ ] `DEVRIG_HOME` override accepts only existing absolute canonical paths;
  no `--home` flag.
- [ ] Startup failure before MCP handshake is visible on stderr and test
  logs.

## Phase 3 — fake-IDE stdio integration

- [ ] `CliMcpStdioIntegrationTest`: initialize, ping, tools/list,
  prompts/list, resources/list.
- [ ] `CliMcpStdioStdoutCleanlinessTest`: no stdout pollution before MCP
  frames.
- [ ] `CliMcpStdioFakeIdeIntegrationTest`: one fake IDE marker, list projects
  returns exposed project name.
- [ ] Fake IDE execute-code route receives original project name and returns
  known marker.
- [ ] Fake IDE windows route rewrites project names and window ids.
- [ ] Fake IDE prompt/resource read works locally in devrig.
- [ ] Fake IDE progress event becomes MCP progress notification.
- [ ] Add two-IDE fake coverage where both IDEs expose the same original
  project name.
- [ ] Add stale-marker fake coverage: marker disappears, old project name
  fails with refresh instruction, new marker is rediscovered.
- [ ] Add unreachable-port and 401/500 bridge failure cases.
- [ ] Add dropped project stream / reconnect behavior coverage.

## Phase 4 — real IDE bridge, no AI

Run these before any AI agent smoke. They separate devrig/IDE bridge bugs from
agent prompt/tool-selection bugs.

- [ ] Selected ij-plugin npx endpoint tests:
  `/npx/v1/products`, metadata auth, project stream, windows, and
  `/npx/v1/tools/call/stream` result/progress/error.
- [ ] One real running IDE, no AI: devrig stdio initializes.
- [ ] One real running IDE, no AI: `steroid_list_projects` discovers the IDE
  marker and returns exposed project name.
- [ ] One real running IDE, no AI: `steroid_execute_code` prints a unique
  marker and returns it through devrig.
- [ ] One real running IDE, no AI: progress notification is observable for an
  execute-code call.
- [ ] One real running IDE, no AI: `steroid_apply_patch` works on a disposable
  fixture file.
- [ ] One real running IDE, no AI: screenshot/input route to the same IDE.
- [ ] Optional but preferred before stable: two real IDEs with duplicate
  project names route independently.

## Phase 5 — AI_NPX long integration tests

Run one Docker IDE / one agent test at a time. Never parallelize
`:test-integration` or `:test-experiments`.

Definition of "AI_NPX smoke passed":
- the agent sees the devrig-provided `mcp-steroid` server as connected;
- the agent calls `steroid_list_projects`;
- the agent uses the exact exposed `project_name`;
- the agent calls `steroid_execute_code`;
- the result contains a unique test-generated marker;
- no `DEVRIG_NPX_FAILED` marker appears;
- run dir and agent raw/decoded logs are recorded in this file.

Tasks:
- [ ] Rewrite stale AI_NPX prompts that only enumerate tools and say "do not
  call tools"; they must call `steroid_list_projects` and
  `steroid_execute_code`.
- [ ] Claude AI_NPX smoke against one real IDE.
- [ ] Gemini AI_NPX smoke against one real IDE using `~/.vertex` for both
  Gemini env var names.
- [ ] Codex AI_NPX smoke against one real IDE using `~/.openai` when supported
  by the harness.
- [ ] Prompt/resource skill smoke through devrig stdio.
- [ ] On any >60s stall, collect screenshot, run dir, container process list,
  and IDE thread dump before killing.

## Phase 6 — release-readiness gates

- [ ] MCP Steroid inspections are clean on every touched Kotlin file.
- [ ] No new IDE inspection warnings.
- [ ] Fast, unit, fake-IDE, and real-IDE no-AI phases pass in order.
- [ ] Full AI_NPX smoke passes. If blocked by infrastructure, this file must
  link the tracked issue, the exact failure, the run directory, and the
  fallback no-AI bridge verification for the same commit.
- [ ] Add structured bridge diagnostics if AI_NPX failures remain opaque:
  tool name, exposed project name, original project name, IDE pid, elapsed
  time, SSE event counts, and upstream error body.
- [ ] 3x `run-agent.sh` quorum review after the above gates.
- [ ] Commit in small logical batches by phase.

# Active focus — npx-kt as stable MCP Steroid stdio replacement (2026-05-17)

Goal: make npx-kt/devrig `mpc` mode a real replacement for the IDE HTTP MCP
server by routing tool calls through discovered IDE bridge endpoints while
keeping prompt/resource rendering local to npx-kt.

Plan-review status:
- [x] Draft plan reviewed by `run-agent.sh claude`
  (`run_20260517-191744-64301`, `REVIEW_OK_WITH_CHANGES`).
- [x] Implementation reviewed by `run-agent.sh` reviewers:
  Claude `run_20260517-194637-73425` (`REVIEW_OK`), Codex
  `run_20260517-194637-73427` (`REVIEW_DONE_WITH_FINDINGS`), replacement
  Claude `run_20260517-200233-80021` (`REVIEW_OK`). Gemini
  `run_20260517-194637-73426` could not run because `GEMINI_API_KEY` is not set.
- [x] Final devrig MCP stdio diff review from Claude
  `run_20260517-201918-86348` returned `REVIEW_OK`.
- [x] MCP Steroid inspections are clean for all touched Kotlin files
  (`INSPECTION_PROBLEMS: 0`, `eid_20260517T223723-npx-kt-devrig-mpc-routing`).

Implementation tasks:
- [x] Add a project routing service under `npx-kt` that consumes discovered IDE
  metadata, project snapshots, and windows.
- [x] Generate exposed `project_name` values as
  `<ideProjectName>-<hash8>`, where `hash8` is base64-url-no-pad of the first
  6 bytes of `SHA-256(realProjectHome.toRealPath UTF-8 + 0x00 + idePid UTF-8)`.
  Project names are session-scoped; agents must refresh after an IDE restart.
- [x] Apply the same suffix logic to window `projectName` values and preserve
  `windowId` routing so input/screenshot calls reach the owning IDE.
- [x] Store a reverse mapping from exposed `project_name` to the IDE pid,
  bridge URL, and original IDE project name. Tool calls must rewrite
  `project_name` back to the original name before crossing the bridge. Never
  parse the suffix at routing time; use exact map lookup.
- [x] Record screenshot `execution_id -> idePid` so follow-up
  `steroid_input` calls route to the same IDE even when multiple IDE windows
  are present.
- [x] Treat stale exposed names as typed, actionable errors:
  "project_name <...> is no longer present; call steroid_list_projects to
  refresh".
- [x] Implement network-backed npx-kt handlers for every `McpSteroidTools`
  handler interface that needs IDE routing:
  `ListProjectsToolHandler`, `ListWindowsToolHandler`,
  `ExecuteCodeToolHandler`, `ApplyPatchToolHandler`,
  `ExecuteFeedbackToolHandler`, `ActionDiscoveryToolHandler`,
  `VisionScreenshotToolHandler`, `VisionInputToolHandler`, and
  `OpenProjectToolHandler`.
- [x] Use `/npx/v1/tools/call/stream` for routed calls that can produce
  progress and forward progress events as MCP progress notifications.
- [x] Define and test `OpenProjectToolHandler` routing policy: require exactly
  one discovered/routable IDE; otherwise return an actionable error.
- [x] Implement local npx-kt `PromptsContextHandler`. Given exposed
  `project_name`, resolve the IDE metadata and render prompts/resources with
  that IDE's product code and baseline version. Do not route prompt/resource
  rendering to an IDE.
- [x] Register generated resources/prompts in the npx-kt stdio server using
  the same `ResourceRegistrar` path as the IJ plugin.
- [x] Move `ResourceRegistrar` from `ij-plugin` into `mcp-steroid-server`
  because it uses no IntelliJ Platform APIs.
- [x] Keep no-IDE and stale-project errors explicit and actionable.
- [x] Add unit tests for hash suffix stability, reverse project mapping, and
  project/window rewriting.
- [x] Add unit tests for prompt context selection.
- [x] Add unit tests for bridge routing request bodies.
- [x] Add bridge-routing unit tests with a fake HTTP client/engine verifying
  the request body rewrites `project_name` to the original IDE project name and
  sets the bearer token header.
- [x] Add npx-kt stdio integration tests with one fake IDE bridge discovered
  through marker/discovery, covering `steroid_list_projects` and a routed tool
  call.
- [x] Extend npx-kt fake-IDE stdio integration coverage to
  `steroid_list_windows` and prompt/resource reads.
- [x] Add/extend agent integration tests for `AiMode.AI_NPX` with one running
  IDE so an AI agent uses devrig stdio MCP end-to-end, not the HTTP MCP server.
- [x] Validate with scoped Gradle tests, MCP Steroid inspections, and a debug
  IDE/runtime check where practical. The Docker+Claude agent scenario was
  compiled but not run in this batch because it requires external agent
  credentials and a long Docker IDE run; the fake-IDE stdio route is executed.
- [ ] Commit in small logical batches:
  1. planning/TASKS update;
  2. ResourceRegistrar move;
  3. routing/name-mapping service + unit tests;
  4. local list/prompts handlers;
  5. network bridge handlers + stdio integration tests;
  6. agent-level integration tests;
  7. cleanup/inspection fixes. Earlier items 1-5 are already committed.

# Active notes — npx-kt CLI home override (2026-05-16)

- Runtime help intentionally documents only user-facing commands/options. The
  npx home override remains available for tests and automation via
  `DEVRIG_HOME=<path>`; it prints a stderr notice when used. Do not re-add the
  old `--home` flag.

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

## What likely happened (failure-mode summary for the next reviewer)

MCP was HTTP-ready, but the project was not smart-mode-ready.
`steroid_execute_code` correctly waited for indexing, but the request was
cancelled at ~60 s before indexing finished. MCP Steroid then logged the
coroutine cancellation as an unexpected `warning` / `SEVERE` error, and the
server confusingly logged both `200 OK` and `500 Internal Server Error` for
the same POST window. That single timeline is the canonical evidence for
all six A tasks below — the boundary catch-all (A0), the single-log
discipline (A2c), the cancellation-as-tool-error shape (A2a), the indexing
error shape (A1), and the cooperative-cancellation work (A3) each address
one slice of that timeline.

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

## A0 — Boundary catch-all in `McpHttpTransport.handlePost` (LANDED — commit pending)

Single `try { … } catch (CancellationException) { rethrow } catch (Throwable)
{ JSON-RPC error envelope } ` around the response phase of `handlePost`.
Implementation deviates from the original plan in one detail: the boundary
emits a JSON-RPC `error` envelope (HTTP 200, `error.code = INTERNAL_ERROR`,
`id` echoed from the request body), not a `cancelled`-kind tool result. The
`kind`-style structured tool result belongs to A2a, which builds on this
foundation. The current A0 scope is "no exception escapes ktor, exactly one
response per call, no `Logger.error(CancellationException)` noise".

Key implementation points:

- `mcp-core/.../McpProtocol.kt` exposes `encodeJsonRpcError(id, code, message)`
  so the transport layer can build error envelopes without depending on
  `McpServerCore` internals. `McpServerCore.encodeError` now delegates to it
  (single source of truth).
- `mcp-http/.../McpHttpTransport.kt` sets the `Mcp-Session-Id` /
  `Mcp-Session-Notice` headers BEFORE the boundary so a newly created
  session is communicated even when the handler throws (otherwise the
  client reconnects with its stale id, leaking sessions).
- The fallback `respondText` inside the catch is itself wrapped in a
  nested try/catch — a secondary throw (e.g. response stream half-committed
  because the client disconnected mid-write) is logged at warn and
  swallowed, so it cannot escape into ktor and reintroduce #46's dual log.
- `CancellationException` is rethrown without logging. All three JVM
  variants (`kotlinx.coroutines.*`, `kotlin.coroutines.cancellation.*`,
  `java.util.concurrent.*`) are runtime type-aliases of the same class, so
  one catch covers all.
- `extractRequestId(body)` is `Throwable`-safe: malformed body / missing id
  / non-object root → `JsonNull`. Diagnostic must never itself throw.

Test surface (66/66 green) — keep passing on future edits:

- `:mcp-http:test --tests '*McpHttpTransportTest*'` — 36 cases, ~0.6 s.
  Four new boundary tests use `AssertionError` (an `Error`, not `Exception`)
  to bypass `McpToolRegistry.kt:90`'s `catch (Exception)` and actually
  reach the transport boundary; an `IllegalStateException` would have been
  intercepted by the registry and surfaced as `result.isError=true` without
  ever firing the boundary path.
- `:ij-plugin:test --tests '*McpServerIntegrationTest*'` — 30 cases, ~43 s.
  Unchanged behavior verified.

Codex + Claude review quorum on the diff: both `Ship with fixes`. Convergent
fixes folded in: nested-try around the fallback respondText (both),
`AssertionError` in the boundary tests (Codex), session-header before the
try (Claude), `encodeError` delegation (Claude), softened the "client has
already closed" block comment to acknowledge server-side timeout
cancellations (Claude).

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

**Landed as a scaffold** (commit pending). `DpaiaPromptCorpusRegressionTest`
under `test-experiments/.../tests/` covers three representative failure
clusters (one test per cluster). Each test:

1. Spins a Docker IDE container with `IntelliJProject.ThisLoggerProject`
2. Hands the Claude agent a neutrally phrased task (no forbidden helper
   names in the prompt — the corpus is supposed to carry the steer)
3. Parses the agent's raw NDJSON transcript via `readAgentExecCodeBodies`
   to inspect actual `steroid_execute_code` script bodies
4. Asserts on the script-body content, not the agent's final text

The script-body inspection is the key design choice: an earlier draft
checked the combined agent transcript, but the agent's
`steroid_fetch_resource` calls echo the article body verbatim — so any
substring match against the transcript passes on article echo even when
the agent never used the helper. Both reviewers (Claude + Codex) flagged
this and the rewrite addresses it.

Tests in v1:
- `agent compiles project via supported IntelliJ build API` (#47) —
  must call `ProjectTaskManager.getInstance(project).buildAllModules()`;
  must not call `buildProject(`, `compileProject(`, `createProjectFile(`.
- `agent wraps VFS write in the correct threading wrapper` (#48) —
  must pair a writeAction/backgroundWriteAction/edtWriteAction wrapper
  with a `VfsUtil.saveText` / `setBinaryContent` / Document-write call.
- `agent uses supported inspection helper not daemon highlighting
  internals` (#51) — must use `runInspectionsDirectly` or
  `InspectionEngine.*`; must not touch `DaemonCodeAnalyzerImpl`,
  `DaemonProgressIndicator`, or `HighlightingSession`.

**Notes for the first-run shakedown:**
- Uses `aiAgents.claude` (not Gemini). Per `test-integration/CLAUDE.md`,
  `ANTHROPIC_TOKEN_KEY_REF` is configured on TC but `GEMINI_API_KEY` is
  not, so Gemini tests would skip everywhere. Claude is the only agent
  that actually exercises the prompt corpus on TC today.
- Compile-checked but **not run end-to-end** in the authoring session
  (each test ~5–25 min Docker startup + Claude run + asserts, requires
  ANTHROPIC_API_KEY). The first run on TC will surface any marker-
  parsing or prompt-phrasing issues; treat as scaffold shakedown.
- Per-test isolated `IntelliJContainer` (matches
  `ThisLoggerComparisonTest`'s pattern). A shared-container variant
  (companion object + `@BeforeAll`/`@AfterAll`, per the IMPROVEMENTS.md
  harness pattern in `test-experiments/CLAUDE.md`) is a follow-up.

**Open follow-ups (defer until v1 has at least one green run):**
- File-content verification for the writeAction test — confirm
  `Logging.kt` actually contains the marker line after the run. Adds
  belt-and-suspenders behind the script-body check.
- Cover the remaining patterns from #47/#48/#51 individually:
  `context.project`, `projectDir`/`findProjectDir`, `readText(vf)`,
  EDT-only access, write-from-wrong-thread runtime error recovery.
  Each is a new `@Test` method following the same shape.
- Migrate to shared `IntelliJContainer` via `@BeforeAll` to amortize the
  ~2 min IDE startup across the suite.

# Landed — Cluster C: structured apply_patch recovery hints (#49, #50) (2026-05-15)

C1, C2, C3 shipped together. The user-facing error response now carries
nearby-file candidates (file-not-found path) and fuzzy line candidates
(anchor-not-found path). C4 remains parked.

Test surface that gates this work — keep passing on future edits:
- `:ij-plugin:test --tests '*ApplyPatchTest*' '*ApplyPatchToolIntegrationTest*'`
  — 36 cases, ~3 s; substring assertions on `"file not found"`,
  `"old_string not found"`, `"occurs more than once"`, `"expand old_string"`.
- `:prompts:test --tests '*AnchorSafeEditing*' '*ApplyPatchToolDescription*' '*MarkdownArticleContract*'`
  — the four kotlin blocks in the new article compile per IDE; the
  tool description still validates.

## C1 — Structured ApplyPatch errors (done — commit pending)

`ij-plugin/src/main/kotlin/.../execution/ApplyPatch.kt` now calls two
private helpers, `fileNotFoundMessage(...)` and `anchorNotFoundMessage(...)`,
that append (multi-line, plain text) structured candidates to the leading
sentence. The leading wording is preserved verbatim so existing
substring-based assertions in `ApplyPatchTest` and
`ApplyPatchToolIntegrationTest` keep passing.

- `file_not_found` adds up to 5 same-basename project-index hits.
- `anchor_not_found` adds `lines x, y bytes` and up to 3 fuzzy lines
  for the longest stable `[A-Za-z0-9_]{4,}` token from `oldString`.
- Both helpers swallow their own `RuntimeException` (re-throwing
  `ProcessCanceledException`) so the diagnostic can never itself fail
  the diagnostic.

The body format is plain multi-line text rather than the JSON shape
floated in the earlier plan — the existing tool result is a text
content payload, and the in-line text is what the agent actually reads.
If A2a lands and standardises a JSON `kind` envelope, the leading line
remains and the structured tail can move into a sibling JSON field
without breaking the substring contract.

## C2 — `skill/anchor-safe-editing.md` (done — commit pending)

New article with four kotlin code blocks (locate → excerpt → unique
check → apply+verify). Cross-linked from
`apply-patch-tool-description.md`. KtBlocksCompilation tests pass across
the IDE distributions that include the Kotlin plugin (default fence
annotation, so all IDEs compile each block).

## C3 — Apply-patch tool description nudge (done — commit pending)

`prompts/src/main/prompts/skill/apply-patch-tool-description.md` now
ends with a "do not retry blindly" paragraph that points at
`mcp-steroid://skill/anchor-safe-editing` and the four steps.

## C4 — `dryRun` parameter (still parked)

Add `dryRun: Boolean = false` to the `steroid_apply_patch` tool args.
When true, run preflight + return structured candidates without
applying. Lower priority. Re-iterate when the issue trail justifies the
extra surface.

## Order of execution (B → A → C)

1. **B3** → **B1** → **B2** → **B4** (prompt edits, mechanical). Single
   commit OK.
2. **B5** (regression tests; can ship in a follow-up).
3. **A** (when re-opened — see Parked section above).
4. **C3** → **C2** → **C1** (recovery hints).
5. **C4** (dryRun, deferred).

---

# Managed-backend review findings (2026-05-15)

Three parallel `run-agent.sh codex` review passes against `mcp-5` after
iter7. Items below are the consensus set (≥2 reviewers, with which
reviewers agreed in parentheses). One item — first-start writers into
the real user home — is intentionally **deferred** per user direction
("Let's keep it so for now, we are going to review that step later").

The reviewer reports are preserved at
`.run-agent-managed-backends/reviews-{a,b,c}/run_*/FINAL_RESULT.md`.

## Blockers

### B1 — `backend stop` can SIGTERM/SIGKILL an unrelated process (A, B, C)
`npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/proxy/ManagedBackend.kt:282-309`.
Stop trusts the PID file, calls `ProcessHandle.of(pid).destroy()` without
proving that pid still belongs to a managed IDE under
`homePaths.backendsDir`. PID reuse → wrong process killed.
**Fix:** before signalling, check `ProcessHandle.info().command()` is
under `homePaths.backendsDir`, or verify the `.<pid>.mcp-steroid`
marker matches our backend descriptor. On mismatch → delete stale PID
file, report "stale" outcome, no signal.

### B2 — Archive extraction allows path-traversal / symlink escape (A, B, C)
`intellij-downloader/src/main/kotlin/.../IdeUnpacker.kt:76-92`, `:133-140`, `:291-293`.
The `outputFile.canonicalPath.startsWith(unpackDir.canonicalPath)`
prefix check is bypassable (no trailing separator); tar symlinks aren't
target-validated.
**Fix:** add trailing `File.separator` to the prefix check; for
symlinks resolve `linkTarget = unpackDir.resolve(entry.linkName).normalize()`
and reject when it escapes the unpack dir.

✅ resolved

### B3 — Build / installDist blocked by `:intellij-downloader:extractSevenZipResources` (B only)
Likely fallout of the parallel 7zip-into-npx-kt worker mid-edit;
expected to resolve as that worker lands. Re-check after each iter
lands.

## Majors

### M1 — `idea-community` may resolve to an Ultimate (`IU-…`) bundle (A)
iter4's marker captured `ide.build: IU-253.28294.334` while the backend
ID was `idea-community-2025.3`. Either the resolver picked the wrong
URL, or the plugin reports the build of a different IDE process.
**Action:** read-only investigation first — confirm whether the
download URL the resolver returns for `code=IIC` actually serves a
Community binary, and whether the marker's `ide.build` reflects the
running JVM's ApplicationInfo. Only fix if a real mismatch is found.

### M2 — `backend start <product>` / `stop <product>` (no version) hits the network (B, C)
`ManagedBackend.kt` resolves "latest stable" via the products API instead
of consulting `homePaths.backendsDir/<product-key>-*`. Means stop
depends on JetBrains uptime AND can target a version different from
what's installed.
**Fix:** for product-only argv, prefer the highest-versioned entry on
disk. Fall back to API only when nothing is installed.

### M3 — Single-instance lock is racy across concurrent CLI calls (A, B, C)
`ManagedBackend.kt:231-273` — scan-then-spawn has no file lock; two
concurrent `backend start` calls can both pass the scan and both spawn.
**Fix:** `FileChannel.tryLock()` on `homePaths.stateDir/global.lock`
for the duration of the start sequence.

### M4 — JSON backend ids use natural stable identifiers (A, B, C) ✅ resolved
`BackendCommand.kt` — `backend --json` no longer exposes synthetic ordinal-based identifiers
as primary keys in `backends[]`.
**Fix:** use each row's natural id: `pid-<n>` for marker-discovered IDEs,
`port-<n>` for port-discovered IDEs, or the managed backend id.

### M5 — SevenZipLocator cache writes are racy (A, C) ✅ resolved (download-A batch)
`SevenZipLocator.kt:69-73`, `:103-107`. Fixed `*.tmp` filename per
binary; two concurrent first-runs collide.
**Fix:** randomise tmp name with `Files.createTempFile` and atomic-move
to the cache slot.

### M6 — `backend stop --json` reports a `logPath` that `start` never writes (A, B)
The schema lies. Either `start` writes to that path, or `stop` omits the field.
**Fix:** drop the field, or have `start` write to that path (it's the
log file we already capture — wire it).

### M7 — Partial / interrupted downloads poison the install dir (B) ✅ resolved (download-A batch)
No transactional rename; an aborted `download` leaves a half-extracted
bundle that subsequent `download` calls treat as installed.
**Fix:** extract to `<id>.partial/`, atomic rename to `<id>/` only on
full success.

### M8 — CLI parser accepts malformed flags / extra positional args (A, B) ✅ resolved
Two reviewers independently found ambiguous argv shapes that resolve
to unexpected modes.
**Fix:** added table-driven parser validation plus fuzz-style parser tests;
reject unrecognised flags, missing value-flag values, and extra
positionals.

## Minors

| | | reviewers |
|---|---|---|
| m1 | `DEVRIG_HOME=~/...` not expanded; `..` normalised rather than rejected ✅ resolved | A, B, C |
| m2 | `NpxKtRoot` has a production-visible mutable test seam ✅ resolved | A, B, C |
| m3 | Text rendering uses UTF-16 `String.length`, not terminal display width ✅ resolved | A, B |
| m4 | Some unit tests depend on live JetBrains/Google APIs (flaky offline) ✅ resolved | A |
| m5 | Banned silent `catch (_:Exception)` in `IdeDownloader.kt:58-60` ✅ resolved (download-A batch) | A, B, C |
| m6 | Help banner omits `--version <v>` for `backend start/stop` ✅ resolved | C |
| m7 | `tempFile.renameTo(dest)` success not checked in `IdeDownloader.kt:79-98` ✅ resolved (download-A batch) | A |

## Deferred (per user, 2026-05-15)

- **First-start config writers target the real user home (`~/.config/JetBrains/...`, `~/.java/...`).** Reviewers flagged this as a blocker (A, B) — managed IDEs can clobber the user's real JetBrains preferences. User direction: "Let's keep it so for now, we are going to review that step later." Re-open later with a per-backend user-home design.

## Plan / execution

Sequential codex runs via `~/Work/marinator/marinade/marinade/run-agent.sh codex`.
One focused brief per task; collect handoff, push, iterate. Order:

1. **M1 investigation** (read-only first; if false alarm, close out, otherwise
   becomes a new blocker fix).
2. **B1** + **M2** + **M3** + **M6** — all lifecycle-correctness, all in
   `ManagedBackend.kt`. One coherent commit chain.
3. **B2** — archive extraction security, isolated to `IdeUnpacker.kt`.
4. **M5** + **M7** + **m5** + **m7** — download/cache atomicity & banned
   pattern, isolated to `IdeDownloader.kt` + `SevenZipLocator.kt`.
5. **M4** — JSON synthetic IDs, isolated to renderer.
6. **M8** — CLI parser tightening.
7. **m1** + **m2** + **m3** + **m4** + **m6** — polish batch ✅ resolved.

B3 watched but not actively fixed (parallel worker territory).

## Additional items (added 2026-05-15 by user)

### M9 — Centralised downloads folder + cleanup after unpack
Today downloads land in per-backend dirs. Move all download staging to
`~/.mcp-steroid/downloads/`. Once a download is unpacked into
`~/.mcp-steroid/backends/<id>/`, **remove** the file from `downloads/`.
`HomePaths` gets a new `downloadsDir` property; `BackendManager.download`
routes the archive there, unpacks, then `Files.delete()`.

✅ resolved (download-B batch)

### M10 — Recoverable downloads + checksum/signature verification
Two parts:

**Recoverable:** if `download` is interrupted, a follow-up
`download` should resume from the saved bytes via HTTP `Range` request
(or skip from the start if the server doesn't support 206 Partial Content).
The `.partial` extension stays until the full size is verified.

**Verified:** the JetBrains products API exposes per-download checksum
fields (`checksumLink`, `sha256`, signature URL) and Android Studio's
`developer.android.com/studio` page exposes SHA-256 checksums next to
each download URL. After download, fetch the upstream checksum and
verify SHA-256 of the local file. On mismatch → reject the file,
delete, fail loudly.

When the source doesn't expose a checksum we trust:
  - DO log a `WARN` (visible without --debug) noting "no checksum
    available from upstream; skipping verification".
  - Don't fabricate a fallback; just record the gap.

✅ resolved (download-B batch)

## Revised plan / execution

Sequential codex runs. Order:

1. **M1 investigation** (read-only).
2. **B1 / M2 / M3 / M6** — lifecycle in `ManagedBackend.kt`.
3. **B2** — archive extraction security in `IdeUnpacker.kt`.
4. **M5 / M7 / M9 / M10 / m5 / m7** — download path overhaul:
   centralised `downloads/` dir, resumable transfer, SHA-256
   verification, `.partial` atomic rename, fix silent catch +
   unchecked rename. All in `IdeDownloader.kt` + `SevenZipLocator.kt`
   + `HomePaths.kt`.
5. **M4** — JSON synthetic IDs.
6. **M8** — CLI parser tightening.
7. **m1 / m2 / m3 / m4 / m6** — polish ✅ resolved.

## Additional item (added 2026-05-15)

### M11 — `devrig backend provision <id>` — install MCP Steroid into an existing IDE

New CLI subcommand to provision the MCP Steroid plugin into an
**already-running** IDE that was discovered by port scan but doesn't
yet have the plugin installed. The current listing already
distinguishes port-discovered ("mcp-steroid plugin not installed —
project list unavailable") rows; promote the action by appending a
clear pointer:

```
  [3] IntelliJ IDEA Ultimate (port 63342)
        run: devrig backend provision port-63342
```

Identifier: stable port-based id (e.g. `port-63342`) since port-
discovered IDEs don't have a `<product-key>-<version>` natural id.
Surface the same id in the JSON `backends[]` row so machine
consumers can pipe it.

**Research first** (read-only, single codex pass):
- Inspect `~/Work/intellij` for the built-in HTTP server (`org.jetbrains.builtInWebServer` / `BuiltInServerManager` / the `WebServerPathHandler` SPI).
  Document every action the running IDE exposes — specifically:
  - is there an endpoint that returns the plugins / config / system path?
  - is there an endpoint that installs a plugin (with or without restart)?
  - what's the auth model (CSRF token / Origin / nothing)?
- Cross-reference with what Toolbox / Settings Sync uses. Toolbox is
  known to inject plugins; figure out how.
- If no useful endpoint exists, derive the plugins folder from
  `/api/about`'s `productCode` + `baselineVersion` + `buildNumber`
  and the per-OS JetBrains config convention
  (`~/Library/Application Support/JetBrains/<ProductSlug><Version>/plugins/`
  on Mac; `$XDG_CONFIG_HOME/JetBrains/<ProductSlug><Version>/plugins/`
  on Linux; `%APPDATA%\JetBrains\<ProductSlug><Version>\plugins\`
  on Windows).

**Implementation** (after research lands):
- Spawn the same plugin-source resolution as `BackendManager.download`'s
  plugin deploy step: `NpxKtRoot.ijPluginDir()` → copy into the
  target IDE's plugins dir.
- Hot-reload if possible (Plugin Hot Reload plugin or built-in
  dynamic-plugin reloader); otherwise prompt the user to restart the
  IDE.
- JSON output shape: `{tool, action: "provision", id, productCode, pluginsDir, hotReloaded: <true|false>, restartRequired: <true|false>}`.

Slots into the pipeline **right after M1** — before the lifecycle batch.

## Revised pipeline order (2026-05-15)

1. M1 investigation (in flight).
2. **M11 — `backend provision`** (research → design → implement).
3. B1 / M2 / M3 / M6 — `ManagedBackend.kt` lifecycle.
4. B2 — `IdeUnpacker.kt` security.
5. M5 / M7 / M9 / M10 / m5 / m7 — download path overhaul.
6. M4 — JSON synthetic IDs.
7. M8 — CLI parser tightening.
8. m1 / m2 / m3 / m4 / m6 — polish ✅ resolved.

## M1 follow-up: confirmed bug, fix required (2026-05-15)

Investigation (run `task-m1/run_20260515-123930-99884`) confirmed the
IIC-vs-IIU mismatch. **Live evidence:**

- JetBrains products API `?code=IIC&release.type=release` returns
  release 2025.3 with these download URLs:
  - `https://download.jetbrains.com/idea/idea-2025.3-aarch64.dmg`
  - `https://download.jetbrains.com/idea/idea-2025.3.tar.gz`
  - `https://download.jetbrains.com/idea/idea-2025.3.dmg`
  - `https://download.jetbrains.com/idea/idea-2025.3.exe`
- HEAD checks on the same URLs return **HTTP 200 with identical
  Content-Length** to the IIU 2025.3 download. The correctly-named
  Community URL `ideaIC-2025.3-aarch64.dmg` returns **HTTP 404** — JetBrains
  hasn't shipped a Community 2025.3 binary.
- The unpacked `product-info.json` on iter4's host run reported
  `productCode: IU`. The IDE that started was Ultimate.
- The NEXT release in the IIC feed (2025.2.6.2) has proper
  `ideaIC-2025.2.6.2-aarch64.dmg` URLs (HTTP 200), unpacks as
  Community.

**Fix:**

1. `IdeReleaseLookup.kt`: when iterating product API releases, require
   the chosen download URL's filename to contain a product-specific
   token. For Community editions, that token is `ideaIC-` / `pycharm-community-`
   / `IC-` (whichever the per-OS URL uses). Skip releases whose URL
   filename doesn't match. The most recent stable that passes the
   filter becomes the "latest stable".
2. `BackendManager.download`: post-unpack, read
   `<bundle>/Contents/Resources/product-info.json` (macOS) or
   `<bundle>/product-info.json` (Linux/Windows). Assert the
   `productCode` matches what the requested IdeProduct expects
   (`IC` for `IdeProduct.IntelliJIdeaCommunity` etc.). On mismatch:
   delete the broken install, fail loudly.

This is now a **blocker fix** (was logged as M1 investigation). Pipeline
moves M1-fix in front of M11.

## Revised pipeline order (2026-05-15)

1. **M1-fix** — IIC resolver filter + post-unpack productCode assertion.
2. M11 — `backend provision`.
3. B1 / M2 / M3 / M6 — `ManagedBackend.kt` lifecycle.
4. B2 — `IdeUnpacker.kt` security.
5. M5 / M7 / M9 / M10 / m5 / m7 — download path overhaul.
6. M4 — JSON synthetic IDs.
7. M8 — CLI parser tightening.
8. m1 / m2 / m3 / m4 / m6 — polish ✅ resolved.

## Additional item (added 2026-05-15)

### M12 — Managed-backend GUI test: stream `devrig …` output to the on-video console

`test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/ManagedBackendGuiIntegrationTest.kt`
currently runs the `devrig backend download/start/stop` commands via
container exec, captures stdout/stderr to assertion strings, and the
video records only Xvfb's fluxbox desktop. The viewer can't see what
the test is actually doing.

Make the video more useful:
- Run each `devrig …` invocation inside a visible `xterm` window on
  the Xvfb display (the container's `ide-base` image already has
  `xterm` + `xvfb` + `fluxbox` + `ffmpeg` ready).
- Pipe the command's combined output through `tee` so the test still
  gets the bytes for assertions, AND the xterm shows them in
  real time.
- The "frame" the existing tests use to spawn IDE windows is
  reusable — see `WhatYouSeeTest` for an `xterm`-as-IDE-frame
  precedent, and the existing `XcvbVideoDriver` for the recording
  loop. No new infrastructure needed.

Shape:

```kotlin
container.execAndAssertOnVideo(
    title = "devrig backend download idea-community",
    script = "DEVRIG_HOME=/tmp/mcp-home /home/agent/devrig backend download idea-community",
)
```

…where `execAndAssertOnVideo` launches `xterm -title <…> -hold -e bash -c <script>` against `DISPLAY=:0`, waits for the wrapped process to exit, captures the exit code + bytes via the `tee` sidekick (write to a file the test reads after the xterm window closes), then asserts on the captured output.

Slot: between M8 (parser tightening) and the polish batch.

✅ resolved

## Revised pipeline order (2026-05-15, final)

1. M1-fix — IIC resolver filter + post-unpack assertion (in flight).
2. M11 — `backend provision`.
3. B1 / M2 / M3 / M6 — `ManagedBackend.kt` lifecycle.
4. B2 — `IdeUnpacker.kt` security.
5. M5 / M7 / M9 / M10 / m5 / m7 — download path overhaul.
6. M4 — JSON synthetic IDs.
7. M8 — CLI parser tightening.
8. **M12 — managed-backend test: stream `devrig` output to the on-video xterm.**
9. m1 / m2 / m3 / m4 / m6 — polish ✅ resolved.

## M13 — `backend provision` three explicit methods (2026-05-15)

M11 landed Option-B-as-I-then-called-it (file-system install with
PathManager-default plugin folder). User now wants all three options
exposed as choices:

- **A — install-files**: discover plugin folder via the IDE's own
  `~/.intellij/<pid>-built-in-server.json` (written by
  `BuiltInServerDiscoveryService` in `community/platform/built-in-server/
  src/org/jetbrains/ide/BuiltInServerInfoService.kt` when the registry
  flag `ij.platform.experimental.discoverability` is on). That JSON's
  `paths.plugins` is the **authoritative** override-respecting plugins
  folder. Write our plugin tree there; print restart hint.
  - Fallback when the discoverability JSON isn't present: PathManager-
    default convention (the existing M11 logic).
- **B — install-marketplace**: GET `/api/installPlugin?pluginId=com.jonnyzzz.mcp-steroid&action=install`
  against the target IDE. `Origin: http://localhost` is required to
  avoid the trust prompt; even so, the IDE pops the REST API consent
  dialog (its wording is already toned down in our parallel
  `marinator/rest-api-dialog-wording` branch). MCP Steroid is on
  Marketplace at plugin id 27834.
- **C — manual**: print "to install manually: drop the plugin into
  <suggested path>, then restart the IDE", and exit. Useful when neither
  REST nor file-system access is desirable.

CLI: `devrig backend provision <id> --method <auto|install-files|install-marketplace|manual>`. Default `auto`:
1. install-files (prefer discoverability JSON; fall back to PathManager-default).
2. If install-files's chosen directory isn't writable, prompt the
   user to retry with `--method install-marketplace` or `--method manual`.

JSON output adds a `method` field on every variant.

## Pipeline update

M13 slots **right after M11** (already done) and BEFORE the lifecycle
batch B1/M2/M3/M6 — touches the same `BackendProvision*` files M11
just added.

## M13 update (2026-05-15) — manual mode only

User feedback:
- **Option A (install-files via `~/.intellij/<pid>-built-in-server.json`)** — not viable. `BuiltInServerDiscoveryService` was reverted upstream; the discoverability JSON isn't in shipped IDE builds.
- **Option B (install-marketplace)** — not great because the IDE would pull a Marketplace build, NOT the version bundled with the devrig launcher. We want the bundled plugin to be the source of truth.

Therefore M13's deliverable shrinks to **manual mode only**:
- `devrig backend provision <id>` prints actionable instructions and the per-OS suggested install path (best-effort derived from `/api/about`), then exits.
- No file writes, no REST install calls.
- Listing of port-discovered IDEs continues to suggest `devrig backend provision port-<port>` next to each row.

M11's filesystem-install code path needs to be **removed** in this iteration — keep the listing + the `port-<port>` id parsing, drop the actual copy-files step. (Or hide it behind an unadvertised `--method install-files` flag that defaults off.) Net result: fewer surfaces to maintain; the user makes the install decision deliberately.

✅ resolved (manual mode only)

## M14 — Relocate MCP Steroid plugin marker into `~/.mcp-steroid/markers/`

Today the plugin writes the PID-marker file at `~/.<pid>.mcp-steroid`
(directly in the user home root). That's noisy and conflicts with the
"all state under `~/.mcp-steroid/`" principle the rest of the project now follows.

Both sides need updating in lockstep:

1. **Plugin (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ServerUrlWriter.kt`)**:
   - Write to `~/.mcp-steroid/markers/<pid>.mcp-steroid` (no leading dot in the filename — we don't need to hide files inside a dedicated subdir).
   - Create the subdir on first write.
   - Cleanup logic now scans `~/.mcp-steroid/markers/` instead of the home root.
   - Honor `MCP_STEROID_HOME` env var when set (plugin reads env at IDE startup).

2. **Proxy (`npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/proxy/monitor/IdeDiscovery.kt`)**:
   - Scan `~/.mcp-steroid/markers/` (via `homePaths.markersDir`).
   - Drop the home-root scan entirely after one release — for now, scan BOTH and warn (DEBUG) when the legacy location surfaces something.

3. **Shared (`mcp-steroid-server` module's `PidMarker`)**:
   - Add `PidMarker.markerDirectory(userHome: Path): Path` returning
     `userHome.resolve(".mcp-steroid/markers")` so plugin and proxy
     can't drift.
   - Filename helper: `markerFileNameFor(pid)` returns `$pid.mcp-steroid`
     (the leading-dot legacy `fileNameFor` stays for compat reads).

4. **HomePaths**: add `markersDir = home.resolve("markers")` (the path lives under the managed home root the proxy already manages).

5. **Stale-file cleanup**: keep the existing pid-alive check (`ProcessHandle.of(pid).isPresent`); apply it to the new directory.

6. **Tests**: bump the existing discovery tests to use the new location; add one to confirm the proxy still reads a legacy `~/.<pid>.mcp-steroid` (with a DEBUG log) for the transition window.

✅ resolved

## Pipeline update (2026-05-15, again)

1. Lifecycle batch B1/M2/M3/M6 (running).
2. **M13** (now: manual-only — small shrink-down of the M11 surface).
3. **M14** (marker relocation: plugin + proxy in lockstep).
4. B2 archive security.
5. Download overhaul M5+M7+M9+M10+m5+m7.
6. M4 JSON synthetic IDs.
7. M8 parser tighten.
8. M12 video xterm.
9. Polish m1+m2+m3+m4+m6 ✅ resolved.

## Post-pipeline follow-ups (added 2026-05-15)

Synthesised from each codex run's `IMPROVEMENTS.md` after the
managed-backends pipeline landed. Higher priority first.

### F1 — M8 introduced a regression: `backend --help` / `backend --version` exit 64 ✅ resolved

`Cli.kt`'s strict allow-list (commit `dc733cac`) dropped `--help` /
`--version` from every backend mode's allowed-flag set. Pre-tightening,
`parseCliMode` checked `--help` / `-h` BEFORE `backend`, so `backend --help`
routed to Help. Now it lands in `Unknown` with exit 64.

**Fix:** re-introduce `--help` / `-h` / `--version` / `-v` as universal
flags that route to Help / Version regardless of the mode keyword
preceding them. Add explicit parametrised tests for
`backend --help`, `backend download --help`, `project --version`.

### F2 — `:npx-kt:installDist` fails when previous tree has read-only bundled JDK files ✅ resolved

Surfaced by `task-lifecycle` and `task-m13`. Manual recovery is
`chmod -R u+w npx-kt/build/install/mcp-steroid-proxy && rm -rf` before
rerunning.

**Fix:** in `npx-kt/build.gradle.kts`, add a `doFirst { ... }` to the
`installDist` task that chmod-fixes any pre-existing install tree
before the `Sync` task copies into it.

### F3 — `SevenZipLocatorTest` writes through the real `~/.cache/mcp-steroid/7z/` ✅ resolved

`SevenZipLocator.cacheRoot` is a `private val by lazy` bound to
`user.home`. Tests run against the real host cache, leaving side
effects.

**Fix:** mirror the `NpxKtRootTestSupport` pattern from m2 — a
`SevenZipLocatorTestSupport` object in the test sourceset overrides
the cache root via package-internal access. Reset between tests.

### F4 — `IntelliJPortDiscoveryTest` has a port-bind race ✅ resolved

`task-polish` hit `Address already in use` on its first full-module
run; immediate rerun passed.

**Fix:** bind Ktor fake IDE servers with `embeddedServer(port = 0)` and
read the resolved connector port after start. No pre-release
`ServerSocket(0).localPort` hand-off and no `Thread.sleep` retries.

### F5 — GUI integration test re-downloads ~1 GB IDE archive every run ✅ resolved

Cold-cache run is ~2 minutes; transient `EOFException` mid-stream has
surfaced (M14 run).

**Fix:** persistent test archive cache under
`~/.cache/mcp-steroid-test/` reused across dev-loop runs; falls back to
fresh download when the file is missing/stale. The test stages cached
`ideaIC-*.tar.gz` archives into `/tmp/mcp-home/downloads/` before
`devrig backend download` and back-populates archive files after a
successful download.

### F6 — Plugin `sinceBuild` must move with the resolver's oldest fallback ✅ resolved

M1-fix's `idea-community` fallback to 2025.2.6.2 (build `IC-252`) does
not load with `sinceBuild=253`. The two surfaces (resolver oldest
release in `IdeProduct.knownProducts` + plugin manifest `sinceBuild`)
are coupled but live in different modules.

**Fix:** declare `MANAGED_BACKEND_MIN_SUPPORTED_BUILD = "252"` in
`:intellij-downloader` and gate `:ij-plugin:test` with
`PluginCompatibilityFloorTest`, which reads `ij-plugin/build.gradle.kts`
and fails if `pluginConfiguration.ideaVersion.sinceBuild` drifts from
the resolver baseline.

### F7 — Remove legacy home-root marker fallback after one release (DEFERRED)

The M14 transition keeps a DEBUG-only fallback scanning
`~/.<pid>.mcp-steroid`. Drop the fallback + transition tests after
one release cycle (once shipped plugins all write to
`~/.mcp-steroid/markers/`). Track which release first shipped the new
layout (next ij-plugin release after `5e324746`).

**No action now.**

### F8 — Bounded retry on transient `checksumLink` fetch failures ✅ resolved

M10's checksum verification fails closed if the `.sha256` URL returns
a transient error. Safest default; but CDN blips would unnecessarily
fail downloads.

**Fix:** small bounded retry (3 attempts, exponential backoff capped
at ~10 s) inside the checksum-fetch path only. Final failure still
surfaces the error verbatim.

### F9 — Log the selected archive URL on `--debug` even on cache hits ✅ resolved

Today only a cold download surfaces the URL chosen by the resolver;
a cached rerun shows just the products-API fetch. Hampers forensics.

**Fix:** in `IdeDistribution.resolveAndDownload`, log the resolved
URL + local destination at INFO (always) or DEBUG (selectable) BEFORE
the cache check.

### F10 — npx-kt fixtures conflate API product code (`IIC`) with installed `product-info.json` code (`IC`) ✅ resolved

Naming hygiene only. Fixtures should distinguish:
- `apiProductCode = "IIC"` — JetBrains products-API code
- `installedProductCode = "IC"` — value in `product-info.json`

### F11 — `backend provision` listing should hide IDEs already running MCP Steroid ✅ resolved

Today the listing scans all port-discovered IDEs, including ones
with the plugin already loaded. Once an IDE has produced a marker
file, the listing should hide those rows.

**Fix:** provision list now runs the same marker discovery as `backend`,
filters port-discovered rows whose normalised build already appears in
a marker, prints the already-installed message when the filter empties
the list, and emits `discoveryNote` in JSON when rows were removed.

### F12 — East-Asian wide characters and combining marks (display width)

m3 (polish batch) intentionally fixed surrogate-pair / code-point
width only. East-Asian wide characters render at 2 columns; combining
marks at 0. Both still misalign in text mode.

**Fix:** extend `String.codePointWidth()` to consult Unicode East
Asian Width. Defer combining marks if scope grows.

---

## Lifecycle batch (B1 + M2 + M3 + M6) — ✅ resolved 2026-05-15

Codex run `task-lifecycle/run_20260515-133645-60441`. Commits pushed to
`origin/mcp-5`:

- `3a44bb88` — B1: `BackendManager.stop` verifies `ProcessHandle.info().command()`
  resolves under `homePaths.backendsDir` or the `~/.<pid>.mcp-steroid` marker
  decodes & matches descriptor; stale pid file deleted, `{outcome: "stale"}`
  emitted on mismatch, no signal sent.
- `6c5df613` — M2: product-only `backend start/stop <product>` prefers the
  highest locally-installed `<product-key>-<version>` entry; falls back to
  the products API only when nothing is installed.
- `4f3e0ea3` — M3: `BackendManager.start` serialised via
  `FileChannel.tryLock(state/global.lock)`; contended starts exit 64 with
  "another devrig backend operation is in progress; retry shortly".
- `1ea0fc32` — M6: launches redirect stdout/stderr to `logs/managed.log`;
  start text + JSON report that path (Windows still uses WMI detach so
  redirection there is best-effort).

GUI integration test green (`ManagedBackendGui*`, BUILD SUCCESSFUL 2m9s),
manual single-instance smoke green. See run dir for full transcript.
