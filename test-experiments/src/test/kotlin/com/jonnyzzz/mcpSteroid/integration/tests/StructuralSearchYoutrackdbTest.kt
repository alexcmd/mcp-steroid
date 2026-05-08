/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Integration test for the **structural-search skill articles** running against
 * a real-world Maven multi-module Java codebase: https://github.com/JetBrains/youtrackdb
 *
 * The point of this test (vs. [StructuralSearchPromptTest] in `:test-integration:`)
 * is to validate that the skill articles' recipes work on a non-handpicked
 * codebase — the `Matcher` over a `GlobalSearchScope.projectScope(project)` of a
 * real multi-module Maven project, with a real Java profile loaded, with a real
 * indexer warming up.
 *
 * The agent is asked to:
 *
 *   1. Enumerate every registered SSR profile and confirm the Java profile is
 *      present in this Maven project.
 *   2. Run an SSR audit that finds every callsite where the resolved expression
 *      type is `java.util.Optional<T>` and the called method is `get` — using
 *      the `:[exprtype(...)]` macro from
 *      `mcp-steroid://skill/structural-search-syntax`. The exact count is not
 *      asserted (youtrackdb's content evolves with each upstream commit); we
 *      assert the recipe ran end-to-end and produced a non-negative integer.
 *   3. Enumerate the Java profile's predefined templates and report a count
 *      ≥ 50 (the bundled Java SSR profile ships ~98 templates in current IntelliJ).
 *
 * Three signals are checked, regardless of how the agent solved (1)–(3):
 *
 *   A. The agent fetched at least one `mcp-steroid://skill/structural-search*`
 *      article — proving the skill articles drove the recipe.
 *   B. No `steroid_execute_code` body uses the broken pattern from
 *      `structural-search-api-recipe.md` §"What NOT to do" (calling
 *      `transformCriteria(...)` twice on the same options).
 *   C. The required marker outputs are present and well-formed.
 */
class StructuralSearchYoutrackdbTest {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `ssr audit on youtrackdb claude`() = ssrAuditOnYoutrackdb(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `ssr audit on youtrackdb codex`() = ssrAuditOnYoutrackdb(session.aiAgents.codex)

    private fun ssrAuditOnYoutrackdb(agent: AiAgentSession) {
        val console = session.console
        console.writeStep(1, "[ssr-youtrackdb] ${agent.displayName}: running prompt")

        val result = agent.runPrompt(SSR_AUDIT_PROMPT, timeoutSeconds = 1500).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        val allMarkersPresent = listOf("SSR_PROFILES", "OPTIONAL_GET_MATCHES", "JAVA_PREDEFINED_COUNT")
            .all { hasAnyMarkerLine(output, it) }
        if (result.exitCode != 0 && !allMarkersPresent) {
            console.writeError("[ssr-youtrackdb] agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "ssr-youtrackdb (${agent.displayName})")
        }
        console.writeInfo("[ssr-youtrackdb] agent exited with code ${result.exitCode ?: "?"}")

        // 1. steroid_execute_code evidence
        console.writeInfo("[ssr-youtrackdb] checking execute_code evidence")
        assertUsedExecuteCodeEvidence(combined)

        // 2. fetched at least one structural-search skill article
        console.writeInfo("[ssr-youtrackdb] checking skill-article fetches")
        val fetched = readAgentFetchedUris(agent)
        val ssrFetches = fetched.filter { it.contains("/structural-search") }
        check(ssrFetches.isNotEmpty()) {
            buildString {
                appendLine("[ssr-youtrackdb/${agent.displayName}] agent did not fetch any structural-search skill article.")
                appendLine("Fetched URIs: $fetched")
                appendLine("Output:")
                appendLine(combined)
            }
        }
        console.writeSuccess("[ssr-youtrackdb] fetched: $ssrFetches")

        // 3. no broken-recipe anti-pattern
        console.writeInfo("[ssr-youtrackdb] checking for broken-recipe anti-patterns")
        val execBodies = readAgentExecCodeBodies(agent)
        check(execBodies.isNotEmpty()) {
            "[ssr-youtrackdb/${agent.displayName}] no steroid_execute_code calls captured."
        }
        val brokenPatterns = listOf(
            Regex("""StringToConstraintsTransformer\s*\.\s*transformCriteria\s*\(.*\)\s*[\s\S]*?StringToConstraintsTransformer\s*\.\s*transformCriteria\s*\("""),
            Regex("""\breplaceAll\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\.\s*matches\b"""),
        )
        val offending = execBodies.mapIndexedNotNull { i, body ->
            val hits = brokenPatterns.mapIndexedNotNull { idx, p -> if (p.containsMatchIn(body)) idx else null }
            if (hits.isEmpty()) null else (i + 1) to hits
        }
        check(offending.isEmpty()) {
            "[ssr-youtrackdb/${agent.displayName}] broken SSR recipe pattern in exec_code: $offending"
        }
        console.writeSuccess("[ssr-youtrackdb] no broken patterns in any of ${execBodies.size} exec_code submission(s)")

        // 4. SSR_PROFILES marker — should be >= 5 in the IDE this test runs in.
        console.writeInfo("[ssr-youtrackdb] SSR_PROFILES marker")
        val nProfiles = findMarkerValue(output, "SSR_PROFILES")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
        check(nProfiles >= 5) {
            "[ssr-youtrackdb/${agent.displayName}] SSR_PROFILES expected >= 5; got $nProfiles.\nOutput:\n$combined"
        }
        console.writeSuccess("[ssr-youtrackdb] SSR_PROFILES=$nProfiles")

        val javaFound = findMarkerValue(output, "JAVA_PROFILE_FOUND") ?: ""
        check(javaFound.contains("yes", ignoreCase = true)) {
            "[ssr-youtrackdb/${agent.displayName}] JAVA_PROFILE_FOUND must be 'yes' on a Maven Java project; got '$javaFound'."
        }
        console.writeSuccess("[ssr-youtrackdb] JAVA_PROFILE_FOUND=$javaFound")

        // 5. OPTIONAL_GET_MATCHES — accept any non-negative integer; youtrackdb's content evolves.
        console.writeInfo("[ssr-youtrackdb] OPTIONAL_GET_MATCHES marker")
        val nOptGet = findMarkerValue(output, "OPTIONAL_GET_MATCHES")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
        check(nOptGet >= 0) {
            "[ssr-youtrackdb/${agent.displayName}] OPTIONAL_GET_MATCHES must be a non-negative integer; got '$nOptGet' (raw: '${findMarkerValue(output, "OPTIONAL_GET_MATCHES")}').\nOutput:\n$combined"
        }
        console.writeSuccess("[ssr-youtrackdb] OPTIONAL_GET_MATCHES=$nOptGet (no exact-count assertion against an evolving real-world repo)")

        // 6. JAVA_PREDEFINED_COUNT — current IntelliJ Java profile ships ~98 templates; allow margin.
        console.writeInfo("[ssr-youtrackdb] JAVA_PREDEFINED_COUNT marker")
        val nTpl = findMarkerValue(output, "JAVA_PREDEFINED_COUNT")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
        check(nTpl >= 50) {
            "[ssr-youtrackdb/${agent.displayName}] JAVA_PREDEFINED_COUNT expected >= 50 (current IntelliJ ships ~98); got $nTpl.\nOutput:\n$combined"
        }
        console.writeSuccess("[ssr-youtrackdb] JAVA_PREDEFINED_COUNT=$nTpl")

        // 7. IMPROVEMENTS reflection.
        val improvements = extractImprovementsBlock(output)
        if (improvements != null && improvements.isNotBlank()) {
            val savedTo = saveImprovements(agent.displayName, improvements)
            console.writeSuccess("[ssr-youtrackdb] improvements -> $savedTo")
        } else {
            console.writeInfo("[ssr-youtrackdb] no IMPROVEMENTS block returned (optional)")
        }

        console.writeHeader("[ssr-youtrackdb/${agent.displayName}] PASSED")
        println("[TEST/ssr-youtrackdb/${agent.displayName}] passed (profiles=$nProfiles optionalGet=$nOptGet predefined=$nTpl)")
    }

    // -------- NDJSON readers (mirror StructuralSearchPromptTest in :test-integration:) --------

    private fun readAgentFetchedUris(agent: AiAgentSession): List<String> {
        val ndjson = locateLatestRawNdjson(agent) ?: return emptyList()
        val uris = mutableListOf<String>()
        Files.newBufferedReader(ndjson).useLines { lines ->
            for (raw in lines) {
                if ('{' !in raw) continue
                val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                val content = obj["message"]?.jsonObject?.get("content")?.let { runCatching { it.jsonArray }.getOrNull() }
                if (content != null) {
                    for (entry in content) {
                        val item = runCatching { entry.jsonObject }.getOrNull() ?: continue
                        if (item["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!name.endsWith("steroid_fetch_resource")) continue
                        val uri = item["input"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
                        if (!uri.isNullOrEmpty()) uris += uri
                    }
                }
                val item = obj["item"]?.let { runCatching { it.jsonObject }.getOrNull() }
                if (item != null && item["type"]?.jsonPrimitive?.contentOrNull == "mcp_tool_call") {
                    val tool = item["tool"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull
                    if (tool == "steroid_fetch_resource" || tool?.endsWith("__steroid_fetch_resource") == true) {
                        val args: JsonObject? = item["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: item["input"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val uri = args?.get("uri")?.jsonPrimitive?.contentOrNull
                        if (!uri.isNullOrEmpty()) uris += uri
                    }
                }
            }
        }
        return uris
    }

    private fun readAgentExecCodeBodies(agent: AiAgentSession): List<String> {
        val ndjson = locateLatestRawNdjson(agent) ?: return emptyList()
        val codes = mutableListOf<String>()
        Files.newBufferedReader(ndjson).useLines { lines ->
            for (raw in lines) {
                if ('{' !in raw) continue
                val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                val content = obj["message"]?.jsonObject?.get("content")?.let { runCatching { it.jsonArray }.getOrNull() }
                if (content != null) {
                    for (entry in content) {
                        val item = runCatching { entry.jsonObject }.getOrNull() ?: continue
                        if (item["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!name.endsWith("steroid_execute_code")) continue
                        val code = item["input"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                        if (!code.isNullOrEmpty()) codes += code
                    }
                }
                val item = obj["item"]?.let { runCatching { it.jsonObject }.getOrNull() }
                if (item != null && item["type"]?.jsonPrimitive?.contentOrNull == "mcp_tool_call") {
                    val tool = item["tool"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull
                    if (tool == "steroid_execute_code" || tool?.endsWith("__steroid_execute_code") == true) {
                        val args: JsonObject? = item["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: item["input"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val code = args?.get("code")?.jsonPrimitive?.contentOrNull
                        if (!code.isNullOrEmpty()) codes += code
                    }
                }
            }
        }
        return codes
    }

    private fun locateLatestRawNdjson(agent: AiAgentSession): Path? {
        val logsRoot = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-experiments/build/test-logs/test")
        if (!Files.isDirectory(logsRoot)) return null
        val agentSlug = agent.displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val pattern = Regex("""agent-$agentSlug-\d+-raw\.ndjson""")
        return Files.walk(logsRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }.toList()
        }.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
    }

    private fun extractImprovementsBlock(output: String): String? {
        val regex = Regex(
            pattern = """<<<\s*IMPROVEMENTS\s*>>>\s*\n([\s\S]*?)\n\s*<<<\s*END_IMPROVEMENTS\s*>>>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        val candidates = regex.findAll(output).toList()
        val placeholderHint = "your reflection"
        val preferred = candidates.lastOrNull { match ->
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            content.isNotEmpty() && !content.startsWith("(") && placeholderHint !in content.lowercase()
        } ?: candidates.lastOrNull()
        return preferred?.groupValues?.getOrNull(1)?.trim()
    }

    private fun saveImprovements(agentName: String, content: String): Path {
        val safeAgent = agentName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-")
        val dir = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-experiments/build/improvements")
        dir.createDirectories()
        val file = dir.resolve("IMPROVEMENTS-ssr-youtrackdb-$safeAgent.md")
        val header = buildString {
            appendLine("# ssr-youtrackdb: agent reflection ($agentName)")
            appendLine()
            appendLine("Generated by StructuralSearchYoutrackdbTest on ${java.time.Instant.now()}.")
            appendLine("Constraint enforced by the prompt: prompt-only tweaks; no new MCP tools / API methods.")
            appendLine()
            appendLine("---")
            appendLine()
        }
        file.writeText(header + content + "\n")
        return file
    }

    companion object {

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "ssr / youtrackdb",
                project = IntelliJProject.YouTrackDbProject,
            ).waitForProjectReady(buildSystem = BuildSystem.MAVEN)
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }

        val SSR_AUDIT_PROMPT: String = """
# SSR audit on a real Maven Java codebase (youtrackdb)

The IntelliJ IDE has loaded https://github.com/JetBrains/youtrackdb (Java, Apache
Maven, multi-module). Use IntelliJ Structural Search and Replace to do the
following audits and report the results.

## Read first

The recipe lives in these skill articles — fetch them via `steroid_fetch_resource`
before writing any code:

- `mcp-steroid://skill/structural-search` — overview
- `mcp-steroid://skill/structural-search-api-recipe` — canonical Kotlin recipe
- `mcp-steroid://skill/structural-search-syntax` — template language
- `mcp-steroid://skill/structural-search-coverage` — language profile matrix

## Task

In a single (or small set of) `steroid_execute_code` invocation(s), do all three:

1. **Profile registry**. Enumerate every registered SSR profile and report:
       SSR_PROFILES: <integer count of registered profiles>
       JAVA_PROFILE_FOUND: <yes if a JavaStructuralSearchProfile is registered, else no>

2. **Optional.get() audit**. Find every callsite where the resolved expression
   type is `java.util.Optional<T>` and the called method is `get`, scoped to
   `GlobalSearchScope.projectScope(project)`. Use the apostrophe-form pattern
   with a `:[exprtype(...)]` constraint per `structural-search-syntax`. Report:
       OPTIONAL_GET_MATCHES: <integer count of matches; 0 is acceptable>

3. **Predefined-templates count**. Resolve the Java profile via
   `StructuralSearchUtil.getProfileByFileType(JavaFileType.INSTANCE)` and count
   `profile.predefinedTemplates.size`. Report:
       JAVA_PREDEFINED_COUNT: <integer>

## Hard rules

These come from `mcp-steroid://skill/structural-search-api-recipe`:

- Always call `Matcher.validate(project, options)` BEFORE constructing the `Matcher`.
- Do NOT wrap `Matcher.findMatches(...)` in an outer `readAction { }`.
- Do NOT call `StringToConstraintsTransformer.transformCriteria(...)` a second
  time on the same `MatchOptions` — it overwrites the search pattern.
- Use the apostrophe form (`'_x`, `'_x:[exprtype(...)]`) for the Optional.get audit.

## Optional reflection

If anything in the skill articles was unclear or could be improved, share notes
between these delimiters (prompt-only tweaks; no new MCP tools / API methods):

<<<IMPROVEMENTS>>>
(your reflection)
<<<END_IMPROVEMENTS>>>
""".trimIndent()
    }
}
