package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ofPattern

/**
 * Wire the `--debug` flag into the bundled logback configuration. Reads the
 * `proxy.log.level` system property at logback-init time (see `logback.xml`):
 *  - default: INFO
 *  - `--debug`: DEBUG
 *
 * MUST run before the first SLF4J call — logback initialises lazily on first
 * use and pins the level. [main] calls this right after command parsing for
 * exactly that reason.
 */
fun applyDebugLogging(debug: Boolean) {
    // Only set the property when --debug is requested — leaving it unset lets
    // operators override the INFO default from the outside with
    // `-Dproxy.log.level=WARN` etc. The hard-coded default in logback.xml
    // (`${proxy.log.level:-INFO}`) handles the no-flag case.
    if (debug) {
        System.setProperty("proxy.log.level", "DEBUG")
    }
}

private class NpxKtLog

fun configureLoggingAndLogStarted(homePaths: HomePaths, rawArgs: List<String>, debug: Boolean) {
    //configure loggers ASAP
    System.setProperty("proxy.log.dir", homePaths.logsDir.toString())
    System.setProperty("proxy.log.session", LocalDateTime.now().format(ofPattern("yyyy-MM-dd-HHmmss")))
    applyDebugLogging(debug)

    val log = logger<NpxKtLog>()
    log.info("Starting NpxKt ${ProxyVersionMetadata.getProxyVersion()} with home paths: $homePaths and args: ${rawArgs.joinToString(" ")}")
}
