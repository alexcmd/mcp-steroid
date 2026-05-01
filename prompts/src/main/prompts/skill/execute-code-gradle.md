Execute Code: Gradle Patterns

Running Gradle syncs and tests through IntelliJ ExternalSystem APIs instead of nested ProcessBuilder.

# Execute Code: Gradle Patterns

Use this resource when a `steroid_execute_code` script must run Gradle work from inside IntelliJ. For simple final verification from an agent shell, the Bash tool can run `./gradlew` directly. Inside `steroid_execute_code`, never spawn a nested Gradle process with `ProcessBuilder("./gradlew")`; use IntelliJ's Gradle ExternalSystem APIs.

## Sync after build.gradle.kts Change

After modifying `build.gradle`, `build.gradle.kts`, or `settings.gradle.kts`, trigger a Gradle re-import and wait for final import tasks before compiling, running tests, or using indexed PSI:

```kotlin[IU]
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlin.time.Duration.Companion.minutes

val gradleProjectPath = project.basePath ?: error("Project base path is not set")
val importDone = CompletableDeferred<Unit>()
val importConnection = project.messageBus.connect(disposable)

fun isCurrentGradleProject(path: String?): Boolean =
    path == null || path == gradleProjectPath

importConnection.subscribe(
    ProjectDataImportListener.TOPIC,
    object : ProjectDataImportListener {
        override fun onFinalTasksFinished(projectPath: String?) {
            if (isCurrentGradleProject(projectPath)) {
                importDone.complete(Unit)
            }
        }

        override fun onImportFailed(projectPath: String?, t: Throwable) {
            if (isCurrentGradleProject(projectPath)) {
                importDone.completeExceptionally(t)
            }
        }
    }
)
importDone.invokeOnCompletion { importConnection.disconnect() }

ExternalSystemUtil.refreshProject(
    gradleProjectPath,
    ImportSpecBuilder(project, GradleConstants.SYSTEM_ID).build()
)
withTimeout(8.minutes) { importDone.await() }
waitForSmartMode()
println("Gradle sync complete")
```

Key points:
- `ProjectDataImportListener.onFinalTasksFinished` is the Gradle import boundary; `onImportFinished` is too early because final import tasks still run afterward.
- Subscribe before calling `ExternalSystemUtil.refreshProject(...)` so the listener cannot miss a fast import event.
- `waitForSmartMode()` after final tasks lets indexing settle before follow-up indexed reads.
- Use the two-argument `ExternalSystemUtil.refreshProject(path, importSpec)` form; older overloads are deprecated.
- If sync fails, fix the Gradle/JDK/import problem. Do not continue with unresolved dependencies.

## Agent: Run Gradle Tests (two-call pattern with SMT events)

The preferred Gradle test runner from `steroid_execute_code`. Uses `GradleRunConfiguration.isRunAsTest = true` so per-test events flow through `SMTRunnerEventsListener.TEST_STATUS` (verified end-to-end by `GradleTestExecutionTest` in this repo). The two-call shape — launch in call 1, poll in call 2+ — keeps every script under the MCP HTTP transport's ~60-second client-side cancel.

> ⚠️ **Each call must finish in under 60 seconds.** A typical Gradle test on a fresh checkout (cold daemon, dependency resolve, compile, test execution) easily exceeds that. Do NOT try a single-call `withTimeout(5.minutes) { deferred.await() }` recipe; it will be cancelled by the client mid-await even though the IDE-side script timeout is much larger.

### Call 1 — launch the Gradle test, return immediately

```kotlin[IU]
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration

data class GradleTestSummary(val total: Int, val failed: Int)
val resultKey = Key.create<CompletableDeferred<GradleTestSummary>>("mcp.steroid.gradle.test.summary")
val labelKey = Key.create<String>("mcp.steroid.gradle.test.label")

if (project.getUserData(resultKey)?.isActive == true) {
    println("Gradle run already in flight — call the polling script instead.")
} else {
    val deferred = CompletableDeferred<GradleTestSummary>()
    project.putUserData(resultKey, deferred)
    project.putUserData(labelKey, ":api:test --tests com.example.api.ProductControllerTest")

    project.messageBus.connect().subscribe(
        SMTRunnerEventsListener.TEST_STATUS,
        object : SMTRunnerEventsListener {
            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                val total = testsRoot.allTests.size
                val failed = testsRoot.allTests.count { it.isDefect }
                deferred.complete(GradleTestSummary(total, failed))
            }
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
            override fun onCustomProgressTestFinished() {}
            override fun onCustomProgressTestFailed() {}
            override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
            override fun onSuiteTreeStarted(suite: SMTestProxy) {}
        }
    )

    val runManager = RunManager.getInstance(project)
    val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
    val runConfig = factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration
    runConfig.name = "Gradle test (MCP)"
    runConfig.settings.externalProjectPath = project.basePath
    runConfig.settings.taskNames = listOf(
        ":api:test",
        "--tests", "com.example.api.ProductControllerTest",
        "--rerun-tasks",                       // never trust UP-TO-DATE on first run after a code edit
        "--console=plain",
    )

    // Critical: enable SMT integration. Without isRunAsTest=true, SMTRunnerEventsListener
    // never fires for Gradle runs, even with `:test` in the task list.
    (runConfig as GradleRunConfiguration).isRunAsTest = true

    val settings = runManager.createConfiguration(runConfig, factory)
    runManager.addConfiguration(settings)
    runManager.selectedConfiguration = settings

    withContext(Dispatchers.EDT) {
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }
    println("Gradle test launched: ${runConfig.settings.taskNames}")
    println("EXECUTION_VIA: GradleRunConfiguration")
    println("Call the polling script next; expected total runtime 30–180s for cold daemon.")
}
```

