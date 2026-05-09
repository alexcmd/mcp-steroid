# CLAUDE.md, AGENTS.md

Guidance for Claude Code when working with this repository. **Instructions here override default behavior.**

## Recursive context lookup (READ THIS FIRST)

Before acting on any task that touches files in a sub-folder, **walk the directory tree from the changed
file's folder up to the project root and read every `CLAUDE.md` and `AGENTS.md` you find on the way**
(including this one). Sub-folder guides take precedence over the root for their own scope; the root only
holds project-wide rules.

Recipe (run this in your head, or with a one-liner). **Normalize the starting directory to an
absolute path first** — relative paths converge on `.` and never match the repo root, infinite-looping:

```bash
# from any file path (relative or absolute), walk parents to repo root, collecting CLAUDE.md / AGENTS.md
file="<changed-file>"
dir=$(cd "$(dirname "$file")" && pwd)
root=$(git rev-parse --show-toplevel)
while [ "$dir" != "$root" ] && [ "$dir" != "/" ]; do
  for f in CLAUDE.md AGENTS.md; do [ -f "$dir/$f" ] && echo "$dir/$f"; done
  dir=$(dirname "$dir")
done
for f in CLAUDE.md AGENTS.md; do [ -f "$root/$f" ] && echo "$root/$f"; done
```

When changing files across multiple sub-folders, read the guides for each.

## Sub-folder guides

| Folder | Guide | Scope |
|---|---|---|
| `ij-plugin/` | [ij-plugin/CLAUDE.md](ij-plugin/CLAUDE.md) | IntelliJ plugin code, services, threading, build, deployment, sandbox/index troubleshooting, registry keys |
| `prompts/` | [prompts/AGENTS.md](prompts/AGENTS.md) | Prompt file format, IDE conditionals, `mcp-steroid://` resources, KtBlocks |
| `test-integration/` | [test-integration/AGENTS.md](test-integration/AGENTS.md) | Stable Docker IDE smoke tests, shared infra, hung-test diagnosis, multi-version compat tests, playgrounds, Rider/.NET, Linux Docker CI gotchas |
| `test-experiments/` | [test-experiments/CLAUDE.md](test-experiments/CLAUDE.md) | DPAIA arena suite, debugger demos, prompt-quality comparisons, IMPROVEMENTS.md harness |
| `docs/` | [docs/CLAUDE.md](docs/CLAUDE.md) | Autoresearch / prompt-optimization working notes, DPAIA history |
| `website/` | [website/CLAUDE.md](website/CLAUDE.md) | Hugo site sources, GitHub Pages deployment |

## MUST DO

- Use IntelliJ MCP for everything where you can — see `ij-plugin/CLAUDE.md` for the API patterns.
- Never ignore warnings or errors — fix them properly.
- No test-only branches (`isUnitTestMode`) — use correct IntelliJ actions (`writeIntentReadAction`, `writeCommandAction`).
- Tests must show reality. **Never remove, disable, or weaken a failing test**; fix the underlying issue.
- No `@Suppress("DEPRECATION")` — find the non-deprecated replacement.
- Prefer JSON libraries for JSON parsing/manipulation; only static final JSON constants may be hand-written as raw strings.
- Log new ideas/tasks in `TODO*` files (`TODO.md`, `TODO-*.md`).
- Atomic commits with descriptive messages (what and why). Test and build before committing.
- Never include AI as co-author or mention AI in commit messages.

### Banned patterns

- **`runCatching{}.onFailure{}`** — use `try { } catch (e: Exception) { }` instead. Other `runCatching` uses
  (`.getOrNull()`, `.getOrDefault()`) are fine.
- **Empty `catch` / `catch (_: Exception) {}`.** Fail fast and log: every catch must rethrow, log via
  `System.err.println` / `logger.error`, or both. Silent failure hides root causes.
- **`run-agent.sh` references in production code or tests.** It is a manual dev/peer-review tool only.
  Never `COPY` or `chmod +x` it inside Dockerfiles. Implement agent integrations directly via CLI flags.
- **Cross-subproject `build/` directory access** in Gradle build files. Use Gradle dependency configurations.
  Fail fast with `require()`/`error()` — no silent fallbacks.
- **`append("\n")` tricks** to bypass the `NoLargeInlineStringsTest` lint rule. When a `buildString { }`
  exceeds the consecutive-`appendLine` limit, move the content to `prompts/src/main/prompts/` and reference
  it via the article URI.
