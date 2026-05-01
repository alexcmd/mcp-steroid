Execute Code: Maven Patterns

Running Maven builds and tests via IntelliJ Maven APIs instead of ProcessBuilder.

# Execute Code: Maven Patterns

## Agent: Run One Maven Test Method (two-call pattern)

When an agent task asks for "run one fast test through Maven" — pick a plain JUnit method, then run it through IntelliJ's Maven integration. **Do NOT shell out to `./mvnw` or `mvn` via the `Bash` tool**, and do NOT use `ProcessBuilder("./mvnw")` inside `steroid_execute_code`. Both bypass the IDE entirely and defeat the value of MCP Steroid.

> ⚠️ **Single-call pattern does NOT work for Maven test runs.** The MCP HTTP transport (claude-code's CLI in particular) cancels in-flight tool calls after ~60 seconds. Maven setup + a JUnit test on a fresh checkout often takes 30–120s. A single script that calls `runConfiguration` and then `await`s the SMT listener will be cancelled mid-run by the client, even though the IDE-side script timeout is much larger. **Use the two-call pattern below: launch in call 1, poll in call 2+.**

> ⚠️ **The reliable evidence is the *Run console output*, not `SMTRunnerEventsListener`.** Maven's surefire writes line-based text — whether IntelliJ promotes it to SMT events depends on the surefire version and the run-config wrapping. The recipe below skips SMT entirely and reads what the IDE's Run tool window already shows: `BUILD SUCCESS` / `BUILD FAILURE`, `Tests run: N, Failures: M, Errors: K`, and the surefire summary. That output is captured via a `ProcessListener` attached at run-start through `ProgramRunner.Callback.processStarted(descriptor)`.

### Call 1 — launch the test, attach output capture, return immediately

This script kicks off the Maven run with a `ProgramRunner.Callback`. As soon as the run starts (which happens on EDT inside `runConfiguration`), the callback fires with the `RunContentDescriptor` and we attach a `ProcessListener` that captures every `event.text` line plus the final exit code. State is parked in `project.userData` so the polling script can read it.

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred

val exitKey = Key.create<CompletableDeferred<Int>>("mcp.steroid.maven.exit.code")
val outputKey = Key.create<MutableList<String>>("mcp.steroid.maven.output.lines")
val labelKey = Key.create<String>("mcp.steroid.maven.test.label")

if (project.getUserData(exitKey)?.isActive == true) {
    println("Maven run already in flight — call the polling script instead.")
} else {
    val exitDeferred = CompletableDeferred<Int>()
    val outputLines: MutableList<String> =
        java.util.concurrent.CopyOnWriteArrayList<String>() as MutableList<String>
    project.putUserData(exitKey, exitDeferred)
    project.putUserData(outputKey, outputLines)
    project.putUserData(labelKey, "MyServiceTest#shouldReturnFeature")

    val callback = ProgramRunner.Callback { descriptor: RunContentDescriptor ->
        descriptor.processHandler?.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { outputLines.add(event.text) }
            override fun processTerminated(event: ProcessEvent) { exitDeferred.complete(event.exitCode) }
        })
    }

    // Multi-module reactor: target a single submodule via `-pl <module>` in the goal list,
    // the working dir stays at the reactor root.
    MavenRunConfigurationType.runConfiguration(project,
        MavenRunnerParameters(true, project.basePath!!, null,
            listOf(
                "test",
                "-pl", "core",
                "-Dtest=com.example.MyServiceTest#shouldReturnFeature",
                "-DskipITs",
                "-Dspotless.check.skip=true",
            ),
            emptyList()),
        null, null, callback)

    println("Maven test launched: MyServiceTest#shouldReturnFeature")
    println("EXECUTION_VIA: MavenRunConfigurationType")
    println("Call the polling script next; expected total runtime 30–120s.")
}
```

### Call 2 — poll for completion + parse run output (re-issue every 20–30s until done)

This script awaits the exit-code deferred with a **short** `withTimeoutOrNull(30.seconds)` so the script itself returns well under the MCP HTTP transport's ~60-second client-side cancel. When the process terminates, it parses the captured output for `BUILD SUCCESS` / `BUILD FAILURE` / `Tests run:` and emits the structured result. The agent re-issues this script until it sees `TEST_RESULT:`.

```kotlin[IU]
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

val exitKey = Key.create<CompletableDeferred<Int>>("mcp.steroid.maven.exit.code")
val outputKey = Key.create<MutableList<String>>("mcp.steroid.maven.output.lines")
val labelKey = Key.create<String>("mcp.steroid.maven.test.label")

