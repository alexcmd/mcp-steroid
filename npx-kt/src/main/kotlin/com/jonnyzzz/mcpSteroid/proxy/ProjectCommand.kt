/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

internal data class ProjectListing(
    val markerRows: List<BackendRow.FromMarker>,
    val portRows: List<BackendRow.FromPort>,
)

internal fun runProjectCommand(
    out: PrintStream,
    json: Boolean = false,
) {
    val listing = projectListingFromRows(collectBackendRows())
    if (json) {
        renderProjectJson(listing, out)
    } else {
        renderProjectOutput(listing, out)
    }
}

internal fun projectListingFromRows(rows: List<BackendRow>): ProjectListing = ProjectListing(
    markerRows = rows.filterIsInstance<BackendRow.FromMarker>(),
    portRows = rows.filterIsInstance<BackendRow.FromPort>(),
)

/**
 * Pure renderer for `mcp-steroid-proxy project`.
 *
 * Output shape:
 * ```
 * devrig v<version> — <tagline>
 *
 * Listing N open project(s) across M IDE(s):
 *
 *   [1] <project-name>  →  <project-path>
 *         <IDE name> <version> (pid <pid>)
 *
 * Skipped K IDE(s) with no mcp-steroid plugin:
 *   - <IDE display> (build <build>, port <port>)
 *
 * ```
 *
 * The project rows are a flattened view of the same marker snapshots the
 * `backend` command fetches. Port-only IDEs and marker IDEs whose snapshot
 * fetch failed are excluded from the project list and reported in a footer.
 */
internal fun renderProjectOutput(listing: ProjectListing, out: PrintStream) {
    out.println("$BRAND_NAME v${loadProxyVersion()} — $BRAND_TAGLINE")
    out.println()

    if (listing.markerRows.isEmpty() && listing.portRows.isEmpty()) {
        out.println(NO_IDES_DETECTED_MESSAGE)
        out.println()
        return
    }

    val reachableRows = listing.markerRows.filter { it.projects != null }
    val projectEntries = reachableRows.flatMap { row ->
        row.projects.orEmpty().map { project -> ProjectEntry(row, project) }
    }

    if (projectEntries.isEmpty()) {
        out.println("No open projects across ${reachableRows.size} IDE(s).")
        renderSkippedProjectFooter(listing, out, leadingBlank = true)
        out.println()
        return
    }

    out.println("Listing ${projectEntries.size} open project(s) across ${reachableRows.size} IDE(s):")
    out.println()
    val padWidth = projectEntries.maxOf { it.project.name.length }.coerceAtMost(40)
    for ((index, entry) in projectEntries.withIndex()) {
        val paddedName = entry.project.name.padEnd(padWidth)
        out.println("  [${index + 1}] $paddedName  →  ${entry.project.path}")
        out.println("        ${formatMarkerBackendIdentity(entry.row.ide)}")
        if (index < projectEntries.lastIndex) out.println()
    }

    renderSkippedProjectFooter(listing, out, leadingBlank = true)
    out.println()
}

/**
 * Pretty-printed JSON renderer for `mcp-steroid-proxy project --json`.
 *
 * Output shape:
 * ```
 * {
 *   "tool": { "name": "devrig", "version": "..." },
 *   "ides": [
 *     { "id": "ide-0", "name": "...", "version": "...", "build": "...", "pid": 123, "mcpUrl": "..." }
 *   ],
 *   "projects": [
 *     { "ide": "ide-0", "name": "myproject", "path": "/Users/me/myproject" }
 *   ],
 *   "skipped": [
 *     { "reason": "port-discovered, no mcp-steroid plugin", "port": 63342, "displayName": "IntelliJ IDEA" }
 *   ]
 * }
 * ```
 *
 * `projects[].ide` references `ides[].id`. The IDE identity field names match
 * `backend --json` marker rows exactly (`name`, `version`, `build`, `pid`,
 * `mcpUrl`) so scripts can share display/selection logic across subcommands.
 */
internal fun renderProjectJson(listing: ProjectListing, out: PrintStream) {
    val reachableRows = listing.markerRows.filter { it.projects != null }
    val rowIds = reachableRows.withIndex().associate { (index, row) -> row to "ide-$index" }
    val json = Json { prettyPrint = true; encodeDefaults = true }
    val payload = buildJsonObject {
        put("tool", buildJsonObject {
            put("name", BRAND_NAME)
            put("version", loadProxyVersion())
        })
        put("ides", buildJsonArray {
            for (row in reachableRows) {
                add(buildJsonObject {
                    put("id", rowIds.getValue(row))
                    putJsonFields(markerBackendIdentityJson(row.ide))
                })
            }
        })
        put("projects", buildJsonArray {
            for (row in reachableRows) {
                val ideId = rowIds.getValue(row)
                for (project in row.projects.orEmpty()) {
                    add(projectToJson(ideId, project))
                }
            }
        })
        put("skipped", buildJsonArray {
            for (row in listing.markerRows.filter { it.projects == null }) {
                add(buildJsonObject {
                    put("reason", "unreachable: ${row.errorMessage ?: "unreachable"}")
                    putJsonFields(markerBackendIdentityJson(row.ide))
                })
            }
            for (row in listing.portRows) {
                add(buildJsonObject {
                    put("reason", "port-discovered, no mcp-steroid plugin")
                    putJsonFields(portBackendIdentityJson(row.ide))
                })
            }
        })
    }
    out.println(json.encodeToString(JsonObject.serializer(), payload))
}

private data class ProjectEntry(
    val row: BackendRow.FromMarker,
    val project: ProjectInfo,
)

private fun projectToJson(ideId: String, project: ProjectInfo): JsonObject = buildJsonObject {
    put("ide", ideId)
    put("name", project.name)
    put("path", project.path)
}

private fun renderSkippedProjectFooter(
    listing: ProjectListing,
    out: PrintStream,
    leadingBlank: Boolean,
) {
    val unreachableRows = listing.markerRows.filter { it.projects == null }
    val portRows = listing.portRows
    if (unreachableRows.isEmpty() && portRows.isEmpty()) return

    if (leadingBlank) out.println()

    if (unreachableRows.isNotEmpty()) {
        out.println("Skipped ${unreachableRows.size} IDE(s) that did not return a project snapshot:")
        for (row in unreachableRows) {
            out.println("  - ${formatMarkerBackendIdentity(row.ide)}: unreachable: ${row.errorMessage ?: "unreachable"}")
        }
        if (portRows.isNotEmpty()) out.println()
    }

    if (portRows.isNotEmpty()) {
        out.println("Skipped ${portRows.size} IDE(s) with no mcp-steroid plugin:")
        for (row in portRows) {
            out.println("  - ${formatPortBackendIdentity(row.ide)}")
        }
    }
}