- **Hardcoded `mcp-steroid://...` URI literals** in production Kotlin. Use the generated article class:
  `XxxPromptArticle().uri` (from `com.jonnyzzz.mcpSteroid.prompts.generated.*`). Enforced by
  `NoHardcodedMcpSteroidUriUsageTest`. See `FetchResourceToolHandler.kt`.
- **Infrastructure workarounds in tests.** When a test fails due to missing Docker socket, missing CLI,
  wrong JDK, or missing native library, fix the infrastructure — never add detection-and-skip code.
- **Detecting failures and skipping tests at runtime** (`try { } catch { skip() }`,
  `Assumptions.assumeTrue(isAvailable)`, `TestAbortedException` on error). The only acceptable skip is at
  the **Gradle task level** (`enabled = !condition`) when an entire suite is structurally incompatible
  with the platform.
  - **Single documented exception: Gemini API key on CI.** TC has no Gemini token and there is no plan to
    add one. `DockerGeminiSession.Companion` opts into `skipTestWhenKeyMissing = true` (see
    `test-helper/.../AISessionBase.kt`), so `requireApiKey()` throws `AssumptionViolatedException` instead
    of `IllegalStateException` when the key is missing. Constraints when working in this area:
    1. **Session creation must stay lazy** — called from inside test method bodies, never from
       `setUp()` / class init / `@ClassRule`. `BasePlatformTestCase`-backed tests route every
       `Throwable` through `JUnit38ClassRunner` to `fireTestFailure`, so an early init failure shows
       up against the wrong test.
    2. **Do NOT add `excludeTestsMatching`** or other test-class-level filters — that hides the test
       from reports.
    3. **Unresolved `%credentialsJSON:…%` must still fail hard** with `IllegalStateException` — that
       branch indicates a real TC misconfiguration and must stay visible. The contract is unit-tested
       in `:test-helper:test` `AIAgentCompanionApiKeyTest`.
    4. **Do NOT extend the opt-in to other agents.** Anthropic / OpenAI keys ARE configured on TC;
       their tests must keep failing if the key disappears.
- **Java threading primitives (`CountDownLatch`, `Semaphore`, `Object.wait()`) in coroutine code.** Use
  `CompletableDeferred<T>` + `withTimeout(d) { deferred.await() }`, `Channel<T>`, or `suspendCancellableCoroutine`.
- **`./gradlew test` at the repo root.** It fans out to every module and can take hours. Always scope:
  `./gradlew :ij-plugin:test`, `./gradlew :kotlin-cli:test`, `./gradlew :prompts:test --tests '<pattern>'`.
  See per-module guidance in `ij-plugin/CLAUDE.md`.

## Test execution discipline

- **NEVER run `:test-integration` or `:test-experiments` tests in parallel.** Each test starts a full
  Docker IntelliJ container. Two concurrent runs exhaust RAM/CPU and OOM-kill both. Wait for completion
  before starting the next. See `test-integration/AGENTS.md` for the full Docker-test playbook.
- **Diagnose stuck/slow tests with JDK tooling BEFORE killing.** `jps -l | grep GradleWorkerMain` →
  `jcmd <pid> Thread.print > /tmp/dump.txt` while the JVM is alive; then
  `grep '<YourTest>Test' /tmp/dump.txt -A 5`. Killing throws away evidence and forces guess-and-retry.
  Once you have the stuck test's name, iterate on just that test (`--tests 'com.example.StuckTest'`
  + `--rerun-tasks`).
- **1-minute rule for integration tests.** Any `:test-integration` / `:test-experiments` case that hasn't
  printed PASS/FAIL within ~60 s of `> Task :*:test` is suspicious — usually a modal dialog, indexing
  stall, or background task that won't finish. Capture the latest screenshot
  (`ls -t test-integration/build/test-logs/test/run-*/screenshot/*.png | head -1`) and an in-container
  thread dump (`docker exec <id> jcmd <PID> Thread.print`) before deciding. Full recipe and
  symptom→cause table in `test-integration/AGENTS.md` → "Debugging a stuck/hung Docker test".

## Project Overview

MCP Steroid — IntelliJ Platform plugin that exposes a standalone MCP server letting LLM agents drive the
IDE via Kotlin code execution.

- **Public repo**: https://github.com/jonnyzzz/mcp-steroid
- **Docs**: [README.md](README.md), [docs/guides/AGENT-STEROID-GUIDE.md](docs/guides/AGENT-STEROID-GUIDE.md)
- **Modules**: see `settings.gradle.kts`. Plugin code lives in `ij-plugin/`; prompt resources in `prompts/`;
  Docker IDE smoke tests in `test-integration/`; experimental/long-running tests in `test-experiments/`.

## Technology Stack