val exitDeferred = project.getUserData(exitKey)
val outputLines = project.getUserData(outputKey)
if (exitDeferred == null || outputLines == null) {
    println("No Maven run in flight. Run the launch script first.")
} else {
    val exitCode = withTimeoutOrNull(30.seconds) { exitDeferred.await() }
    if (exitCode == null) {
        // Still running — print the most recent line so the agent sees progress.
        val live = outputLines.lastOrNull()?.trimEnd() ?: "(no output yet)"
        println("Maven run still in flight after 30s. Latest console line: $live")
        println("Call this script again to keep polling.")
    } else {
        // Run finished — parse the surefire tail to extract pass/fail.
        val joined = outputLines.joinToString("")
        val tail = joined.lines().takeLast(40).joinToString("\n")
        val testsRunLine = Regex("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)")
            .find(joined)
        val buildSuccess = joined.contains("BUILD SUCCESS")
        val passed = buildSuccess && testsRunLine?.let { it.groupValues[2] == "0" && it.groupValues[3] == "0" } == true

        val label = project.getUserData(labelKey) ?: "<unknown>"
        println("EXECUTION_VIA: MavenRunConfigurationType")
        println("TEST_LABEL: $label")
        println("PROCESS_EXIT_CODE: $exitCode")
        if (testsRunLine != null) println("TESTS_RUN_LINE: ${testsRunLine.value}")
        println("TEST_RESULT: ${if (passed) "PASSED" else "FAILED"}")
        println("--- Run console tail (last 40 lines) ---")
        println(tail)

        project.putUserData(exitKey, null)
        project.putUserData(outputKey, null)
        project.putUserData(labelKey, null)
    }
}
```

**Why "read the run console" instead of `SMTRunnerEventsListener`:**
- The Run tool window's console **always** has the surefire summary — `BUILD SUCCESS|FAILURE`, `Tests run: N, Failures: M, Errors: K, Skipped: J` — for any green-or-red Maven test run. It is the same evidence a human looks at.
- SMT events are best-effort: they fire only when the IDE's Java program runner recognizes surefire's output format and promotes it. With `MavenRunConfigurationType.runConfiguration`, that promotion is *not* guaranteed (observed empty `SMTestProxy.SMRootTestProxy.allTests` even on `BUILD SUCCESS`).
- The `ProgramRunner.Callback.processStarted(descriptor)` hook fires synchronously inside `runConfiguration`'s EDT block — by the time `runConfiguration` returns, the `ProcessListener` is already attached and starts capturing the very first `[INFO] Scanning for projects...` line.
- `project.userData` survives across `steroid_execute_code` calls; the `ProcessHandler` is bound to the run descriptor, which lives until the user closes the Run tab.

**`-am` (also-make) is BANNED.** It walks the upstream graph and frequently OOM-kills the container. Pin to the one submodule with `-pl <module>` and accept that one extra `install` round-trip below if a sibling artifact is missing.

### Sibling-install fallback (when the targeted module references an in-reactor sibling not yet in `~/.m2`)

If the polling script reports `TEST_RESULT: FAILED` and the failure mentions `The POM for io.example:sibling:jar:X is missing` or `Could not resolve artifact ...:sibling:jar:X`, install ONLY that sibling — through IntelliJ — using the same two-call shape:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred

val installKey = Key.create<CompletableDeferred<Boolean>>("mcp.steroid.maven.install.done")
val installDeferred = CompletableDeferred<Boolean>()
project.putUserData(installKey, installDeferred)

val params = MavenRunnerParameters(
    /* isPomExecution = */ true,
    /* workingDirPath = */ project.basePath!!,
    /* pomFileName    = */ null,
    /* goals          = */ listOf("install", "-pl", "test-commons", "-DskipTests"),
    /* enabledProfiles = */ emptyList()
)
val runner = MavenRunner.getInstance(project)
val settings: MavenRunnerSettings = runner.settings.clone()
settings.mavenProperties["spotless.check.skip"] = "true"
runner.run(params, settings) { installDeferred.complete(true) }
println("Sibling install launched: test-commons")
println("Call the polling script (substituting installKey) next.")
```

The polling script for the install is identical in shape to call 2 above — just swap the key. Stop after at most TWO sibling-install rounds; if more are needed, escalate.

**Why `MavenRunner.run` (not `MavenRunConfigurationType.runConfiguration`) for the install:** the install goal has no test framework, so `SMTRunnerEventsListener` never fires — the simpler `MavenRunner.run` callback (which fires only on exit-code 0) is the right primitive there.

