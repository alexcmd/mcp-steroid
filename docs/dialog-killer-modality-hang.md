# DialogKiller hang — modal not closed (investigation notes)

Status: **leading hypothesis identified (write-intent dispatch), not yet confirmed live or
fixed.** Living doc; update as we learn more.

## Symptom

On macOS + Docker Desktop + Xvfb (the local `:test-integration` `DialogKillerIntegrationTest`),
the dialog killer never closes the test's modal `DialogWrapper`. The killing
`steroid_execute_code` execution hangs ~10+ min until the MCP request times out
(`Process MCP request failed … Terminated by timeout`). Because the test class shares one
lazy IDE `session`, the first stuck modal cascades into all 5 tests failing (≈55 min). The
same "elevated modality never resolved" also froze the live IDE's MCP when a probe elevated
modality without closing it. **Passes on CI Linux** historically — this is environment-specific.

## Why it matters

The dialog killer's whole purpose is to **close every modal and restore
`ModalityState.nonModal()`** so VFS refresh, the next execution, and the MCP server can run.
While a modal is up and unresolved, everything downstream is blocked.

## Pinpointed hang location (key result)

From the IDE log of the killing execution (`eid_…-explicit-dialog-killer`):

```
ExecutionManager Starting execution
CodeEvalManager Compiling script
Script evaluation complete
ScriptExecutor Starting execution
ScriptExecutor Running 1 script block(s)        <-- last execution line
[then only unrelated background IDE logs; execution never continues]
```

So it compiles, enters `ScriptExecutor`, reaches the **pre-flight `killProjectDialogs`** (right
after "Running 1 script block(s)"), and hangs there. With the timeout-based detection restored
(see below), `DialogWindowsLookup.withDialogWindows` runs:
1. `canPumpEdtNonModal()` — a 100 ms-timeout probe; correctly returns "modal present" (never hangs).
2. **`withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { findDialogsOwnedBy(projectFrame) }`** — **HANGS** (no timeout).

The DialogKiller's own capture/close logs never appear because it never gets past that
enumeration.

## Root cause (current best understanding — codex-reviewed)

