package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver

class GuiContainer(
    val console: ConsoleDriver,
    val container: ContainerDriver,
    val windowsDriver: XcvbWindowDriver,
    val videoDriver: XcvbVideoDriver,
    val screenshotDriver: XcvbScreenshotDriver,
    val windowsLayout: WindowLayoutManager,
    val consoleDriver: XcvbConsoleDriver,
    val inputDriver: XcvbInputDriver,
    val xcvb: XcvbDriver
)

fun setupGuiContainerServices(lifetime: CloseableStack,
                              basicContainer: ContainerDriver,
                              layoutManager: LayoutManager,
                              containerMountedPath: String,
                              realConsoleTitle: String): GuiContainer {

    val xcvb = XcvbDriver(
        lifetime,
        basicContainer,
        layoutManager
    )

    xcvb.startDisplayServer()
    val container = xcvb.withDisplay(basicContainer)

    val windowsDriver = XcvbWindowDriver(lifetime, container, xcvb.wholeScreenAreal())
    windowsDriver.startWindowManager()

    val videoDriver = XcvbVideoDriver(lifetime, container, windowsDriver, xcvb, "$containerMountedPath/video", realConsoleTitle)
    videoDriver.startVideoService()

    val screenshotDriver = XcvbScreenshotDriver(lifetime, container, "$containerMountedPath/screenshot")
    screenshotDriver.startScreenshotCapture()

    val windowsLayout = WindowLayoutManager(windowsDriver, layoutManager)

    val consoleDriver = XcvbConsoleDriver(lifetime, container, windowsDriver)
    val console = consoleDriver.createConsoleDriver(container, realConsoleTitle, windowsLayout.layoutStatusConsoleWindow())

    val inputDriver = XcvbInputDriver(container)
    XcvbSkillDriver(lifetime, container)

    return GuiContainer(
        console = console,
        xcvb = xcvb,
        container = container,
        windowsDriver = windowsDriver,
        videoDriver = videoDriver,
        screenshotDriver = screenshotDriver,
        windowsLayout = windowsLayout,
        consoleDriver = consoleDriver,
        inputDriver = inputDriver,
    )
}