---

## Primary: MavenRunner + MavenRunnerParameters

Use `MavenRunner` for all Maven goal execution — it runs inside the IDE JVM, reuses IntelliJ's Maven installation, and avoids spawning a separate process:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val done = CompletableDeferred<Boolean>()
val params = MavenRunnerParameters(
    /* isPomExecution    = */ true,
    /* workingDirPath   = */ project.basePath!!,
    /* pomFileName      = */ null,              // null → use default pom.xml
    /* goals            = */ listOf("package"),
    /* enabledProfiles  = */ emptyList()
)
val runner = MavenRunner.getInstance(project)
val settings: MavenRunnerSettings = runner.settings.clone()
settings.mavenProperties["spotless.check.skip"] = "true"
runner.run(params, settings) { done.complete(true) }
// Note: callback fires only on exit code 0. If Maven fails, the deferred never completes.
// For pass/fail semantics use MavenRunConfigurationType + SMTRunnerEventsListener (see coding-with-intellij-spring.md).
val ok = withTimeout(5.minutes) { done.await() }
println("Maven goal completed: $ok")
```

**Import paths:**
```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunner             // project service
import org.jetbrains.idea.maven.execution.MavenRunnerParameters   // goal + working dir
import org.jetbrains.idea.maven.execution.MavenRunnerSettings     // properties, skip flags
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType  // lower-level static entry
import org.jetbrains.idea.maven.project.MavenProjectsManager      // sync/reload
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec            // full/incremental sync spec
```

---

## Structured Pass/Fail for Test Runs — SMTRunnerEventsListener

For Maven test execution with explicit pass/fail result, use `MavenRunConfigurationType.runConfiguration` + `SMTRunnerEventsListener`. See **`mcp-steroid://skill/coding-with-intellij-spring`** for the complete pattern. Summary:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val mavenResult = CompletableDeferred<Boolean>()
project.messageBus.connect().subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) { mavenResult.complete(testsRoot.isPassed) }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})
MavenRunConfigurationType.runConfiguration(project,
    MavenRunnerParameters(true, project.basePath!!, null,
        listOf("test", "-Dtest=MyServiceTest", "-Dspotless.check.skip=true"), emptyList()),
    null, null) {}
val passed = withTimeout(5.minutes) { mavenResult.await() }
println("Maven test: passed=$passed")
```

---

## Sync after pom.xml Change

After modifying `pom.xml`, trigger a full Maven re-import and wait for completion before compiling or running tests:

```kotlin[IU]
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec  // package: buildtool (NOT project) — IU-253+
import com.intellij.platform.backend.observation.Observation

val manager = MavenProjectsManager.getInstance(project)
manager.scheduleUpdateAllMavenProjects(
    MavenSyncSpec.full("post-pom-edit", explicit = true)
)
Observation.awaitConfiguration(project)  // suspends until sync + indexing fully complete
println("Maven sync complete — new deps resolved, safe to compile/inspect")
```

**Key notes:**
- `MavenSyncSpec.full()` forces re-reading all POM files (use after external edits)
- `MavenSyncSpec.incremental()` only syncs changed files (use for minor updates)
- `explicit = true` marks the sync as user-initiated (affects IDE progress indicators)
- `Observation.awaitConfiguration(project)` is required — otherwise `runInspectionsDirectly` shows false "cannot resolve symbol" errors from undownloaded deps
- ⚠️ `MavenSyncSpec` is in package `org.jetbrains.idea.maven.buildtool` — NOT `.project`

**Partial sync — only specific pom files:**
```kotlin[IU]
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec

val pomVf = findProjectFile("pom.xml")!!
MavenProjectsManager.getInstance(project).scheduleUpdateMavenProjects(
    MavenSyncSpec.full("update specific pom"),
    filesToUpdate = listOf(pomVf),
    filesToDelete = emptyList()
)
```

---

## Run Specific Test Class

Pass `-Dtest=ClassName` via `MavenRunnerSettings.mavenProperties`:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val done = CompletableDeferred<Boolean>()
val params = MavenRunnerParameters(
    true,
    project.basePath!!,
    null,
    listOf("test"),
    emptyList()
)
val runner = MavenRunner.getInstance(project)
val settings: MavenRunnerSettings = runner.settings.clone()
// Single test class:
settings.mavenProperties["test"] = "FeatureServiceTest"
// OR single method:  settings.mavenProperties["test"] = "FeatureServiceTest#shouldReturnFeature"
settings.mavenProperties["spotless.check.skip"] = "true"
runner.run(params, settings) { done.complete(true) }
val ok = withTimeout(5.minutes) { done.await() }
println("Test run completed: $ok")
// ⚠️ callback fires only on exit 0. For test failure details, use SMTRunnerEventsListener pattern above.
```

