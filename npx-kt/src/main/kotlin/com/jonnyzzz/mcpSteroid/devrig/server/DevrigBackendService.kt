/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.InstalledBackend
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.normalizeHome
import com.jonnyzzz.mcpSteroid.devrig.startableBackendName
import com.jonnyzzz.mcpSteroid.devrig.startableBackends
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A candidate that can be passed to [DevrigBackendService.ensureBackendRunning].
 * [Running] wraps an already-discovered IDE; [Startable] wraps a managed installed IDE that is
 * not yet running.
 */
sealed interface OpenProjectCandidate {
    val backendName: String
    val displayName: String

    /** An IDE already running (discovered via a pid marker). */
    data class Running(val ide: DiscoveredIde) : OpenProjectCandidate {
        override val backendName: String get() = ide.backendName
        override val displayName: String get() = ide.ide.name
    }

    /** A devrig-managed IDE that is installed but not yet running. */
    data class Startable(val installed: InstalledBackend) : OpenProjectCandidate {
        override val backendName: String
            get() = startableBackendName(installed)
        override val displayName: String get() = installed.ide.name
    }
}

/** Thrown when a startable backend was launched but did not post its pid marker within the timeout. */
class BackendStartTimeoutException(message: String) : RuntimeException(message)

/**
 * Service that lists open-project candidates (running + startable) and can start a not-yet-running
 * IDE, blocking until its pid marker appears.
 *
 * All dependencies are injected so tests can drive fakes without any Java threading primitives.
 */
class DevrigBackendService(
    /** Returns the current list of discovered (running) IDEs. */
    private val stateProvider: () -> List<DiscoveredIde>,
    /** Returns the current list of devrig-managed installed IDEs. */
    private val installedProvider: () -> List<InstalledBackend>,
    /** Launches a managed IDE; must return only after the launch command has been issued (not after the IDE is reachable). */
    private val starter: suspend (InstalledBackend) -> Unit,
    /**
     * Returns the set of managed backend IDs that currently have a live pid file (RUNNING state).
     * Used to exclude running managed IDEs from the startable group so they never appear twice.
     * Defaults to [emptySet] for backwards-compat in tests that do not wire the BackendManager.
     */
    private val runningManagedIdsProvider: () -> Set<String> = { emptySet() },
) {

    /**
     * Returns running candidates first, followed by startable candidates (installed managed IDEs that
     * are not currently running).
     *
     * Running entries are filtered to compatible IDEs only (those whose pid marker carries a non-null
     * [DiscoveredIde.ideHome] — an absent ideHome means an OLD/incompatible plugin version that cannot
     * serve open_project). Running entries are then deduplicated by [OpenProjectCandidate.backendName]
     * so that a single IDE seen via multiple discovery paths does not appear twice.
     */
    fun candidates(): List<OpenProjectCandidate> {
        val running = stateProvider().filter { it.ideHome != null }
        val startable = startableBackends(installedProvider(), running, runningManagedIdsProvider())
        return (running.map { OpenProjectCandidate.Running(it) }.distinctBy { it.backendName } +
                startable.map { OpenProjectCandidate.Startable(it) })
    }

    /**
     * Ensures the given [candidate] is reachable and returns the corresponding [DiscoveredIde].
     *
     * - [OpenProjectCandidate.Running]: returns its [DiscoveredIde] immediately.
     * - [OpenProjectCandidate.Startable]: calls [starter], then polls [stateProvider] until a marker
     *   with a matching `ideHome` appears, or until [timeout] elapses.
     *
     * @throws BackendStartTimeoutException if a startable backend was started but did not become
     *   reachable within [timeout].
     */
    suspend fun ensureBackendRunning(
        candidate: OpenProjectCandidate,
        timeout: Duration = 120.seconds,
    ): DiscoveredIde {
        return when (candidate) {
            is OpenProjectCandidate.Running -> candidate.ide
            is OpenProjectCandidate.Startable -> startAndWait(candidate.installed, timeout)
        }
    }

    private suspend fun startAndWait(installed: InstalledBackend, timeout: Duration): DiscoveredIde {
        starter(installed)
        val targetHome = normalizeHome(installed.ideHome)
        val result = withTimeoutOrNull(timeout) {
            var found: DiscoveredIde? = null
            while (found == null) {
                found = stateProvider().firstOrNull { sameHome(it.ideHome, targetHome) }
                if (found == null) delay(250.milliseconds)
            }
            found
        }
        return result
            ?: throw BackendStartTimeoutException(
                "started ${installed.id} but it did not become reachable within $timeout"
            )
    }

    companion object {
        private fun sameHome(ideHome: String?, target: String): Boolean {
            if (ideHome == null) return false
            return normalizeHome(ideHome) == target
        }
    }
}