Gradle 9.4.1 / Kotlin 2.2.20 / Java 21 / IntelliJ Platform 2025.3+ / Ktor 3.1.0 (CIO+SSE) / kotlinx.serialization

The Gradle Daemon is pinned to **JDK 21** via `gradle/gradle-daemon-jvm.properties`. The
`foojay-resolver-convention` plugin in `settings.gradle.kts` is the auto-download fallback if no JDK 21 is
present locally. To change the daemon JVM: edit `gradle-daemon-jvm.properties` directly (one-line
`toolchainVersion=N`).

## Workflow

1. Read requirements; ask if ambiguous.
2. Add a failing test, then implement (test-first; integration tests preferred; never fake tests).
3. Run Gradle build/test via the IDE's MCP, not shell — see `ij-plugin/CLAUDE.md` for the run-config recipe.
4. Deploy: `./gradlew deployPlugin`.
5. Test with IntelliJ MCP. Validate full Docker scenarios via `:test-integration:test` / `:test-experiments:test`.
6. Use `steroid_execute_code` to verify warnings/errors are gone before declaring done.
7. Update `TODO*` and commit.

## CI

Root `build.gradle.kts` defines `ci`-prefixed aggregator tasks for TeamCity and GitHub Actions.
`./gradlew tasks --group ci` lists them.

| Task | Subprojects | Notes |
|------|-------------|-------|
| `buildPluginOnCI` | `:ij-plugin` (builds + publishes ZIP) | Entry point for both GH Actions and TC |
| `ciBuildPluginTests` | All plugin modules **except** prompts + non-plugin | Per-OS matrix on TC |
| `ciBuildPromptsTests` | `prompt-generator`, `prompts`, `prompts-api` | Linux only; full matrix takes 60–120+ min |
| `ciIntegrationTests` | `:test-helper:test` → `:ij-plugin:integrationTest` → `:test-integration:test` | Strict sequential ordering via `mustRunAfter`; needs Docker + API keys |

`:test-integration:test` and `:test-experiments:test` have an `onlyIf` guard — plain root `./gradlew test`
silently skips both. Direct `./gradlew :test-integration:test --tests '...'` still works.

**TeamCity DSL** lives in a separate repo (`~/Work/mcp-steroid-teamcity`). See its own `CLAUDE.md` for the
generate→edit→regenerate→commit workflow. The TC VCS root pulls from `jb`, not `origin` — see "Git remotes" below.

**GitHub Actions** (`.github/workflows/`): builds the publishable plugin ZIP and deploys the website to
GitHub Pages. Plugin tests are intentionally NOT mirrored — full coverage stays on TC (3–5× faster
internal agents). Trigger PR builds via `workflow_dispatch` on the PR's head branch.

## Git Remotes: `origin` vs `jb`

| Remote | URL | Role |
|---|---|---|
| `origin` | `git@github.com:jonnyzzz/mcp-steroid` | Day-to-day development fork; source of truth for new commits |
| `jb` | `git@github.com:JetBrains/mcp-steroid.git` | JetBrains-org mirror; consumed by TeamCity (`mcp_steroid` project) |

**Sync direction:**
- **origin → jb**: always via merge (the `jb-merge` procedure below).
- **jb → origin**: always via cherry-pick (individual commits, manual conflict resolution).
- **Never fast-forward-push `main` to `jb`** — `jb/main` carries org-specific commits that would be lost.

```bash
git fetch jb
git checkout -b jb-merge jb/main
git merge main --no-ff -m "Merge remote-tracking branch 'origin/main' into jb-merge"
git push jb jb-merge:main
git checkout main && git branch -D jb-merge
```

`--no-ff` preserves `jb/main`'s existing head as the merge's first parent so jb-only history stays
reachable. **Why this matters for CI:** TC pulls from `jb`. If your commit isn't on `jb/main`, TC builds
stale code.

## IntelliJ Source Research

The IntelliJ project at `~/Work/intellij` is open in the IDE for research. Use `steroid_execute_code` with
`project_name="intellij"` and PSI APIs (`FilenameIndex`, `JavaPsiFacade`) — faster and more accurate than
`grep`. See `test-integration/AGENTS.md` → "Researching IntelliJ APIs" for the recipe.

`run-agent.sh` from `~/Work/jonnyzzz-ai-coder/` launches AI agents (Claude/Codex/Gemini) for peer reviews,
research, and consensus checks. Encouraged from agent sessions — the BANNED rule applies only to
production code/tests referencing it.

## Environment Constraints

`timeout` / `gtimeout` are not available on this Mac. Use Gradle's own timeout mechanisms or the Bash
tool's `timeout` parameter.
