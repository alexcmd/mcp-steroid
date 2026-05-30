# DialogKiller hang — modal not closed (investigation notes)

Status: **root cause narrowed, not yet fixed.** Living doc; update as we learn more.

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

## Root cause (current best understanding)

**`withContext(Dispatchers.EDT + ModalityState.any())` is not being dispatched while a
`DialogWrapper.show()` modal is up in this IDE 2026.1.2 / Docker+Xvfb environment.** Every
EDT+`any()` operation — the dialog enumeration, the screenshot capture, and the dialog
`close()` — queues behind the modal and never runs. The killer can therefore never close the
modal, because closing itself needs an EDT+`any()` dispatch that doesn't pump.

Corroborating coroutine dump (`/tmp/ti-dialog-dump.txt`): an execution coroutine was parked
with a sibling `DispatchedCoroutine{Active} state: CREATED [Dispatchers.EDT]` — i.e. an
EDT-dispatched coroutine queued and never started while the EDT sat in `Dialog.show()`'s modal
loop (`AWT-EventQueue-0` in `WaitDispatchSupport.enter` → `DialogWrapper.doShow`).

This **contradicts the assumption** that coroutine `Dispatchers.EDT + any()` ≡
`ApplicationManager.invokeLater(runnable, ModalityState.any())` *for pumping during a modal*,
at least empirically in this 2026.1/Xvfb setup. Confirming/refuting that is the next step.

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
  equivalent; not a fix to pursue.

## Open question (drives the live-debug)

Why is an `any()`-modality EDT coroutine task NOT pumped inside the `DialogWrapper.show()`
secondary event loop in IntelliJ 2026.1 under Xvfb? Breakpoint targets:
- `DialogWindowsLookup.withDialogWindows` line ~84 (the EDT+`any` enumeration).
- `EdtCoroutineDispatcher.dispatch` / `ImmediateEdtCoroutineDispatcher.isDispatchNeeded` /
  `LaterInvocator` flush queue — does the `any()` task get enqueued and is the flush pumped
  during the modal?
- Whether the test's modal (opened from inside the MCP execution coroutine context) carries a
  context that suppresses pumping — hence the fix below to open it from **vanilla EDT**.

## Test-harness issues found

- **Shared lazy `session`** in `DialogKillerIntegrationTest` cascades one stuck modal into all 5
  failures (and ≈55-min runs). Isolate per-test (fresh session) or add a teardown that
  force-closes windows for fast, attributable feedback.
- The test opens its modal from inside a `steroid_execute_code` script (the MCP execution
  coroutine context). It should open from a **vanilla EDT** scope
  (`CoroutineScope(Dispatchers.EDT).launch { … dialog.show() }`) so the modal does not inherit
  the execution's coroutine scope/modality — both more realistic and a cleaner repro.

## Artifacts already in place (origin/main)

- **JDWP debug port on the Dockerized IDE** (`11bec066`): IDE JVM always starts
  `-agentlib:jdwp=…,server=y,suspend=n,address=*:5005` (`IDE_DEBUG_PORT`), exposed + printed as
  `IDE_DEBUG_PORT=<host-port>` (console + `session-info.txt`). Docs in `test-integration/AGENTS.md`
  → "Remote-debugging the Dockerized IDE". Use this to attach IntelliJ "Remote JVM Debug" and
  step through the EDT+`any` dispatch live.
- **Diagnostic logging** (`2e26ec6a`) in `DialogKiller` (capture/close boundaries) — currently
  partly reverted with the Yuri revert; re-add as needed.
- **VisionService refactor** (`0331c040`): `object` → `@Service(Service.Level.PROJECT)`; `capture`
  returns after the screenshot image only; OCR (and any `deferred` provider) is queued on the
  service scope to run AFTER capture. Good hygiene, but does NOT fix this hang.
- **Yuri revert** (working tree, uncommitted): `DialogWindowsLookup` restored to timeout-based
  `canPumpEdtNonModal`. Did NOT fix the hang; keep only if the team prefers it, otherwise restore
  `c2c1ddd1`.

## Next steps

1. Open the test modal from vanilla EDT (above).
2. Live-debug via `IDE_DEBUG_PORT`: confirm exactly why the EDT+`any` enumeration task isn't
   dispatched during the modal.
3. Once the precise mechanism is known, fix (likely a platform-correct way to run work during a
   modal, or a different close mechanism that the modal loop pumps).
