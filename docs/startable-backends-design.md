# Startable backends + backend-listing simplification

**Status:** design approved 2026-06-22; implementation plan pending.

## Goal

Let devrig offer **not-yet-running managed backends** (IDEs devrig downloaded under
`~/.mcp-steroid/backends/`) as *startable*. `steroid_open_project` can pick one, **start it,
block until the IDE is reachable, then open the project** — without the agent having to run
`devrig backend start` first.

Do this while **simplifying** the backend model: delete the abstract `BackendRow` sealed type,
the dual `BackendInfo`/`ListedBackendInfo` schemas, the `backends[]` array on the list tools,
and the brittle process/pid-file correlation for managed backends.

## Compatibility (waived for this release)

The frozen **additive-only devrig↔mcp-steroid wire contract is explicitly lifted for this change**
(this change only). devrig and the plugin ship and deploy together, so we may **reshape or remove**
DTO fields, marker fields, and tool-response shapes outright — no deprecation/back-compat shims.
This is what allows the full cleanup below (removing `backends[]`, deleting `BackendInfo`/
`ListedBackendInfo`, reshaping the CLI `--json`). `docs/PHILOSOPHY.md` Tenet 5 and the
`ij-plugin/CLAUDE.md` wire-contract section are updated to record this one-release waiver.

## Why the current model is too complex

- `BackendRow` (sealed: `FromMarker` / `FromPort` / `FromManaged`) funnels **three unrelated
  data sources** through one type, then `mergeRows` de-duplicates them with build-number and
  pid heuristics (`normaliseBuildForDedup`, `matchingManagedIds`).
- Two output schemas: rich `BackendInfo` (CLI `--json`) and slim `ListedBackendInfo` (MCP), plus
  a third path (`routing.listedBackends()`).
- Managed-running correlation scans OS processes / pid files
  (`scanRunningManagedProcesses`, `DefaultManagedProcessInspector.isAlive`) — unstable and tricky.
- `backends[]` on `steroid_list_projects` / `steroid_list_windows` mostly duplicates the
  `backend_name` already on each project/window item.

## Core idea — `ideHome` is the identity

A backend is identified by the **IDE install home folder**, not by an opaque managed id.

- The plugin already runs *inside* the IDE, so it self-reports its install location via
  `PathManager.getHomePath()`. We add it to the marker.
- A running managed IDE is matched to its installed copy by **`ideHome` equality** — no process
  scanning, no managed-id round-tripping.
- `backend_name` stays a devrig-generated implementation detail; it is never the cross-process key.

## Three explicit data sources (no `BackendRow`)

Each consumer composes the sources it needs — directly, no sealed union, no merge/dedup pass:

- **S1 — running plugin IDEs**: markers → `DiscoveredIde` (via `DevrigProjectRoutingService`).
  These are *routable* — devrig can drive them.
- **S2 — running non-plugin IDEs**: port scan → `DiscoveredIdeByPort`. **Detect-only**; devrig
  cannot drive or start them. **CLI-only** surface.
- **S3 — startable managed backends**: installed under `~/.mcp-steroid/backends/` (ide info +
  home folder + launcher), **minus** any whose `ideHome` already appears in S1 (i.e. not already
  running). Only these are startable — other IDEs must be started manually.

## Changes

### 1. Marker carries `ideHome`

- `PidMarker` gains `val ideHome: String?` — kept nullable only so a marker written by a
  not-yet-upgraded running IDE still decodes (pragmatic robustness, not a compat obligation; the
  additive constraint is waived this release — see Compatibility).
- Plugin `ServerUrlWriter` sets `ideHome = PathManager.getHomePath()` when building the marker.
- devrig `IdePidDiscoveryService` reads it onto a new `DiscoveredIde.ideHome: String?`.

### 2. Startable enumeration + `ensureBackendRunning`

- devrig enumerates S3 by scanning `~/.mcp-steroid/backends/` (reuse the installed-backend
  descriptor read; no `BackendManager.list()` process-state probing for *listing*).
- A service method — `ensureBackendRunning(selected): DiscoveredIde` — owns the running-vs-startable
  decision and **blocks until the IDE is reachable**:
  - **running (S1)** → return its `DiscoveredIde` (no-op).
  - **startable (S3)** → launch the IDE at its `ideHome` (`BackendManager.start` does the real
    launcher/vmoptions/spawn work), then **poll discovery until a marker reports that same
    `ideHome`**, bounded by a timeout, then return that `DiscoveredIde`.
  - **timeout** (default **120 s** — cold IDE start + plugin init to first marker) → fail with a
    clear error: *"started <ide> but it did not become reachable within 120s"*.

### 3. `open_project` (devrig)

- Candidates = **S1 ∪ S3** (running markers + startable managed). **Not S2.**
- **No `backend_name`**: exactly one candidate ⇒ use it; otherwise return the candidate list
  (running + startable, clearly grouped) and require the agent to call again with a chosen
  `backend_name`. (Drops the `newestOf` auto-pick.)
- **With `backend_name`**: resolve to a candidate, hand it to `ensureBackendRunning(...)`, then
  open the project in the returned `DiscoveredIde`. Unknown id ⇒ self-correcting error listing
  candidates. The command never branches on running-vs-startable — the service does.

### 4. `list_projects` / `list_windows`

