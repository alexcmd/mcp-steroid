/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.nio.file.Files
import java.nio.file.Path

data class IdeStartupConfigFile(
    val relativePath: String,
    val content: String,
)

/**
 * Startup-state files that must exist before a fresh IntelliJ config dir is
 * launched. They suppress onboarding and AI-promo first-run flows that would
 * otherwise block headless/managed runs behind modal dialogs or slow network
 * verdict calculations.
 */
fun ideStartupConfigFiles(): List<IdeStartupConfigFile> = listOf(
    IdeStartupConfigFile(
        relativePath = "options/other.xml",
        content = """<application>
  <component name="PropertyService"><![CDATA[{"keyToString":{"experimental.ui.on.first.startup":"true","experimental.ui.onboarding.proposed.version":"suppressed","RunOnceActivity.llm.onboarding.window.launcher.v7":"true"}}]]></component>
</application>
""",
    ),
    IdeStartupConfigFile(
        relativePath = "early-access-registry.txt",
        content = "switched.from.classic.to.islands\nfalse\n",
    ),
    IdeStartupConfigFile(
        relativePath = "options/AIOnboardingPromoWindowAdvisor.xml",
        content = """<application>
  <component name="AIOnboardingPromoWindowAdvisor">
    <option name="shouldShowNextTime" value="NO" />
    <option name="wasShown" value="true" />
    <option name="attempts" value="1" />
  </component>
</application>
""",
    ),
)

fun writeIdeStartupConfigFiles(configDir: Path) {
    for (file in ideStartupConfigFiles()) {
        val target = configDir.resolve(file.relativePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, file.content)
    }
}