### Call 2 — poll for the SMT result (re-issue every 20–30s until done)

```kotlin[IU]
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

data class GradleTestSummary(val total: Int, val failed: Int)
val resultKey = Key.create<CompletableDeferred<GradleTestSummary>>("mcp.steroid.gradle.test.summary")
val labelKey = Key.create<String>("mcp.steroid.gradle.test.label")

val deferred = project.getUserData(resultKey)
if (deferred == null) {
    println("No Gradle run in flight. Run the launch script first.")
} else {
    val summary = withTimeoutOrNull(30.seconds) { deferred.await() }
    if (summary == null) {
        println("Gradle test still running after 30s; call this script again to keep polling.")
    } else {
        val label = project.getUserData(labelKey) ?: "<unknown>"
        val passed = summary.failed == 0 && summary.total > 0
        println("EXECUTION_VIA: GradleRunConfiguration")
        println("TEST_LABEL: $label")
        println("TEST_TOTAL: ${summary.total}")
        println("TEST_FAILED: ${summary.failed}")
        println("TEST_RESULT: ${if (passed) "PASSED" else "FAILED"}")
        project.putUserData(resultKey, null)
        project.putUserData(labelKey, null)
    }
}
```

### Targeting Rules

- Always use the subproject task path for targeted tests: `:api:test --tests com.example.MyTest`.
- For tests in multiple subprojects, batch them in one Gradle invocation with repeated `:subproject:test --tests FQCN` pairs.
- Add `--rerun-tasks` to the first Gradle test run after writing new source files. Without it, Gradle may report `UP-TO-DATE`, skip tests, and still print `BUILD SUCCESSFUL`.
- Keep the full suite as a final separate run after targeted tests pass.
- `isRunAsTest = true` is the difference between "I get per-test pass/fail" and "I get only a build-success boolean". Always set it for test runs.

### Build-only Gradle goals (no test framework)

For non-test goals like `:assemble`, `:build`, `:check`, where SMT events are irrelevant, the `ExternalSystemUtil.runTask` + `TaskCallback` path remains useful — it gives a boolean success without a UI run config. Same `withTimeoutOrNull(30.seconds)` polling shape applies.

## Inspect Gradle Test Failures from JUnit XML

When the IDE Gradle runner returns `success=false`, inspect Gradle's XML results before retrying. This is cheaper and more precise than immediately rerunning Gradle:

```kotlin[IU]
import com.intellij.openapi.vfs.LocalFileSystem

val root = java.io.File(project.basePath!!).toPath()
val resultFiles = java.nio.file.Files.walk(root)
    .filter { it.fileName.toString().startsWith("TEST-") }
    .filter { it.fileName.toString().endsWith(".xml") }
    .filter { it.toString().contains("/build/test-results/") }
    .toList()

println("Gradle XML result files: ${resultFiles.size}")
for (path in resultFiles.take(20)) {
    val text = java.nio.file.Files.readString(path)
    val failures = Regex("<failure[^>]*>(.+?)</failure>", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .map { it.groupValues[1].take(500) }
        .toList()
    if (failures.isNotEmpty()) {
        println("FAIL ${root.relativize(path)}")
        println(failures.first())
    }
}
LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
```

## ProcessBuilder("./gradlew") Is Banned Inside steroid_execute_code

Do not spawn `./gradlew test` through `ProcessBuilder` inside `steroid_execute_code`.

Why:
- It spawns a nested Gradle daemon from inside the IDE JVM.
- It can inherit the wrong classpath or JDK environment.
- It often costs more tokens because agents print or summarize huge Gradle output.

If the IDE Gradle runner is unavailable or times out after you gathered evidence, use the Bash tool outside `steroid_execute_code`, for example `JAVA_HOME=<Recommended JAVA_HOME> ./gradlew :api:test --tests com.example.api.ProductControllerTest --rerun-tasks --no-daemon --console=plain`.