- **Drop `backends[]`** from both `ListProjectsResponse` and `ListWindowsResponse`.
- Each `ListedProject` / window / background-task item keeps its `backend_name`.
- This removes `ListedBackendInfo` and the rich `BackendInfo` from these tools.

### 5. in-IDE (HTTP-MCP) plugin surface

- `list_projects` / `list_windows`: items carry the **self** `backend_name`; no `backends[]`.
- `open_project`: **ignores** `backend_name` (single-IDE surface; there is only this IDE). Matches
  the existing `OpenProjectToolSpec(includeBackendName = false)` registration.

### 6. CLI `devrig backend`

Render groups directly from the three sources — no `BackendRow`:

1. **MCP Steroid backends** (S1) — *you can work here.*
2. **Other running IDEs** (S2) — *detected; no MCP Steroid; cannot be driven.*
3. **Installed managed backends, not running** (S3) — *startable.*
4. **footer** — how to install more (`devrig backend download …`).

`backend --json` / `project --json` reshape from one `backends[]` (of `BackendInfo`) to the three
explicit groups. **No back-compat shape needed** (no external consumers; output just has to be
valid JSON).

### 7. Deletions

`BackendRow` (sealed) · `BackendInfo` · `ListedBackendInfo` · `mergeRows` + dedup helpers
(`normaliseBuildForDedup`, `matchingManagedIds`) · `BackendInventory`'s merge + process-scan
correlation (`scanRunningManagedProcesses` for *listing*, `isProcessAlive` in the inventory) ·
`collectBackendInfos` · `DevrigProjectRoutingService.newestOf` (auto-pick).

`BackendManager.start` / `download` / `stop` and the descriptor model stay — they do the real
install/launch work behind `ensureBackendRunning` and the CLI `backend download/start/stop`.

## `backend_name` semantics

- Running markers: existing devrig scheme (unchanged).
- Startable: deterministic from `ideHome` so the listing is stable within a session.
- The id a startable backend shows **may differ** from the id it has once running (marker-based).
  That is acceptable — `backend_name` is an implementation detail and `open_project` does
  select→start→open atomically, so the agent never reconciles the two.

## Testing

- **npx-kt unit**:
  - S3 enumeration + S1/S3 `ideHome` correlation (running managed is not double-listed as startable).
  - `ensureBackendRunning`: running ⇒ no-op; startable ⇒ start→wait→return (fake discovery);
    timeout ⇒ clear error.
  - `open_project`: no-arg (one candidate ⇒ use; many ⇒ list+require pick); with-arg ⇒ ensure+open;
    unknown id ⇒ self-correcting error.
  - CLI `backend` rendering of the 3 groups + footer; `--json` 3-group shape.
- **ij-plugin integration**:
  - `PidMarker.ideHome` is populated (`PathManager.getHomePath()`).
  - `/projects` bridge unaffected.
  - list tools no longer emit `backends[]`; in-IDE `open_project` ignores `backend_name`.

## Blast radius / docs to reconcile

Production: `PidMarker`, `ServerUrlWriter`, `IdePidDiscovery`/`DiscoveredIde`,
`DevrigProjectRoutingService`, the devrig list/open handlers, `StubMcpSteroidTools`,
`BackendInventory`/`BackendCommand`/`ProjectCommand`/`BackendInfoMapping`, `ManagedBackend`,
the in-IDE `ListProjectsToolHandlerIJ`/`ListWindowsToolHandlerIJ`/`OpenProjectToolSpec`,
`ListProjectsTool`/`ListWindowsTool` DTOs.

Tests: `McpServerIntegrationTest`, `WirePristinenessTest`, `DevrigToolBridgeClientTest`,
`DevrigListToolHandlersTest`, the `BackendCommand*`/`ProjectCommand*` render tests, routing tests.

### Documentation deliverables (ship with the code)

Agent-facing runtime resources describe live behavior, so they are rewritten **as part of the
implementation** (so docs never lead the deployed binary):

- **`prompts/src/main/prompts/open-project/managing-backends.md`** — rewrite to the simplified
  view: no `backends[]`/`routable` polling; startable backends appear as `open_project` candidates
  and `open_project` starts one and blocks until ready; the CLI `devrig backend …` is for
  installing/managing IDEs, not a prerequisite to open. Remove the managed-preference/auto-pick
  guidance.
- **`docs/guides/AGENT-STEROID-GUIDE.md`** — drop the `backends[]` description from the
  list-tools section; each item carries `backend_name`; backend discovery lives in `open_project`.
- **`docs/PHILOSOPHY.md` Tenet 5** + **`ij-plugin/CLAUDE.md` wire-contract** — refresh stale type
  examples (`BackendInfo`/`ListedBackendInfo` deleted; `backends[]` removed) and record the
  one-release additive-only waiver (see Compatibility above). The Tenet-5 principle itself
  ("devrig-computed output is devrig-owned and free to reshape") is unchanged — it is the
  justification for this cleanup.
- **`docs/devrig-naming.md`** (the backend-naming contract) + **`docs/ARCHITECTURE.md`** — reconcile
  any `backends[]` / `BackendInfo` / backend-listing references with the new model.
- Sweep `README.md` / `website/content/docs/*` / other prompt resources for stray `backends[]`
  references and bring them in line.
