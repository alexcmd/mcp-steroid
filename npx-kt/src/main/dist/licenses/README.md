# Bundled licenses

This directory aggregates the licensing material of every third-party
component shipped inside the mcp-steroid-proxy distribution. Files
also exist in each component's own folder; this index is the
operator-facing single point of reference.

## Components

| Component | License location |
|---|---|
| Amazon Corretto JDK 21 (linux-amd64) | `corretto-jdk-21/linux-amd64/` |
| Amazon Corretto JDK 21 (linux-arm)   | `corretto-jdk-21/linux-arm/`   |
| Amazon Corretto JDK 21 (mac-arm)     | `corretto-jdk-21/mac-arm/`     |
| Amazon Corretto JDK 21 (windows-amd64) | `corretto-jdk-21/windows-amd64/` |
| 7-Zip (NSIS-capable Unix builds)     | `seven-zip/License.txt`        |
| MCP Steroid (this proxy + IntelliJ plugin) | `mcp-steroid/EULA`       |

## License summaries

* **Amazon Corretto 21** — OpenJDK is licensed under GPL v2 with the
  Classpath Exception. Per-OpenJDK-module licenses are under
  `legal/`. See each platform's `LICENSE`, `ADDITIONAL_LICENSE_INFO`,
  `ASSEMBLY_EXCEPTION`, and `THIRD_PARTY_README` for the full text.
* **7-Zip** — LGPL v2.1 or later, with unRAR restriction (we don't
  ship the RAR codec). Upstream: https://www.7-zip.org/license.txt.
* **MCP Steroid** — see `mcp-steroid/EULA`.
