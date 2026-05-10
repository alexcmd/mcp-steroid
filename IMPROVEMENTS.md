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
