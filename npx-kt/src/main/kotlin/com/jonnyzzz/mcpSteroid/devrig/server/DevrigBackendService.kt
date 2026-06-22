/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.InstalledBackend
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.startableBackends
import com.jonnyzzz.mcpSteroid.server.backendNameFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
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
            get() = backendNameFor(sourceKey = "home:" + installed.ideHome, build = installed.ide.build)
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
) {

    /**
     * Returns running candidates first, followed by startable candidates (installed managed IDEs that
     * are not currently running).
     */
    fun candidates(): List<OpenProjectCandidate> {
        val running = stateProvider()
        val startable = startableBackends(installedProvider(), running)
        return running.map { OpenProjectCandidate.Running(it) } +
                startable.map { OpenProjectCandidate.Startable(it) }
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
        private fun normalizeHome(p: String): String =
            Path.of(p).toAbsolutePath().normalize().toString()

        private fun sameHome(ideHome: String?, target: String): Boolean {
            if (ideHome == null) return false
            return normalizeHome(ideHome) == target
        }
    }
}
