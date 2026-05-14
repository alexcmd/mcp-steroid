/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.server.ProjectInfo
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
        out.println("{}")
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
        out.println("        ${formatMarkerIdeIdentity(entry.row.ide)}")
        if (index < projectEntries.lastIndex) out.println()
    }

    renderSkippedProjectFooter(listing, out, leadingBlank = true)
    out.println()
}

private data class ProjectEntry(
    val row: BackendRow.FromMarker,
    val project: ProjectInfo,
)

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
            out.println("  - ${formatMarkerIdeIdentity(row.ide)}: unreachable: ${row.errorMessage ?: "unreachable"}")
        }
        if (portRows.isNotEmpty()) out.println()
    }

    if (portRows.isNotEmpty()) {
        out.println("Skipped ${portRows.size} IDE(s) with no mcp-steroid plugin:")
        for (row in portRows) {
            out.println("  - ${formatPortIdeIdentity(row.ide)}")
        }
    }
}
