/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class ManagedBackendGuiIntegrationTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `devrig downloads starts and stops IDEA Community inside a GUI container`() = runWithCloseableStack { lifetime ->
        val container = IntelliJContainer.create(
            lifetime = lifetime,
            dockerFileBase = "managed-backend-host",
            consoleTitle = "managed-backend",
            aiMode = AiMode.NONE,
            startIde = false,
            mountSshAgent = false,
            repoCacheDir = null,
        )
        val devrig = container.deployDevrigLauncher()

        val download = container.execAndAssert(
            description = "download IDEA Community managed backend",
            timeoutSeconds = 25 * 60L,
            script = """
                set -euo pipefail
                rm -rf /tmp/mcp-home
                mkdir -p /tmp/mcp-home
                "$devrig" --home /tmp/mcp-home backend download idea-community
            """.trimIndent(),
        )
        assertTrue(download.stdout.contains("id: idea-community-"), download.stdout)

        val id = container.execAndAssert(
            description = "resolve managed backend id",
            script = """
                set -euo pipefail
                basename "${'$'}(find /tmp/mcp-home/backends -mindepth 1 -maxdepth 1 -type d -name 'idea-community-*' | sort | head -1)"
            """.trimIndent(),
        ).stdout.trim()
        assertTrue(Regex("""idea-community-\d+\.\d+.*""").matches(id), id)

        container.execAndAssert(
            description = "assert managed backend files",
            script = """
                set -euo pipefail
                backend_dir="/tmp/mcp-home/backends/$id"
                bundle="${'$'}(find "${'$'}backend_dir" -mindepth 1 -maxdepth 1 -type d | head -1)"
                test -n "${'$'}bundle"
                test -f "${'$'}backend_dir/backend.json"
                jq -e --arg id "$id" '.id == ${'$'}id and .productKey == "idea-community" and (.launcherPath | length > 0)' "${'$'}backend_dir/backend.json"
                test -f "${'$'}bundle/product-info.json" -o -f "${'$'}bundle/Contents/Resources/product-info.json"
                vmoptions="${'$'}backend_dir/${'$'}(basename "${'$'}bundle").vmoptions"
                test -f "${'$'}vmoptions"
                grep -F -- "-Didea.config.path=/tmp/mcp-home/caches/$id/config" "${'$'}vmoptions"
                grep -F -- "-Didea.system.path=/tmp/mcp-home/caches/$id/system" "${'$'}vmoptions"
                grep -F -- "-Didea.log.path=/tmp/mcp-home/caches/$id/logs" "${'$'}vmoptions"
                grep -F -- "-Didea.plugins.path=/tmp/mcp-home/caches/$id/plugins" "${'$'}vmoptions"
            """.trimIndent(),
        )

        val start = container.execAndAssert(
            description = "start IDEA Community managed backend",
            timeoutSeconds = 5 * 60L,
            script = """
                set -euo pipefail
                "$devrig" --home /tmp/mcp-home backend start idea-community
            """.trimIndent(),
        )
        val pid = Regex("""pid: (\d+)""").find(start.stdout)?.groupValues?.get(1)
            ?: error("No pid in backend start output: ${start.stdout}")

        container.waitForIntelliJBuiltInHttpServer(timeoutSeconds = 180)

        container.execAndAssert(
            description = "assert managed backend cache paths",
            script = """
                set -euo pipefail
                test -d "/tmp/mcp-home/caches/$id/config"
                test -d "/tmp/mcp-home/caches/$id/system"
                test -d "/tmp/mcp-home/caches/$id/logs"
                test -d "/tmp/mcp-home/caches/$id/plugins"
                test "${'$'}(find "/tmp/mcp-home/caches/$id/logs" -type f | wc -l)" -gt 0
            """.trimIndent(),
        )

        container.execAndAssert(
            description = "stop IDEA Community managed backend",
            timeoutSeconds = 120,
            script = """
                set -euo pipefail
                "$devrig" --home /tmp/mcp-home backend stop idea-community
                test ! -f "/tmp/mcp-home/state/$id.pid"
                ! kill -0 $pid 2>/dev/null
            """.trimIndent(),
        )
    }
}
