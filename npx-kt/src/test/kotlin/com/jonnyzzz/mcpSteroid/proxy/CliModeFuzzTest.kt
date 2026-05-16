/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.random.Random

class CliModeFuzzTest {

    @ParameterizedTest
    @MethodSource("rejectedInvocations")
    fun `rejected invocations route to Unknown with a hint`(args: Array<String>, expectedHintFragment: String) {
        val mode = parseCliMode(args)
        assertTrue(mode is CliMode.Unknown, "Expected Unknown for ${args.toList()}, got $mode")
        val unknown = mode as CliMode.Unknown
        assertNotNull(unknown.hint)
        assertTrue(
            unknown.hint!!.contains(expectedHintFragment),
            "Expected hint to contain '$expectedHintFragment', got '${unknown.hint}'",
        )
    }

    @ParameterizedTest
    @MethodSource("acceptedInvocations")
    fun `accepted invocations resolve to the expected mode`(args: Array<String>, expectedMode: Any) {
        assertEquals(expectedMode, parseCliMode(args))
    }

    @ParameterizedTest
    @MethodSource("fuzzedInvocations")
    fun `fuzzed invocations never throw`(args: Array<String>) {
        val mode = assertDoesNotThrow<CliMode> { parseCliMode(args) }
        when (mode) {
            CliMode.Mcp,
            CliMode.Help,
            CliMode.Version,
            CliMode.Backend.Text,
            CliMode.Backend.Json,
            CliMode.Project.Text,
            CliMode.Project.Json,
            is CliMode.Backend.DownloadList,
            is CliMode.Backend.StartList,
            is CliMode.Backend.StopList,
            is CliMode.Backend.ProvisionList,
            is CliMode.Backend.Download,
            is CliMode.Backend.Start,
            is CliMode.Backend.Stop,
            is CliMode.Backend.Provision,
            is CliMode.Unknown -> Unit
        }
    }

    companion object {
        @JvmStatic
        fun rejectedInvocations(): Stream<Arguments> = Stream.of(
            Arguments.of(arrayOf("backend", "--frobnicate"), "Unknown flag"),
            Arguments.of(arrayOf("backend", "download", "idea-community", "--xyz"), "Unknown flag"),
            Arguments.of(arrayOf("backend", "download", "idea-community", "extra"), "Unexpected extra argument"),
            Arguments.of(arrayOf("--home", "--debug", "backend"), "Unknown flag"),
            Arguments.of(arrayOf("backend", "stop", "idea-community", "stop-also-idea-ultimate"), "Unexpected extra argument"),
            Arguments.of(arrayOf("backend", "provision", "port-63342", "--method", "install"), "Unknown flag"),
            Arguments.of(arrayOf("project", "foo"), "Unexpected extra argument"),
            Arguments.of(arrayOf("project", "foo", "bar", "baz"), "Unexpected extra argument"),
            Arguments.of(arrayOf("backend", "--weasel"), "Unknown flag"),
            Arguments.of(arrayOf("--home", "/tmp/x", "backend"), "Unknown flag"),
            Arguments.of(arrayOf("--mcp"), "Unknown flag"),
            Arguments.of(arrayOf("mpc", "backend"), "Unexpected extra argument"),
        )

        @JvmStatic
        fun acceptedInvocations(): Stream<Arguments> = Stream.of(
            Arguments.of(emptyArray<String>(), CliMode.Help),
            Arguments.of(arrayOf("--help"), CliMode.Help),
            Arguments.of(arrayOf("-h"), CliMode.Help),
            Arguments.of(arrayOf("--debug"), CliMode.Help),
            Arguments.of(arrayOf("mpc"), CliMode.Mcp),
            Arguments.of(arrayOf("mpc", "--debug"), CliMode.Mcp),
            Arguments.of(arrayOf("--version"), CliMode.Version),
            Arguments.of(arrayOf("-v"), CliMode.Version),
            Arguments.of(arrayOf("backend", "--help"), CliMode.Help),
            Arguments.of(arrayOf("backend", "download", "--help"), CliMode.Help),
            Arguments.of(arrayOf("backend", "download", "idea-community", "--version"), CliMode.Version),
            Arguments.of(arrayOf("backend", "download", "idea-community", "--version", "--json"), CliMode.Version),
            Arguments.of(arrayOf("project", "--version"), CliMode.Version),
            Arguments.of(arrayOf("backend"), CliMode.Backend.Text),
            Arguments.of(arrayOf("backend", "--json"), CliMode.Backend.Json),
            Arguments.of(arrayOf("backend", "download"), CliMode.Backend.DownloadList(json = false)),
            Arguments.of(arrayOf("backend", "download", "--json"), CliMode.Backend.DownloadList(json = true)),
            Arguments.of(
                arrayOf("backend", "download", "idea-community"),
                CliMode.Backend.Download(id = "idea-community", versionOverride = null, json = false),
            ),
            Arguments.of(
                arrayOf("backend", "download", "idea-community", "--version", "2025.2.6.2"),
                CliMode.Backend.Download(id = "idea-community", versionOverride = "2025.2.6.2", json = false),
            ),
            Arguments.of(
                arrayOf("backend", "download", "idea-community", "--version", "2025.2.6.2", "--json"),
                CliMode.Backend.Download(id = "idea-community", versionOverride = "2025.2.6.2", json = true),
            ),
            Arguments.of(
                arrayOf("backend", "stop", "idea-community"),
                CliMode.Backend.Stop(id = "idea-community", versionOverride = null, json = false),
            ),
            Arguments.of(
                arrayOf("backend", "provision", "port-63342"),
                CliMode.Backend.Provision(id = "port-63342", json = false),
            ),
            Arguments.of(arrayOf("project"), CliMode.Project.Text),
            Arguments.of(arrayOf("project", "--json"), CliMode.Project.Json),
        )

        @JvmStatic
        fun fuzzedInvocations(): Stream<Arguments> {
            val alphabet = listOf(
                "--debug",
                "--json",
                "--home",
                "--version",
                "--mcp",
                "--help",
                "-h",
                "-v",
                "--frobnicate",
                "mpc",
                "backend",
                "project",
                "download",
                "start",
                "stop",
                "provision",
                "idea-community",
                "pid-1",
                "port-63342",
                "xyzzy",
            )
            val random = Random(0x5EED_2026)
            return (0 until 1_000).map {
                val size = random.nextInt(from = 0, until = 8)
                val args = Array(size) { alphabet[random.nextInt(alphabet.size)] }
                Arguments.of(args)
            }.stream()
        }
    }
}
