Managing IDE backends for open_project

How open_project starts a not-yet-running managed IDE and how devrig backend manages IDE installs.

# Managing IDE backends

Use this recipe when you need to open a project in a **specific
product/version** (Rider for .NET, GoLand for Go, a particular build,
etc.) or in a **not-yet-running IDE**.

## How `steroid_open_project` resolves a backend

When you call `steroid_open_project` through the devrig stdio MCP
server, devrig composes two candidate sources:

- **Running MCP Steroid IDEs** (S1) — IDEs with the plugin active,
  discovered from pid markers. These are ready to use immediately.
- **Startable managed backends** (S3) — IDEs devrig downloaded under
  `~/.mcp-steroid/backends/` that are **not yet running**. devrig can
  start them on demand.

**Without `backend_name`**: if exactly one candidate exists across S1
and S3, `open_project` uses it automatically. If more than one
candidate exists, it returns the list grouped by kind and asks you to
call again with a chosen `backend_name`.

**With `backend_name`**: devrig resolves the name to a candidate. If
the candidate is a startable managed backend, devrig **starts the IDE
and waits (blocking, up to 120 s) until the IDE is reachable**, then
opens the project — all in a single `open_project` call. You never
need to run `devrig backend start` first.

The command never branches on running-vs-startable — devrig handles
the lifecycle transparently.

## Choosing a `backend_name`

Every `projects[]`, `windows[]`, and `backgroundTasks[]` entry in
`steroid_list_projects` / `steroid_list_windows` carries a
`backend_name`. When you need to target a specific IDE:

1. Call `steroid_list_projects` (or `steroid_list_windows`) — each
   item carries a `backend_name`.
2. Call `steroid_open_project` with the `backend_name` of the IDE you
   want.

If the chosen `backend_name` belongs to a startable managed backend
(not yet running), `open_project` starts it and blocks until it is
reachable, then opens the project.

An unknown or stale `backend_name` returns a self-correcting error
that lists the currently available `backend_name`s (both running and
startable).

**A `backend_name` is not stable across IDE restarts** (it is derived
from the pid or the install path). Re-read `steroid_list_projects`
rather than caching a `backend_name`.

## The `devrig backend` CLI — for installing and managing IDEs

Use `devrig backend` when you need to **install** a new IDE, or to
explicitly control lifecycle. It is **not a prerequisite** to
`open_project` — if the IDE you need is already installed (even if
not running), `open_project` can start it.

- `devrig backend` — show three groups:
  - **MCP Steroid backends** (running, routable) — you can open
    projects here now.
  - **Other running IDEs** (no MCP Steroid plugin) — detected only;
    devrig cannot drive them.
  - **Installed managed backends, not running** — startable via
    `open_project` or `devrig backend start`.
- `devrig backend --json` — machine-readable:
  `{ tool, mcpSteroidBackends[], otherIdes[], startableBackends[] }`.
- `devrig backend download <id>` — fetch an IDE (may take minutes;
  cached and resumable).
- `devrig backend start <id>` / `devrig backend stop <id>` — explicit
  lifecycle control.

The `<id>` values appear in `devrig backend --json`.

## After opening — polling for readiness

`steroid_open_project` returns once the project-open request is
accepted. The project is not fully ready until indexing finishes:

1. Poll `steroid_list_windows` every 2-3 seconds until:
   - The project appears in the list.
   - `modalDialogShowing` is `false`.
   - `indexingInProgress` is `false`.
   - `projectInitialized` is `true`.
2. If `modalDialogShowing` is `true`, call `steroid_take_screenshot`
   to see the dialog and `steroid_input` to interact.

When several IDEs are running, each `windows[]` / `backgroundTasks[]`
entry carries a `backend_name` — use it to track the right IDE.

## Only managed backends are startable

Startable = devrig-installed under `~/.mcp-steroid/backends/`. Other
running IDEs (discovered by port scan, no MCP Steroid plugin) are
detected but cannot be driven or started by devrig. Install the MCP
Steroid plugin in them (`devrig backend provision`) to make them
routable.

# See also

- [Open Project Workflow Overview](mcp-steroid://open-project/overview)
- [Open Project (Trusted)](mcp-steroid://open-project/open-trusted)
- [Open Project (With Dialog Handling)](mcp-steroid://open-project/open-with-dialogs)
- [Open Project via IntelliJ APIs](mcp-steroid://open-project/open-via-code)