A `run-agent.sh codex` review of the chain of thought **refuted** the first theory ("`any()`
modality isn't pumped"). The corrected, leading hypothesis:

**`Dispatchers.EDT` is the *legacy write-intent* coroutine dispatcher** — it dispatches via
`dispatchCoroutineOnEDT(…, needsWriteIntent = true)` (`EdtCoroutineDispatcher.kt`). During a
`DialogWrapper.show()` modal loop, write-intent runnables can be **withheld by
`NonBlockingFlushQueue`** independently of modality (`NonBlockingFlushQueue.kt:229,316`).
`ModalityState.any()` itself *is* accepted under any modality (`ModalityStateEx.java:102`) — so
the modality filter was never the problem. The killer's enumeration / capture / close all use
`Dispatchers.EDT + any()`, request the write-intent lock, and queue behind the modal forever.

Corroborating coroutine dump (`/tmp/ti-dialog-dump.txt`): an execution coroutine parked with a
sibling `DispatchedCoroutine{Active} state: CREATED [Dispatchers.EDT]` — an EDT-dispatched
coroutine queued and never started while the EDT sat in `WaitDispatchSupport.enter` →
`DialogWrapper.doShow`.

**Candidate fix:** run the killer's modal-time UI work on the **non-write-intent** UI dispatcher,
`Dispatchers.UiWithModelAccess + ModalityState.any().asContextElement()` (verified present in
this platform at `core-api/.../coroutines.kt`), at `DialogWindowsLookup` (enumeration + modality
check), `DialogKiller.closeDialog`, and the `VisionService`/`ScreenshotImageProvider` capture path;
move `delay(10)` out of the UI block; add short timeouts so a future regression surfaces as a tool
error instead of an MCP-request timeout.

**Why this isn't the whole answer (all-weather requirement).** A `DialogWrapper` can be shown by
*any* code path — including IntelliJ's own **old write-intent APIs**. The killer must close
**every** modal regardless of how it was opened, so switching the killer's *own* dispatch to
non-write-intent is necessary but may not be sufficient. This must be **confirmed by live
debugging** (below) before we commit a fix; the `UiWithModelAccess` change was applied and then
**reverted** pending that confirmation.

## What was ruled OUT (so we don't re-chase it)

- **OCR is not the cause.** Sub-agent verified `ExecUtil.execAndGetOutput(cmd, timeout)` is
  fully EDT-independent (pooled reader threads + a JDK semaphore honoring the timeout; zero
  `invokeAndWait`/EDT touchpoints — `LaterInvocator`/`CapturingProcessRunner`/`ProcessHandler`).
  It returns within its timeout regardless of a blocked EDT. (We still deferred it off the
  capture path as good hygiene — see below — but it was never the hang.)
- **The `current() != nonModal()` detection (`c2c1ddd1`, "Kudos Yuriy Artamonov") is not the
  cause.** Reverting it to the timeout-based `canPumpEdtNonModal` probe did NOT fix the hang —
  the *enumeration* (also EDT+`any()`, no timeout) hangs the same way. Sub-agent confirmed
  `ModalityState.current()` reads the global `LaterInvocator` stack and is not corrupted by the
  `any()` context element; `LaterInvocator.isInModalContext()` is the platform-blessed check.
- **A non-`any()` EDT call in the capture path is not the cause.** Audited: `captureOnEdt`,
  `resolveComponent`, `ScreenshotImageProvider.captureComponent` all use
  `Dispatchers.EDT + ModalityState.any()`. The only non-`any` `Dispatchers.EDT` calls are in the
  *input* path (`SwingInputExecutor`), irrelevant here.
- **Raw `invokeLater(any())` vs coroutine `Dispatchers.EDT + any()`** — per maintainer, these are
  equivalent; not a fix to pursue. (Switching to the older API will not help.)
- **The "`any()` modality is not pumped" theory itself — REFUTED by codex.** `any()` is accepted
  under any modality; the real suspect is the *write-intent* nature of legacy `Dispatchers.EDT`
  (see Root cause above), not the modality filter.

## Open question (drives the live-debug)

With the write-intent hypothesis, the live-debug question becomes: during the
`DialogWrapper.show()` modal loop, is the killer's `Dispatchers.EDT + any()` task withheld by
`NonBlockingFlushQueue` because it requests the write-intent lock — and does
`Dispatchers.UiWithModelAccess + any()` (non-write-intent) actually run there? Breakpoint targets:
- `DialogWindowsLookup.withDialogWindows` (the EDT+`any` enumeration) — does it ever enter?
- `EdtCoroutineDispatcher.dispatch` (`needsWriteIntent=true`) vs the `UiWithModelAccess` dispatcher.
- `NonBlockingFlushQueue` (`:229`, `:316`) — is the write-intent runnable enqueued-but-withheld?
- Whether the test's modal (opened from inside the MCP execution coroutine context) carries a
  context that changes dispatch — hence the test now opens it from **vanilla EDT**.

## Test-harness issues found

- **Shared lazy `session`** in `DialogKillerIntegrationTest` cascades one stuck modal into all 5
  failures (and ≈55-min runs). Isolate per-test (fresh session) or add a teardown that
  force-closes windows for fast, attributable feedback.
- The test opens its modal from inside a `steroid_execute_code` script (the MCP execution
  coroutine context). It should open from a **vanilla EDT** scope
  (`CoroutineScope(Dispatchers.EDT).launch { … dialog.show() }`) so the modal does not inherit
  the execution's coroutine scope/modality — both more realistic and a cleaner repro.

## Artifacts already in place (origin/main)

Remote-debug infra is now **complete** — both JVMs always run a JDWP agent, so you can attach and
step through the modal loop live. Both **must stay `suspend=n`** (a `suspend=y` would block the
whole test on CI where nobody attaches — see `jdwp-suspend-n-test-modules` memory):

- **IDE debug port** (`IDE_DEBUG_PORT` = `ContainerPort(5005)`): IDE JVM always starts
  `-agentlib:jdwp=…,server=y,suspend=n,address=*:5005`, published + Docker-mapped.
- **devrig debug port** (`DEVRIG_DEBUG_PORT` = `ContainerPort(5006)`, commit `b970e037`): the
  in-container devrig (`npx-kt`) JVM gets its own agent via `DEVRIG_OPTS` with `quiet=y` (so it
  doesn't corrupt `devrig mpc`'s stdout JSON-RPC). Different port ⇒ debug IDE + devrig at once.
- **Host-side print** matches the JVM's own wording with the **host-mapped** port:
  `Listening for transport dt_socket at address: <host-port>`, plus `IDE_DEBUG_PORT` /
  `DEVRIG_DEBUG_PORT` in `session-info.txt`. (The in-container port is invisible from the host.)
- **Attach recipe**: `mcp-steroid://debugger/debug-attach-remote-jvm`
  (`prompts/.../debugger/debug-attach-remote-jvm.md`) — `RemoteConfiguration` with
  `SERVER_MODE=false` at `localhost:<host-port>`, or low-level
  `DebuggerManagerEx.attachVirtualMachine`. Docs: `test-integration/AGENTS.md` →
  "Remote-debugging the Dockerized IDE".
- **VisionService refactor**: `object` → `@Service(Service.Level.PROJECT)`; `capture` returns after
  the screenshot image only; OCR (and any `deferred` provider) is queued on the service scope to
  run AFTER capture. Good hygiene, but does NOT fix this hang.
- **`UiWithModelAccess` candidate**: applied to `DialogWindowsLookup` then **reverted** — not
  committed pending the live-debug confirmation (all-weather requirement, above).

## Next steps

1. Start a playground / Docker test; grab the host-mapped `IDE_DEBUG_PORT` and attach IntelliJ's
   "Remote JVM Debug" (module `ij-plugin`) per the attach recipe. (Open the test modal from vanilla
   EDT — already done in `DialogKillerIntegrationTest`.)
2. Breakpoint `DialogWindowsLookup.withDialogWindows` + `EdtCoroutineDispatcher.dispatch` +
   `NonBlockingFlushQueue`: confirm the write-intent task is withheld during the modal and that
   `Dispatchers.UiWithModelAccess + any()` is dispatched there.
3. If confirmed, re-apply the `UiWithModelAccess` change across the killer's modal-time UI work
   (enumeration, modality check, close, capture), move `delay(10)` out of the UI block, add short
   timeouts. Verify the killer closes the modal **and restores `ModalityState.nonModal()`**, and
   that it works against modals opened by arbitrary (incl. write-intent) code paths.
