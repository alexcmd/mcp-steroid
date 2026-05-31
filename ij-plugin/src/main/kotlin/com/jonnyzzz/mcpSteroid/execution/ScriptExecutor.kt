/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiDocumentManager
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

inline val Project.scriptExecutor: ScriptExecutor get() = service()

/**
 * Executes Kotlin scripts using IntelliJ's script engine.
 *
 * Execution flow:
 * 1. Script is compiled and evaluated to capture runnable script blocks
 * 2. Lambdas are executed in FIFO order inside a supervisorScope
 * 3. Any failure marks the whole execution as complete
 * 4. On timeout or cancellation, the Disposable is disposed and coroutine canceled
 *
 * Editing-guard pre/post-flight (former McpEditingGuard, inlined here as the
 * only caller):
 *
 *  - **Kill stuck modals.** Run [DialogKiller] to dismiss any modal dialogs
 *    left over from a previous step or background activity.
 *  - **Modality fail-fast.** Re-check via [DialogWindowsLookup]. If a modal
 *    is still up, abort with a clean tool error.
 *  - **Pre-flight commit + save + refresh.** Commit pending PSI edits, flush
 *    dirty documents, await VFS refresh. Guarantees the body sees disk-truth
 *    and any external write the body performs lands on a clean VFS.
 *  - **Run the body.** The actual script-blocks loop with the periodic dialog
 *    killer below.
 *  - **Post-flight refresh** in `finally` so the next agent step (compile,
 *    grep, follow-up edit) sees disk changes the body made (e.g. via Bash).
 *
 * Periodic dialog killing:
 * - A periodic [DialogKiller] coroutine polls during execution and dismisses
 *   any modal dialog that appears, surfacing a screenshot to the agent log.
 * - If the script intentionally shows a dialog (e.g. refactoring confirmation),
 *   call `doNotCancelOnModalityStateChange()` on the script context BEFORE
 *   the action — that cancels the killer's poll job for the rest of the run.
 *
 * Non-modal dialogs DO NOT block execution — they neither pin the EDT nor
 * count for the modality check; the script runs to completion against the
 * non-modal-dialog-visible IDE state.
 *
 * IMPORTANT: This executor runs the captured suspend block inside a supervisorScope.
 * The script code gets the coroutine context implicitly - no runBlocking needed.
 */