---

## ⚠️ ProcessBuilder("./mvnw") — LAST RESORT ONLY

Use `ProcessBuilder("./mvnw")` **only** when ALL of the following are true:
1. `pom.xml` was just modified in this session, AND
2. Maven sync was already triggered (`scheduleUpdateAllMavenProjects` + `awaitConfiguration`), AND
3. `MavenRunConfigurationType.runConfiguration()` with `dialog_killer: true` has already timed out (>2 min)

In all other cases, **use `MavenRunner` or `MavenRunConfigurationType`**.

```kotlin[IU]
// ⚠️ ONLY when Maven sync unavailable — e.g. immediately after pom.xml edit before sync
// ⚠️ ./mvnw (wrapper) not 'mvn' — system mvn is typically not installed
// ⚠️ Spring Boot test output can exceed 200k chars — NEVER print untruncated output
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | lines: ${lines.size}")
// ⚠️ Always capture BOTH ends: Spring context errors appear at START; BUILD FAILURE at END
println("--- First 30 lines (Spring context errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

---

## JAVA_HOME / Multi-JDK Selection

### JDK Selection Algorithm (do this BEFORE your first Maven/Gradle command)

When multiple JDKs are available, select the right one immediately — don't trial-and-error:

1. **Read the project's Java version** from `pom.xml` (`<java.version>`, `<maven.compiler.source>`, `<maven.compiler.target>`, or `<release>`) or `build.gradle` (`sourceCompatibility`, `toolchain`).
2. **List available JDKs**: `ls /usr/lib/jvm/ 2>/dev/null` (Linux) or `ls /Library/Java/JavaVirtualMachines/ 2>/dev/null` (macOS).
3. **Pick the LOWEST available JDK version >= the project's requirement.** Example: project needs Java 24 → available are temurin-8, 11, 17, 21, 25 → pick **temurin-25** (only one >= 24). Never start with lower JDK versions and work upward.
4. **Set JAVA_HOME in your FIRST Bash command** — before any Maven/Gradle invocation:
```
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64
export PATH=$JAVA_HOME/bin:$PATH
java -version   # confirm
```

> **If the first `steroid_execute_code` call already printed available JDKs** (e.g., `JDKs: temurin-8-jdk-arm64, temurin-21-jdk-arm64, temurin-17-jdk-arm64, temurin-11-jdk-arm64, temurin-25-jdk-arm64`), use that list directly — do NOT run `ls /usr/lib/jvm/` again.

### JDK Troubleshooting

When Maven fails with `Fatal error compiling`, `cannot find symbol`, `POM not found for parent`,
or `Unsupported class file major version`, the root cause is often a JDK version mismatch.
Fix it BEFORE making any other changes.

**Run Maven with explicit JAVA_HOME:**
```
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 mvn -pl ts-common install -DskipTests
```

**When to do this first:**
- Multi-module project with a `common` or `parent` module that fails to install
- Error message mentions `Unsupported class file major version` (version mismatch)
- Maven cannot resolve `../pom.xml` or parent POM in a fresh container
- Project's `pom.xml` declares `<java.version>17</java.version>` but `java -version` shows 21+

**Do NOT use steroid_execute_code for Maven JAVA_HOME issues.** The IDE cannot fix JDK
mismatches — only setting `JAVA_HOME` in the shell environment fixes it. Use `Bash` tool.

---

## Fix: "Project JDK is not defined" Banner (IntelliJ IDEA)

When IntelliJ shows a yellow "Project JDK is not defined" notification in the editor,
Maven builds and inspections will fail. Fix it immediately before doing any other work.

**For Maven/Gradle projects**: the correct JDK is the one Maven/Gradle uses for import —
typically whatever `JAVA_HOME` is set to. Using a different JDK can cause language-level
mismatches and re-import failures.

**Step 1: Check existing registered SDKs first**

IntelliJ may already have a Java SDK registered from a previous session or auto-detection.
Reuse it instead of scanning the filesystem:

```kotlin[IU]
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.platform.backend.observation.Observation
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec

