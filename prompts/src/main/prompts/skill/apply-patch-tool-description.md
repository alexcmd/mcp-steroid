Apply Patch Tool

MCP tool description for the steroid_apply_patch tool.

###_NO_AUTO_TOC_###
Atomic multi-site literal-text patch. Apply N `old_string → new_string`
substitutions across one or more files in a single undoable command.

Use this INSTEAD of chaining 2+ native `Edit` calls. Pre-flight
validates every `old_string` is present exactly once per file; if any
hunk fails validation, NO edits land (all-or-nothing). Multi-hunk
edits in the same file apply in descending-offset order automatically
so earlier edits don't shift later ones.

Why this tool vs `steroid_execute_code` with `applyPatch { }`: this
bypasses kotlinc compilation, so large patches (8+ hunks, 3k+ char
payloads) complete in tens of ms instead of tens of seconds — matters
for Claude Code CLI's 60s per-tool MCP timeout.

Input parameters:
- `project_name` (string) — project name from `steroid_list_projects`
- `task_id` (string) — your task id; reuse across related calls
- `reason` (string, optional) — one-line summary
- `hunks` (array of objects) — each with `file_path`, `old_string`, `new_string` (all strings)
- `dry_run` (boolean, optional, default `false`) — when `true`, run preflight
  only. Every anchor is validated and the same "file not found" /
  "old_string not found" / "occurs more than once" errors are returned as on
  a live call, but **no files are written**. Use this when you are
  uncertain whether your hunks still match the current file bytes — a
  dry-run is cheaper than a failed live patch followed by a discovery
  call. Successful dry-runs return `apply-patch (dry-run): N hunks across
  M file(s) would apply atomically.` so the audit-trail string is
  distinguishable from a live run. Note: `dry_run: true` still flushes the
  IDE's unsaved editor buffers to disk before preflight (so the preflight
  reads canonical bytes); the patch itself never writes.

Field names match Claude Code `Edit` exactly (`file_path`, `old_string`, `new_string`) — agents that already know `Edit` can re-use their knowledge directly.

Example hunks: `[{"file_path": "/abs/A.java", "old_string": "old", "new_string": "new"}, {"file_path": "/abs/B.java", "old_string": "other", "new_string": "replacement"}]`.

Return: human-readable audit — `N hunks across M file(s) applied
atomically` + per-hunk `path:line:col (oldLen→newLen chars)`.

On error, do **not** retry blindly with the same hunks. The pre-flight
rejection means either the file path is wrong or the `old_string` no
longer matches the current file bytes. Inspect the file via
`steroid_execute_code` first (`FilenameIndex.getVirtualFilesByName(...)`
to locate, then read the relevant slice) and rebuild the hunk against
the actual text. The "Anchor-safe editing" recipe at
`mcp-steroid://skill/anchor-safe-editing` walks the four steps —
locate → excerpt → unique-occurrence check → apply → verify.

Same underlying engine as `steroid_execute_code`'s `applyPatch { hunk(…) }`
DSL — identical semantics, no boilerplate, no compile overhead.