@Service(Service.Level.PROJECT)
class ScriptExecutor(
    private val project: Project
) : Disposable {
    private val log = Logger.getInstance(ScriptExecutor::class.java)
    override fun dispose() = Unit

    /**
     * Executes a script with progress reporting and returns its output.
     * It is a suspending function that runs inside the caller's coroutine context.
     *
     * Fast failure: If the script engine is not available or compilation fails,
     * it returns immediately with an error - no waiting.
     */
    suspend fun executeWithProgress(
        executionId: ExecutionId,
        exec: ExecCodeParams,
        resultBuilder: ExecutionResultBuilder,
    ) {
        val evalResult = project
            .codeEvalManager
            .evalCode(executionId, exec.code, resultBuilder) ?: return

        log.info("Starting execution $executionId")

        // Single Disposable governs the entire execution lifecycle. Disposing
        // it cancels every coroutine launched against [coroutineScope] below
        // (the dialog killer poll, the IDE-exception collector). No manual
        // job.cancel() calls anywhere — disposal IS cancellation.
        val executionDisposable = Disposer.newDisposable(this, "mcp-execution-$executionId")

        //TODO: move timeout up to the ToolSpec
        val timeout = exec.timeout ?: Registry.intValue("mcp.steroid.execution.timeout", 600)

        try {
            val capturedBlocks = evalResult.result
            log.info("Running ${capturedBlocks.size} script block(s) for $executionId with timeout ${timeout}s")

            coroutineScope {
                withContext(Dispatchers.IO) {
                    // Pre-flight step 1: start the dialog killer FIRST, spanning the whole
                    // execution (gate, commit, VFS, body). Launched, NEVER awaited — its own
                    // EDT work can hang, so blocking the main flow on it would re-introduce the
                    // deadlock. Does an immediate first pass, then polls. Skipped only when the
                    // caller opted out (dialog_killer=false or cancelOnModal=false).
                    resultBuilder.logMessage("[PRE] dialog-killer: start")
                    val killerJob: Job? = if (exec.dialogKiller != false && exec.cancelOnModal) {
                        launch(CoroutineName("execution-dialog-killer-$executionId")) {
                            while (isActive) {
                                try {
                                    dialogKiller().killProjectDialogs(
                                        project = project,
                                        executionId = executionId,
                                        logMessage = { resultBuilder.logMessage(it) },
                                        forceEnabled = exec.dialogKiller,
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    log.warn("Periodic dialog killer failed for $executionId: ${t.message}", t)
                                }

                                delay(1_000L.milliseconds)
                            }
                        }
                    } else null

                    Disposer.register(executionDisposable) {
                        killerJob?.cancel()
                    }

                    val context = McpScriptContextImpl(
                        project = project,
                        executionId = executionId,
                        disposable = executionDisposable,
                        resultBuilder = resultBuilder,
                        // Script can opt out of the periodic killer for this execution.
                        onDoNotCancelOnModalityStateChange = { killerJob?.cancel() },
                    )

                    // Pre-flight step 2: modality gate (no timeout). EDT + any() is pumped
                    // under a modal loop, so the read returns; current()!=nonModal() flags any
                    // elevated modality (a real dialog OR background progress/indexing).
                    resultBuilder.logMessage("[PRE] modality gate")
                    val isEdtModal = isModalEdt()

                    // Pre-flight step 3: commit PSI + save docs + VFS refresh — only when safe.
                    // commit/VFS use the write-intent EDT and hang under a modal, so gate them.
                    when {
                        !isEdtModal -> {
                            resultBuilder.logMessage("[PRE] commit + save documents")
                            commitAndSaveAllDocumentsGuardedOnEdt(project, "pre-flight")
                            resultBuilder.logMessage("[PRE] VFS refresh")
                        }

                        exec.allowModal -> {
                            resultBuilder.logMessage("[PRE] WARNING: modal detected and allow_modal=true — skipping pre-flight commit + VFS refresh (PSI/disk may be stale for this run)")
                        }

                        else -> {
                            // modal && !allow_modal: only HARD-FAIL on a real modal dialog, not
                            // on mere progress/indexing (which also elevates ModalityState).
                            val realDialog = dialogWindowsLookup().withModalityCheck { it }
                            if (realDialog) {
                                resultBuilder.reportFailed(
                                    "Modal dialog still showing after dialog killer ran — refusing to run the script. " +
                                        "Pass allow_modal=true to proceed anyway. " +
                                        "See IDE log + execution screenshot under execution id '${executionId.executionId}' for details."
                                )
                                return@withContext
                            }
                            resultBuilder.logMessage("[PRE] WARNING: elevated modality without a modal dialog (likely indexing/progress) — skipping pre-flight commit + VFS refresh")
                        }
                    }

                    val exceptionJob = launch {
                        service<ExceptionCaptureService>().exceptions.collect { ex ->
                            context.println(buildString {
                                appendLine("=== IDE Exception Captured ===")
                                appendLine("Time: ${ex.timestamp}")
                                ex.pluginId?.let { appendLine("Plugin: $it") }
                                appendLine("Message: ${ex.message}")
                                appendLine("Stacktrace:")
                                append(ex.stacktrace)
                                appendLine("=== END ===")
                            })
                        }
                    }

                    Disposer.register(executionDisposable) {
                        exceptionJob.cancel()
                    }

                    resultBuilder.logMessage("[RUN] script")
                    withTimeout(timeout.seconds) {
                        context.waitForSmartMode()
                        for ((index, block) in capturedBlocks.withIndex()) {
                            yield()
                            if (capturedBlocks.size > 1) {
                                log.info("Executing block #${index + 1}/${capturedBlocks.size} for $executionId")
                                context.progress("Executing block ${index + 1} of ${capturedBlocks.size}...")
                            }
                            block(context)
                        }
                        log.info("Execution $executionId completed normally")
                    }

                    // Post-flight commit: only if we committed pre-flight AND we are still
                    // non-modal (the body may have opened a modal or opted out of the killer).
                    if (!isModalEdt()) {
                        //maybe fire-and-forget?
                        resultBuilder.logMessage("[POST] commit + save documents")
                        commitAndSaveAllDocumentsGuardedOnEdt(project, "post-flight")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout - report as error (must be caught before CancellationException since it's a subclass)
            log.warn("Execution $executionId timed out: ${e.message}")
            resultBuilder.logRemappedException("Execution timed out", e, evalResult.lineMapping)
            resultBuilder.reportFailed("Execution timed out after $timeout seconds")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Unexpected error during execution $executionId: ${t.message}", t)
            val remappedMessage = evalResult.lineMapping.remapStackTrace(t.message ?: "")
            resultBuilder.logRemappedException("Unexpected error during execution: $remappedMessage", t,
                evalResult.lineMapping
            )
            resultBuilder.reportFailed("Unexpected error during execution: $remappedMessage")
        } finally {
            Disposer.dispose(executionDisposable)
        }
    }

    private suspend fun isModalEdt(): Boolean =
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            ModalityState.current() != ModalityState.nonModal()
        }

    private suspend fun commitAndSaveAllDocumentsGuardedOnEdt(
        project: Project,
        stage: String,
    ) {
        val commitTimeout = 60_000L

        return try {
            // Deadlock guard for the pre/post-flight document commit (write-intent EDT). Generous
            // enough not to false-trip on a busy EDT, short enough to fail fast vs a multi-minute hang
            // if a modal slips into the gate→commit window.
            withTimeout(commitTimeout.milliseconds) {
                withContext(Dispatchers.EDT) {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    FileDocumentManager.getInstance().saveAllDocuments()
                    project.vfsRefreshService.awaitRefresh()
                }
            }
        } catch (_: TimeoutCancellationException) {
            log.error("IntelliJ appears deadlocked during $stage commit " +
                "— commitAndSaveAllDocuments did not complete within " +
                "${commitTimeout}ms (EDT write-intent likely withheld by a modal)")

            throw ToolCallErrorException(
                "IntelliJ appears deadlocked during $stage: commitAndSaveAllDocuments did " +
                    "not complete within ${commitTimeout}ms — a modal dialog likely " +
                    "blocks the EDT. See the IDE log."
            )
        }
    }

    /**
     * Logs an exception with stack trace line numbers remapped from wrapped-file coordinates
     * to user-code coordinates, so agents see meaningful line references.
     */
    private fun ExecutionResultBuilder.logRemappedException(
        message: String,
        throwable: Throwable,
        lineMapping: LineMapping,
    ) {
        val cleanTrace = lineMapping.cleanStackTrace(throwable.stackTraceToString())
        val text = "ERROR: $message\n$cleanTrace"
        logMessage(text)
    }

}
