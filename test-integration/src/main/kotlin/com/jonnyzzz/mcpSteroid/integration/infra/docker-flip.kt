package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File


class HostMappingsInfo(
    val volumes: List<ContainerVolume>,
    val envOverride: Map<String, String>,
    val containerSetup: (ContainerDriver) -> Unit
) {
    fun applyToContainer(container: ContainerDriver, lifetime: CloseableStack) {
        containerSetup(container)
    }
}

fun setupHostMappings(mountSshAgent: Boolean, mountDockerSocket: Boolean): HostMappingsInfo {
    val hostHomeDir = File(System.getProperty("user.home"))
    val dockerSocketFile = File("/var/run/docker.sock")
    val sshAgentHostMountPath = "/tmp/host-ssh-agent.sock"
    val sshAgentGuestPath = "/tmp/ssh-agent.sock"
    val hostNetrcGuestPath = "/tmp/host-netrc"
    val hostNetrcFile = File(hostHomeDir, ".netrc").takeIf { it.isFile }
    val hostM2SettingsGuestPath = "/tmp/host-m2-settings.xml"
    val hostM2SettingsFile = File(hostHomeDir, ".m2/settings.xml").takeIf { it.isFile }
    val hostJetBrainsTokensGuestPath = "/tmp/host-jb-tokens"
    val hostJetBrainsTokensDir = File(hostHomeDir, ".jb/tokens").takeIf { it.isDirectory }
    val hostJetBrainsTeamTokenGuestPath = "/tmp/host-jb-jetbrains-team-token.json"
    val hostJetBrainsTeamTokenFile = File(hostHomeDir, ".jb/tokens/jetbrains.team.json").takeIf { it.isFile }
    val hostPrivatePackagesAuthCacheGuestPath = "/tmp/host-private-packages-authorizer-cache"
    val hostPrivatePackagesAuthCacheDir = sequenceOf(
        File(hostHomeDir, "Library/Caches/JetBrains/private-packages-authorizer"),
        File(hostHomeDir, ".cache/JetBrains/private-packages-authorizer"),
    ).firstOrNull { it.isDirectory }
    val hostPackagesClientId = readHostCredential(
        "test.integration.intellij.packages.client.id",
        "JB_SPACE_CLIENT_ID",
        "TEST_INTEGRATION_JB_SPACE_CLIENT_ID",
    )
    val hostPackagesClientSecret = readHostCredential(
        "test.integration.intellij.packages.client.secret",
        "JB_SPACE_CLIENT_SECRET",
        "TEST_INTEGRATION_JB_SPACE_CLIENT_SECRET",
    )
    val hasPackagesEnvCredentials = hostPackagesClientId != null && hostPackagesClientSecret != null
    val sshAgentSocketFile: File? = if (mountSshAgent) {
        val sshAuthSock = System.getenv("SSH_AUTH_SOCK")?.trim()?.takeIf { it.isNotBlank() }
        if (sshAuthSock == null) {
            // No ssh-agent on host (typical on TC / CI agents) — skip the forward
            // rather than hard-failing. Tests that actually need SSH (e.g. git
            // clone from a private remote) will fail later with a clearer error;
            // tests that don't (DPAIA arena clones from public HTTPS remotes,
            // bright scenarios drive Maven/Gradle locally inside the container)
            // proceed cleanly. Pass `mountSshAgent = false` explicitly to quell
            // this notice in tests that intentionally run without SSH.
            println("[IDE-AGENT] SSH_AUTH_SOCK not set — skipping SSH agent socket forward")
            null
        } else {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val isMacHost = osName.contains("mac")
            if (isMacHost) {
                // Docker Desktop provides a stable SSH agent socket proxy that is more reliable
                // than mounting launchd paths like /private/tmp/com.apple.launchd.../Listeners.
                File("/run/host-services/ssh-auth.sock")
            } else {
                val socket = File(sshAuthSock)
                if (socket.exists()) {
                    socket
                } else {
                    println("[IDE-AGENT] SSH_AUTH_SOCK points to a missing socket (${socket.absolutePath}) — skipping SSH agent forward")
                    null
                }
            }
        }
    } else {
        null
    }

    if (mountDockerSocket) {
        require(dockerSocketFile.exists()) {
            "mountDockerSocket=true but Docker socket not found at ${dockerSocketFile.absolutePath}. " +
                "Ensure Docker is running on the host."
        }
        println("[IDE-AGENT] Docker socket mount enabled: ${dockerSocketFile.absolutePath}")
    }
    if (sshAgentSocketFile != null) {
        println(
            "[IDE-AGENT] SSH agent mount enabled: ${sshAgentSocketFile.absolutePath} -> $sshAgentHostMountPath " +
                "(exported as $sshAgentGuestPath)"
        )
    }
    if (hostNetrcFile != null) {
        println("[IDE-AGENT] Host netrc mount enabled: ${hostNetrcFile.absolutePath} -> $hostNetrcGuestPath")
    }
    if (hostM2SettingsFile != null) {
        println("[IDE-AGENT] Host Maven settings mount enabled: ${hostM2SettingsFile.absolutePath} -> $hostM2SettingsGuestPath")
    }
    if (hostJetBrainsTeamTokenFile != null) {
        println(
            "[IDE-AGENT] Host jb token cache mount enabled: " +
                "${hostJetBrainsTeamTokenFile.absolutePath} -> $hostJetBrainsTeamTokenGuestPath"
        )
    }
    if (hostJetBrainsTokensDir != null) {
        println(
            "[IDE-AGENT] Host jb tokens dir mount enabled: " +
                "${hostJetBrainsTokensDir.absolutePath} -> $hostJetBrainsTokensGuestPath"
        )
    }
    if (hostPrivatePackagesAuthCacheDir != null) {
        println(
            "[IDE-AGENT] Host private-packages auth cache mount enabled: " +
                "${hostPrivatePackagesAuthCacheDir.absolutePath} -> $hostPrivatePackagesAuthCacheGuestPath"
        )
    }
    if (hasPackagesEnvCredentials) {
        println("[IDE-AGENT] JB_SPACE credentials pass-through enabled (source: host env/system properties)")
    } else if (hostPackagesClientId != null || hostPackagesClientSecret != null) {
        println("[IDE-AGENT] WARNING: Incomplete JB_SPACE credentials, ignoring host pass-through")
    }


    val mounts = buildList {
        if (mountDockerSocket) add(ContainerVolume(dockerSocketFile, "/var/run/docker.sock", "rw"))
        if (sshAgentSocketFile != null) add(ContainerVolume(sshAgentSocketFile, sshAgentHostMountPath, "rw"))
        if (hostNetrcFile != null) add(ContainerVolume(hostNetrcFile, hostNetrcGuestPath, "ro"))
        if (hostM2SettingsFile != null) add(ContainerVolume(hostM2SettingsFile, hostM2SettingsGuestPath, "ro"))
        if (hostJetBrainsTokensDir != null) add(
            ContainerVolume(
                hostJetBrainsTokensDir,
                hostJetBrainsTokensGuestPath,
                "ro"
            )
        )
        if (hostJetBrainsTeamTokenFile != null) {
            add(ContainerVolume(hostJetBrainsTeamTokenFile, hostJetBrainsTeamTokenGuestPath, "ro"))
        }
        if (hostPrivatePackagesAuthCacheDir != null) {
            add(
                ContainerVolume(
                    hostPrivatePackagesAuthCacheDir,
                    hostPrivatePackagesAuthCacheGuestPath,
                    "ro",
                )
            )
        }
    }

    val containerEnv = buildMap {
        if (sshAgentSocketFile != null) {
            put("SSH_AUTH_SOCK", sshAgentGuestPath)
        }
        if (hasPackagesEnvCredentials) {
            put("JB_SPACE_CLIENT_ID", hostPackagesClientId)
            put("JB_SPACE_CLIENT_SECRET", hostPackagesClientSecret)
        }
        if (mountDockerSocket) {
            // Testcontainers creates new containers on the HOST Docker daemon (via the mounted socket).
            // Those containers expose ports on the host network, not inside this container.
            // Without this override, Testcontainers connects to "localhost" which is the container
            // itself — causing connection failures. host.docker.internal resolves to the host gateway
            // (set via --add-host in docker-container-start.kt), bridging the gap.
            put("TESTCONTAINERS_HOST_OVERRIDE", "host.docker.internal")
        }
    }

    class Scope(val container: ContainerDriver)

    val actions = mutableListOf<Scope.() -> Unit>()

    actions += {
        if (
            hasPackagesEnvCredentials ||
            hostNetrcFile != null ||
            hostM2SettingsFile != null ||
            hostJetBrainsTokensDir != null ||
            hostJetBrainsTeamTokenFile != null ||
            hostPrivatePackagesAuthCacheDir != null
        ) {
            container.startProcessInContainer {
                this
                    .user("0:0")
                    .args(
                        "bash", "-lc",
                        """
                    set -euo pipefail
                    mkdir -p /home/agent/.m2 /home/agent/.jb/tokens
                    chown -R agent:agent /home/agent/.m2 /home/agent/.jb
                    if [ -f "$hostNetrcGuestPath" ]; then
                      install -m 600 -o agent -g agent "$hostNetrcGuestPath" /home/agent/.netrc
                    fi
                    if [ -f "$hostM2SettingsGuestPath" ]; then
                      install -D -m 600 -o agent -g agent "$hostM2SettingsGuestPath" /home/agent/.m2/settings.xml
                    fi
                    if [ -f "$hostJetBrainsTeamTokenGuestPath" ]; then
                      install -D -m 600 -o agent -g agent "$hostJetBrainsTeamTokenGuestPath" /home/agent/.jb/tokens/jetbrains.team.json
                    fi
                    if [ -d "$hostJetBrainsTokensGuestPath" ]; then
                      cp -R "$hostJetBrainsTokensGuestPath"/. /home/agent/.jb/tokens/
                    fi
                    read_netrc_field_for_machine() {
                      machineName="${'$'}1"
                      field="${'$'}2"
                      netrcPath="${'$'}3"
                      awk -v machineName="${'$'}machineName" -v field="${'$'}field" '
                        BEGIN { in_host = 0 }
                        ${'$'}1 == "machine" {
                          if (${'$'}2 == machineName) {
                            in_host = 1
                          } else if (in_host) {
                            exit
                          } else {
                            in_host = 0
                          }
                        }
                        in_host {
                          for (i = 1; i <= NF; i++) {
                            if (${'$'}i == field && i + 1 <= NF) {
                              print ${'$'}(i + 1)
                              exit
                            }
                          }
                        }
                      ' "${'$'}netrcPath"
                    }

                    upsert_netrc_machine() {
                      machineName="${'$'}1"
                      machineLogin="${'$'}2"
                      machinePassword="${'$'}3"
                      netrcFile="/home/agent/.netrc"
                      tmpNetrc="$(mktemp)"
                      {
                        printf "machine %s login %s password %s\n" "${'$'}machineName" "${'$'}machineLogin" "${'$'}machinePassword"
                        if [ -f "${'$'}netrcFile" ]; then
                          awk -v machineName="${'$'}machineName" '
                            BEGIN { skip = 0 }
                            ${'$'}1 == "machine" {
                              if (${'$'}2 == machineName) {
                                skip = 1
                                next
                              }
                              skip = 0
                            }
                            !skip { print }
                          ' "${'$'}netrcFile"
                        fi
                      } > "${'$'}tmpNetrc"
                      chmod 600 "${'$'}tmpNetrc"
                      mv "${'$'}tmpNetrc" "${'$'}netrcFile"
                      chown agent:agent "${'$'}netrcFile"
                    }

                    packagesNetrc="/home/agent/.netrc"
                    packagesUser="${'$'}{JB_SPACE_CLIENT_ID:-}"
                    packagesPassword="${'$'}{JB_SPACE_CLIENT_SECRET:-}"
                    if [ -z "${'$'}packagesUser" ] || [ -z "${'$'}packagesPassword" ]; then
                      packagesUser=""
                      packagesPassword=""
                    fi
                    if [ -z "${'$'}packagesUser" ] && [ -f "${'$'}packagesNetrc" ]; then
                      packagesUser="$(read_netrc_field_for_machine "packages.jetbrains.team" "login" "${'$'}packagesNetrc")"
                      packagesPassword="$(read_netrc_field_for_machine "packages.jetbrains.team" "password" "${'$'}packagesNetrc")"
                    fi

                    # Host ~/.m2/settings.xml often contains an expired private packages token.
                    # Prefer current packages.jetbrains.team credentials from ~/.netrc for all
                    # IntelliJ private Maven server IDs used during project import/indexing.
                    if [ -n "${'$'}packagesUser" ] && [ -n "${'$'}packagesPassword" ]; then
                      touch /home/agent/.netrc
                      chmod 600 /home/agent/.netrc
                      upsert_netrc_machine "packages.jetbrains.team" "${'$'}packagesUser" "${'$'}packagesPassword"
                      upsert_netrc_machine "cache-redirector.jetbrains.com" "${'$'}packagesUser" "${'$'}packagesPassword"
                      upsert_netrc_machine "ultimate-bazel-cache-http.labs.jb.gg" "${'$'}packagesUser" "${'$'}packagesPassword"
                      cat > /home/agent/.m2/settings.xml <<EOF
                    <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
                      <servers>
                        <server>
                          <id>code-with-me-lobby-server-private</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                        <server>
                          <id>intellij-private-dependencies</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                        <server>
                          <id>grazie-platform-private</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                        <server>
                          <id>jcp-github-mirror-private</id>
                          <username>${'$'}packagesUser</username>
                          <password>${'$'}packagesPassword</password>
                        </server>
                      </servers>
                    </settings>
                    EOF
                      chown agent:agent /home/agent/.m2/settings.xml
                      chmod 600 /home/agent/.m2/settings.xml
                    fi
                    if [ -d "$hostPrivatePackagesAuthCacheGuestPath" ]; then
                      mkdir -p /home/agent/.cache/JetBrains
                      rm -rf /home/agent/.cache/JetBrains/private-packages-authorizer
                      cp -R "$hostPrivatePackagesAuthCacheGuestPath" /home/agent/.cache/JetBrains/private-packages-authorizer
                    fi
                    if [ -d /home/agent/.cache ]; then
                      chown -R agent:agent /home/agent/.cache
                    fi
                    """.trimIndent()
                    )
                    .timeoutSeconds(30)
                    .description("Install host auth artifacts for agent user")
                    .quietly()
            }.assertExitCode(0) {
                "Failed installing host auth artifacts for agent user: $stderr"
            }
        }
    }

    actions += {
        if (mountDockerSocket) {
            // Fix docker socket permissions: the GID of /var/run/docker.sock on the host
            // won't match the GID of the 'docker' group inside the container (created by
            // groupadd without a specific GID). chmod 666 makes it accessible to the agent user.
            val fixDockerSocketResult = container.startProcessInContainer {
                this
                    .user("0:0")
                    .args(
                        "bash", "-lc",
                        """
                    set -euo pipefail
                    if [ ! -S "/var/run/docker.sock" ]; then
                      echo "Mounted Docker socket is missing: /var/run/docker.sock" >&2
                      exit 1
                    fi
                    chmod 666 /var/run/docker.sock
                    """.trimIndent()
                    )
                    .timeoutSeconds(10)
                    .description("Fix docker socket permissions for TestContainers")
                    .quietly()
            }.awaitForProcessFinish()
            require(fixDockerSocketResult.exitCode == 0) {
                "Failed to fix docker socket permissions: ${fixDockerSocketResult.stderr}"
            }
        }
    }

    actions += {
        if (sshAgentSocketFile != null) {
            container.startProcessInContainer {
                this
                    .user("0:0")
                    .args(
                        "bash", "-lc",
                        """
                    set -euo pipefail
                    if [ ! -S "$sshAgentHostMountPath" ]; then
                      echo "Mounted SSH agent socket is missing: $sshAgentHostMountPath" >&2
                      exit 1
                    fi
                    chmod 666 "$sshAgentHostMountPath" || true
                    ln -sfn "$sshAgentHostMountPath" "$sshAgentGuestPath"
                    """.trimIndent()
                    )
                    .timeoutSeconds(30)
                    .description("Initialize SSH agent socket path in container")
                    .quietly()
            }.assertExitCode(0) {
                "Failed to initialize SSH agent socket in container: $stderr"
            }
        }
    }

    return HostMappingsInfo(mounts, containerEnv, { Scope(it).apply { actions.forEach { it() } } })
}

private fun readHostCredential(propertyName: String, vararg envNames: String): String? {
    val fromProperty = System.getProperty(propertyName)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (fromProperty != null) return fromProperty
    return envNames
        .asSequence().firstNotNullOfOrNull { envName ->
            System.getenv(envName)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
}
