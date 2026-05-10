# IMPROVEMENTS — branch `mcp-5`

Process notes and friction encountered while implementing the `npx-kt`
project-monitoring service (push-style HTTP, JSON PID markers).

## Decisions taken (with the user, before coding)

- **PID file format**: replace the existing `.<pid>.mcp-steroid` text file
  with a single-file JSON document (`schema=1`, `pid`, `mcpUrl`, `ide`,
  `plugin`, `createdAt`). Rejected: sibling-file or hybrid layouts (extra
  surfaces to keep in sync).
- **Streaming**: `application/x-ndjson`, one complete JSON object per line.
  Rejected: SSE (extra framing for no benefit; we don't need named event
  types here).
- **Event payload**: full snapshot every emit
  (`{type:"snapshot", seq, projects:[...]}`). Consumer state stays trivial —
  replace, don't merge. Rejected: delta events (more consumer state, more
  edge cases on missed messages / reconnect).
- **npx-kt wiring**: instantiate the new monitoring services in `Main.kt`
  alongside the new stdio MCP server. Legacy proxy path
  (`legacyProxyMain`, `ServerRegistry`, `NpxBeacon`) is left alone.

## Friction / observations

- Existing IDE marker file is consumed by **three** readers
  (`npx-kt::Utils.kt::scanMarkers`, the npm-distributed `npx/` TS proxy,
  `:test-helper:NpxProxyInstaller`). The TS reader is out of scope for this
  branch; the Kotlin readers are updated in lockstep with the writer.
- `NoLargeInlineStringsTest` and the `mcp-steroid://` URI lint rules don't
  apply here — no prompt content, no `mcp-steroid://` URIs added.

## Mid-flight clarifications (user, after PidMarker scaffold landed)

- **Forward/backward compat is universal**: `ignoreUnknownKeys = true`
  applies to **every** decoder we touch in this branch — JSON marker file,
  NDJSON wire frames, any request/response body. PidMarker already does
  this; the npx-kt monitor and the IDE-side stream parsers must follow
  suit.
- **Liveness**: IDE emits a `ping` envelope on the projects stream every N
  seconds (target 5s) so the monitor can distinguish "no project changes"
  from "TCP socket silently dead". Reading a `ping` resets a stale-watchdog
  on the consumer; missing it past `N * 3` triggers a reconnect.
- **Client identification**: npx-kt announces itself to the IDE on connect
  (clientId, clientPid, clientVersion, platform/arch). Cleanest fit is a
  `POST /npx/v1/projects/stream` whose request body carries the
  client-info JSON; the response keeps the streaming NDJSON shape. IDE
  logs the announcement and includes `clientInstanceId` on the streamed
  envelopes for traceability.
