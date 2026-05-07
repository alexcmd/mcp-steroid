IDE: Find Duplicate Code
[IU]
Run the bundled `DuplicatedCode` inspection across the project and walk each clone cluster (main + duplicates) with typed access — no reflection, no `setAccessible(true)`.

# When to use this

Whenever an agent is asked to "find and refactor duplicate code", "extract a common helper for repeated logic", or "scan for clones". The `DuplicatedCode` inspection is the right tool: it is bundled in IntelliJ IDEA Ultimate, runs on Java, Kotlin, Python, Groovy, JavaScript, Ruby, and other supported languages via the same `DuplicateProblemDescriptor` payload, and the descriptor exposes a public `getTextClone()` getter so a script can enumerate every clone cluster typed.

> **Before submitting the recipe, ensure `steroid_execute_code` is callable in your session.** If your client lazy-loads MCP tool schemas (e.g. Claude Code's deferred tools), call `ToolSearch` (or the equivalent schema-load step for your client) for `mcp__mcp-steroid__steroid_execute_code` first. Without the schema loaded the call will fail with `InputValidationError` and you will lose a turn.

# Why direct typed access works (no reflection needed)

`steroid_execute_code` compiles your Kotlin against every loaded plugin's classloader files (`ScriptClassLoaderFactory.ideClasspath()` flattens `descriptor.pluginClassLoader.files` for every loaded plugin and its content modules). In IDEA Ultimate the duplicates-detector module (`intellij.platform.duplicatesDetector.jar`) is bundled, so `com.jetbrains.clones.DuplicateProblemDescriptor`, `com.jetbrains.clones.structures.TextClone`, and `com.jetbrains.clones.structures.TextFragment` are all on the script classpath. **Import them directly and cast.** Do **not** look the class up via `JavaPsiFacade` — that queries the *user project's* classpath, where the plugin classes never live, and a `null` result there is a known false negative that has historically led agents into reflection.

# The recipe (copy-paste)

Submit this as a single `steroid_execute_code` call. Adjust `targetExtensions` to whatever your project uses. Everything else is fully self-contained.

```kotlin[IU]
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.PairProcessor
import com.jetbrains.clones.DuplicateInspection
import com.jetbrains.clones.DuplicateProblemDescriptor
import com.jetbrains.clones.structures.TextClone
import com.jetbrains.clones.structures.TextFragment

data class CloneRange(val path: String, val startLine: Int, val endLine: Int)
data class CloneCluster(val main: CloneRange, val duplicates: List<CloneRange>)

fun TextFragment.toRange() = CloneRange(file.path, lines.first, lines.last)

// Adjust to your task
val targetExtensions = listOf("java", "kt", "py")
val maxClustersToReport = 20

val scope = GlobalSearchScope.projectScope(project)
val files = readAction {
    targetExtensions.flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }
}
println("Scanning ${files.size} file(s) for clones")

val wrapper = LocalInspectionToolWrapper(DuplicateInspection())
val clusters = mutableListOf<CloneCluster>()

for (vf in files) {
    val perFile = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction emptyList<CloneCluster>()
        val raw: List<ProblemDescriptor> = InspectionEngine.inspectEx(
            listOf(wrapper),
            psiFile,
            psiFile.textRange,
            psiFile.textRange,
            false,
            false,
            true,
            EmptyProgressIndicator(),
            PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true },
        ).values.flatten()

        raw.filterIsInstance<DuplicateProblemDescriptor>().map { dpd ->
            val tc: TextClone = dpd.textClone
            CloneCluster(main = tc.main.toRange(), duplicates = tc.duplicates.map { it.toRange() })
        }
    }
    clusters += perFile
    if (clusters.size >= maxClustersToReport) break
}

println("CLUSTERS_FOUND: ${clusters.size}")
clusters.forEachIndexed { i, c ->
    println("Cluster #${i + 1} (${c.duplicates.size + 1} occurrences)")
    println("  main ${c.main.path}:${c.main.startLine}-${c.main.endLine}")
    c.duplicates.forEach { d -> println("  dup  ${d.path}:${d.startLine}-${d.endLine}") }
}
```

# How it works

- `DuplicateInspection` is a `LocalInspectionTool` (`shortName = "DuplicatedCode"`, registered with `runForWholeFile="true"`). Per-file `checkFile` looks up a `DuplicateScopeExtension` for the file's language, queries the project-wide `HashFragmentIndex`, and emits a `DuplicateProblemDescriptor` for each clone cluster whose main fragment lives in the inspected file. So one pass over project sources visits every cluster exactly once (each cluster is anchored by its `main`).
- `DuplicateProblemDescriptor.getTextClone()` returns a `TextClone(main: TextFragment, duplicates: List<TextFragment>)`. `TextFragment` exposes `file: VirtualFile`, `range: TextRange`, and `lines: IntRange` — everything you need to render `path:startLine-endLine` and pull the snippet text from the document.
- Indexing must be ready. The script's bootstrap calls `waitForSmartMode()` automatically; if you trigger any reindexing in the same call, await `Observation.awaitConfiguration(project)` before the inspection runs.

# Language coverage (Java, Kotlin, Python, ...)

The same `DuplicatedCode` inspection works across every language that registers a `duplicateScope` extension:

| Language | Module providing the scope | IDE that bundles it |
|----------|---------------------------|---------------------|
| Java     | `intellij.java.duplicatesDetection`     | IDEA Ultimate |
| Kotlin   | `intellij.kotlin.duplicate.scope`       | IDEA Ultimate (Kotlin plugin) |
| Python   | `intellij.python.duplicatesDetection`   | PyCharm Pro / IDEA Ultimate with Python plugin |
| Groovy   | `intellij.groovy.duplicatesDetection`   | IDEA Ultimate |
| JS / TS  | `intellij.javascript.duplicatesDetection` | WebStorm / IDEA Ultimate |
| XML      | `intellij.xml.duplicatesDetection`      | IDEA Ultimate |
| Ruby     | `intellij.ruby.duplicatesDetection`     | RubyMine |

If `DuplicateScopeExtension.findDuplicateScope(fileType)` returns `null` for the file's language, `checkFile` returns no problems — the file is silently skipped. No language-specific code change to the script above is required; just adjust `targetExtensions` to whatever the project uses.

# When the direct import does not compile

If `steroid_execute_code` reports `unresolved reference: DuplicateProblemDescriptor`, the duplicates-detector module is not loaded into the running IDE (e.g. PyCharm Community without the Python duplicates plugin pulled in). The fix is **not** to switch to reflection — it is to declare the missing module so the IDE loads it and the typed import compiles:

1. List the loaded plugins with the "Find Plugin by ID" recipe in `mcp-steroid://skill/coding-with-intellij-patterns`. Confirm whether `com.intellij.modules.duplicatesDetector` is present.
2. If it is missing, pass it through the `required_plugins` parameter on your `steroid_execute_code` call so the IDE side handles loading. Do not install it programmatically.
3. Re-run the typed snippet above. The recipe is the same in every IDE that has the module loaded — there is no reflection branch.

> **Reflection is for exploration, not for the recipe you ship.** If you reached for `Class.getDeclaredField("myTextClone")` + `setAccessible(true)` to extract the clone pair, stop — that path is brittle (private-field renames in the next IDE release silently break the script) and unnecessary. The public `getTextClone()` getter and the typed `import` above are the right answer. See the reflection-policy note in `mcp-steroid://skill/coding-with-intellij`.

# See also

- [Inspection + Quick Fix](mcp-steroid://ide/inspect-and-fix) — single-file inspection + quick fix pattern this recipe extends
- [Inspection Summary](mcp-steroid://ide/inspection-summary) — list inspections enabled in the project
- [Apply Patch](mcp-steroid://ide/apply-patch) — atomic multi-site edits, the natural follow-up after locating a clone cluster
- [Common Patterns](mcp-steroid://skill/coding-with-intellij-patterns) — cross-classloader fallback and find-by-extension recipes used above
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) — top-level skill index