// 1. Use already-registered Java SDK if available (preferred — no filesystem scan needed)
// getSdksOfType() is the correct API; allJdks includes all types (JavaScript, Python, etc.)
val registeredSdk = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance()).firstOrNull()

// 2. Otherwise: find JDK on disk — same JDK Maven uses (JAVA_HOME), then scan /usr/lib/jvm/
val jdkPath = if (registeredSdk == null) {
    val candidates = listOfNotNull(
        System.getenv("JAVA_HOME"),
        *java.io.File("/usr/lib/jvm").listFiles()
            ?.filter { it.isDirectory }?.sortedByDescending { it.name }
            ?.map { it.absolutePath }?.toTypedArray() ?: emptyArray(),
        System.getProperty("java.home"),   // IntelliJ JBR — always present, last resort
    )
    candidates.firstOrNull { java.io.File(it, "bin/java").exists() }
} else null

val currentSdk = ProjectRootManager.getInstance(project).projectSdk
when {
    currentSdk != null -> println("Project SDK already set: ${currentSdk.name}")
    registeredSdk != null -> {
        edtWriteAction { JavaSdkUtil.applyJdkToProject(project, registeredSdk) }
        println("Applied registered SDK: ${registeredSdk.name}")
    }
    jdkPath != null -> {
        // Check for duplicate before creating (createAndAddSDK does NOT deduplicate by path)
        val existing = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
            .firstOrNull { it.homePath == jdkPath }
        val sdk = existing ?: edtWriteAction { SdkConfigurationUtil.createAndAddSDK(jdkPath, JavaSdk.getInstance()) }
        if (sdk != null) {
            edtWriteAction { JavaSdkUtil.applyJdkToProject(project, sdk) }
            println("Applied SDK from: $jdkPath (${sdk.name})")
        } else println("ERROR: createAndAddSDK returned null for $jdkPath")
    }
    else -> println("ERROR: No JDK found. Contents of /usr/lib/jvm: ${java.io.File("/usr/lib/jvm").list()?.toList()}")
}

// 3. Trigger Maven re-sync — initial import may have failed without a JDK
if (ProjectRootManager.getInstance(project).projectSdk != null) {
    MavenProjectsManager.getInstance(project)
        .scheduleUpdateAllMavenProjects(MavenSyncSpec.full("after-jdk-fix", explicit = true))
    Observation.awaitConfiguration(project)
    println("Maven re-sync complete")
}
```

**When to run this**: Before any Maven or inspection call if the editor shows the JDK banner.
**Why same JDK as Maven**: Maven was configured for `JAVA_HOME` — using a different JDK causes
language-level mismatches and re-import failures.

---

## Multi-module: missing in-tree sibling artifact

When `mvn -pl <target>` fails with `The POM for io.example:sibling-module:jar:X-SNAPSHOT is missing` or `Could not resolve artifact io.example:sibling-module:jar:X-SNAPSHOT`, the target depends on a sibling Maven module in the same reactor that has not been installed to your local `~/.m2` yet. The fix is one extra command: install ONLY that single sibling, then retry the targeted test.

**Do NOT use `-am` (also-make).** It walks the full upstream graph (often dozens of modules) and OOM-kills the container. Install exactly one module:

```
JAVA_HOME=<picked-jdk> ./mvnw install -pl <missing-module> -DskipTests -Dspotless.check.skip=true
JAVA_HOME=<picked-jdk> ./mvnw -pl <target-module> test -Dtest=<TestClass>#<method> -DskipITs -Dspotless.check.skip=true
```

If the second command fails again with a *different* missing sibling, repeat the install once for that one too. Stop after at most 2 such installs — if more are needed, the project genuinely requires a top-level `mvn install -DskipTests`, and you should escalate rather than chain installs.

This is the only valid form of `mvn install` in this codebase: single sibling, `-DskipTests`, no `-am`.

---

## What NOT to Do

- **❌ `ProcessBuilder("./mvnw")` as primary pattern** — banned. Use `MavenRunner` or `MavenRunConfigurationType`.
- **❌ `ProcessBuilder("mvn")` without wrapper** — `mvn` is not installed. Always use `./mvnw`.
- **❌ Skip Maven sync after pom.xml edit** — without sync, imports show "cannot resolve symbol" false positives.
- **❌ Print untruncated Maven output** — Spring Boot tests generate 100k+ chars. Always use `.take(30)` + `.takeLast(30)`.
- **❌ Run multiple test classes in one `-Dtest=A,B,C,D`** — 4 Spring Boot tests × 25k chars each = MCP token overflow. Run one at a time.
