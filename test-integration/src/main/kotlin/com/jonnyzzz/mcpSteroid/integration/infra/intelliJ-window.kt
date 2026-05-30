package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer


fun waitAndLayoutIntelliJWindow(
    ijProcess: RunningContainerProcess,
    container: ContainerDriver,
    windowsDriver: XcvbWindowDriver,
    realConsoleTitle: String,
    windowsLayout: WindowLayoutManager,
) {
    var trackedPids = setOf(ijProcess.pid)
    var lastPidRefreshAt = 0L
    var lastWindows = emptyList<WindowInfo>()
    var lastWindowDiagnosticsAt = 0L
    val ijWindowInfo = try {
        // 60s is safe: X11 frame appears before most startup work completes; the only
        // confirmed long blocker (AIPromoWindowAdvisor, 480s) is suppressed by our 4-layer fix.
        // Research confirmed no other pre-frame blocking network calls in the startup path.
        waitForValue(60_000, "Waiting for IDE window") {
            val now = System.currentTimeMillis()
            if (now - lastPidRefreshAt >= 1_000) {
                trackedPids = discoverProcessFamilyPids(container, ijProcess.pid)
                lastPidRefreshAt = now
            }

            lastWindows = windowsDriver.listWindows()

            if (now - lastWindowDiagnosticsAt >= 5_000) {
                lastWindowDiagnosticsAt = now
                println("[IDE-AGENT] Waiting for IDE window: PIDs=${trackedPids.sorted()}, visible=${lastWindows.size}")
                lastWindows.forEach { w ->
                    println("[IDE-AGENT]   id=${w.id} pid=${w.pid} ${w.rect.width}x${w.rect.height} title='${w.title}'")
                }
            }

            pickIdeWindow(lastWindows, trackedPids, realConsoleTitle)
        }
    } catch (t: RuntimeException) {
        val windowsSnapshot = lastWindows.joinToString(separator = "\n") { info ->
            "id=${info.id} pid=${info.pid} rect=${info.rect.width}x${info.rect.height}+${info.rect.x}+${info.rect.y} title='${info.title}'"
        }
        throw RuntimeException(
            buildString {
                append("Failed waiting for IDE window.")
                append(" trackedPids=${trackedPids.sorted()}")
                if (windowsSnapshot.isNotEmpty()) {
                    appendLine()
                    append("Visible windows:")
                    appendLine()
                    append(windowsSnapshot)
                }
            },
            t,
        )
    }

    windowsDriver.updateLayout(ijWindowInfo, windowsLayout.layoutIntelliJWindow())

    // Re-layout the console window now that fluxbox is fully settled (decorations applied).
    // The first updateLayout call in createConsoleDriver races with fluxbox applying the
    // apps file {NONE} decorations — by this point IntelliJ is up, so fluxbox has had
    // 30+ seconds to settle and the console position is corrected.
    val consoleWindow = windowsDriver.listWindows()
        .firstOrNull { it.title.contains(realConsoleTitle, ignoreCase = true) }
    if (consoleWindow != null) {
        windowsDriver.updateLayout(consoleWindow, windowsLayout.layoutStatusConsoleWindow())
    }
}

private fun pickIdeWindow(
    windows: List<WindowInfo>,
    candidatePids: Set<Long>,
    consoleTitle: String,
): WindowInfo? {
    val sizableWindows = windows
        .asSequence()
        .filter { it.rect.width > 300 && it.rect.height > 300 }
        .filter { it.title.isNotBlank() }
        .filterNot { it.title.equals("Desktop", ignoreCase = true) }
        .toList()
    if (sizableWindows.isEmpty()) return null

    // First preference: match by known process family PID
    val byProcessFamily = sizableWindows.filter { window ->
        val pid = window.pid ?: return@filter false
        pid in candidatePids
    }
    if (byProcessFamily.isNotEmpty()) {
        return byProcessFamily.maxByOrNull { it.rect.width * it.rect.height }
    }

    // Fallback: largest sizable non-console window (covers windows without exposed PID)
    return sizableWindows
        .asSequence()
        .filterNot { it.title.contains(consoleTitle, ignoreCase = true) }
        .maxByOrNull { it.rect.width * it.rect.height }
}


private fun discoverProcessFamilyPids(container: ContainerDriver, rootPid: Long): Set<Long> {
    val processMap = container.startProcessInContainer {
        this
            .args("bash", "-c", "ps -eo pid=,ppid=")
            .timeoutSeconds(5)
            .quietly()
            .description("ps -eo pid=,ppid=")
    }.awaitForProcessFinish()
    if (processMap.exitCode != 0) return setOf(rootPid)

    val childrenByParent = mutableMapOf<Long, MutableList<Long>>()
    processMap.stdout.lineSequence().forEach { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 2) return@forEach
        val pid = parts[0].toLongOrNull() ?: return@forEach
        val ppid = parts[1].toLongOrNull() ?: return@forEach
        childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
    }

    val discovered = linkedSetOf(rootPid)
    val queue = ArrayDeque<Long>()
    queue.add(rootPid)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (child in childrenByParent[current].orEmpty()) {
            if (discovered.add(child)) {
                queue.add(child)
            }
        }
    }

    return discovered
}
