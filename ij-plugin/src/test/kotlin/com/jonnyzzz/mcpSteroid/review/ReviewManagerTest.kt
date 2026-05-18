/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.review

import com.intellij.openapi.components.service
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the agent-visible contract for [ReviewManager.requestReview]:
 *
 *  1. Review mode `NEVER` → auto-approves without emitting a "review pending"
 *     progress notification.
 *  2. Review timeout → `reportFailed` is called with a message that names the
 *     review as the cause, so the agent can distinguish a stuck-review
 *     timeout from a generic execution timeout.
 *  3. While the review UI is open → a *progress* notification naming the
 *     review is emitted **before** the wait, so the agent can render
 *     "awaiting human review" to the user immediately rather than waiting
 *     for the failure to arrive at end-of-call (or being client-side-timed-
 *     out before any signal at all).
 *
 * Test 3 is the TDD driver — it fails against the pre-fix behavior because
 * `requestReview` does not emit a progress message when the review UI opens.
 *
 * Threading note matches [ApplyPatchTest]: the review flow dispatches its
 * EDT work via `withContext(Dispatchers.EDT)`, so the test must NOT run on
 * EDT itself (`runInDispatchThread() = false`), otherwise `timeoutRunBlocking`
 * parks EDT and the dispatch deadlocks.
 */
class ReviewManagerTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    private val savedRegistryValues = mutableMapOf<String, String>()

    override fun tearDown() {
        try {
            for ((key, value) in savedRegistryValues) Registry.get(key).setValue(value)
            savedRegistryValues.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun overrideRegistry(key: String, value: String) {
        val k = Registry.get(key)
        if (key !in savedRegistryValues) savedRegistryValues[key] = k.asString()
        k.setValue(value)
    }

    fun testReviewModeNeverAutoApprovesWithoutEmittingReviewProgress(): Unit =
        timeoutRunBlocking(15.seconds) {
            overrideRegistry("mcp.steroid.review.mode", "NEVER")
            val builder = TestResultBuilder()

            val approved = project.service<ReviewManager>().requestReview(
                executionId = ExecutionId("test-auto-approve"),
                execCodeParams = testExecParams("println(\"ok\")"),
                resultBuilder = builder,
            )

            assertTrue("Review must be auto-approved when mode=NEVER", approved)
            assertFalse("Auto-approve must not mark the builder failed", builder.isFailed)
            assertTrue(
                "Auto-approve must not emit a 'review pending' progress message (got: ${builder.progressMessages})",
                builder.progressMessages.none { it.contains("review", ignoreCase = true) },
            )
        }

    fun testReviewTimeoutReportsFailureNamingTheReview(): Unit =
        timeoutRunBlocking(30.seconds) {
            // Force review-required + a 1-second review timeout. The test
            // never calls approve()/reject(), so the timeout fires.
            overrideRegistry("mcp.steroid.review.mode", "ALWAYS")
            overrideRegistry("mcp.steroid.review.timeout", "1")
            val builder = TestResultBuilder()

            val approved = project.service<ReviewManager>().requestReview(
                executionId = ExecutionId("test-review-timeout"),
                execCodeParams = testExecParams("println(\"never runs\")"),
                resultBuilder = builder,
            )

            assertFalse("Review timeout must return false (not approved)", approved)
            assertTrue("Review timeout must mark the builder failed", builder.isFailed)

            val msg = builder.failureMessage.orEmpty()
            assertTrue(
                "Failure message must name the review as the cause so the agent can " +
                    "distinguish stuck-review-timeout from generic execution timeout (got: '$msg')",
                msg.contains("review", ignoreCase = true),
            )
            assertTrue(
                "Failure message must mention the timeout cause (got: '$msg')",
                msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true),
            )
        }

    fun testReviewWaitEmitsProgressMessageBeforeTheTimeoutFires(): Unit =
        timeoutRunBlocking(30.seconds) {
            // Force review-required + a 1-second review timeout. We never
            // approve(); the timeout fires and `requestReview` returns false.
            // The CONTRACT this test pins: before the wait, a progress message
            // naming the review must be emitted — so the agent sees "awaiting
            // human review" via MCP `notifications/progress` immediately when
            // the review UI opens, not minutes later at end-of-call.
            overrideRegistry("mcp.steroid.review.mode", "ALWAYS")
            overrideRegistry("mcp.steroid.review.timeout", "1")
            val builder = TestResultBuilder()

            project.service<ReviewManager>().requestReview(
                executionId = ExecutionId("test-review-progress"),
                execCodeParams = testExecParams("println(\"never runs\")"),
                resultBuilder = builder,
            )

            assertTrue(
                "Review must emit a progress message naming the review when the UI opens " +
                    "(got progressMessages=${builder.progressMessages})",
                builder.progressMessages.any { it.contains("review", ignoreCase = true) },
            )
        }
}
