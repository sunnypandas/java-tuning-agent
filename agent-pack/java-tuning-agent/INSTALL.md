# Installing the Java Tuning Agent Pack

Use this pack when a client needs workflow guidance in addition to the MCP server jar.

You do **not** need this pack when you only want raw MCP tools. In that case, register the jar directly:

```bash
java -jar /absolute/path/to/java-tuning-agent-0.1.0.jar
```

## When to Use Each Installer

| Client | Use when | macOS/Linux | Windows PowerShell |
|---|---|---|---|
| Codex | You want the installable `java-tuning-agent-workflow` skill and MCP registration. | `scripts/install-codex.sh <jar>` | `.\scripts\install-codex.ps1 <jar>` |
| Codex dev | You are developing this repo from source and want Maven-based MCP. | `scripts/install-codex-dev.sh <pom.xml>` | `.\scripts\install-codex-dev.ps1 <pom.xml>` |
| Cursor | You want Cursor project rules, Cursor skill, and project MCP config. | `scripts/install-cursor.sh <project> <jar>` | `.\scripts\install-cursor.ps1 <project> <jar>` |
| Cursor dev | You are developing this repo from source in Cursor and want Maven-based MCP. | `scripts/install-cursor-dev.sh <project> <pom.xml>` | `.\scripts\install-cursor-dev.ps1 <project> <pom.xml>` |
| GitHub Copilot | You want Copilot instructions and project MCP config. | `scripts/install-copilot.sh <project> <jar>` | `.\scripts\install-copilot.ps1 <project> <jar>` |

## Release Installs

Codex:

```bash
scripts/install-codex.sh /absolute/path/to/java-tuning-agent-0.1.0.jar
```

```powershell
.\scripts\install-codex.ps1 C:\path\to\java-tuning-agent-0.1.0.jar
```

Cursor:

```bash
scripts/install-cursor.sh /path/to/project /absolute/path/to/java-tuning-agent-0.1.0.jar
```

```powershell
.\scripts\install-cursor.ps1 C:\path\to\project C:\path\to\java-tuning-agent-0.1.0.jar
```

GitHub Copilot:

```bash
scripts/install-copilot.sh /path/to/project /absolute/path/to/java-tuning-agent-0.1.0.jar
```

```powershell
.\scripts\install-copilot.ps1 C:\path\to\project C:\path\to\java-tuning-agent-0.1.0.jar
```

## Development Installs

Development mode uses Maven only while working from the source tree. Use it for Codex and Cursor while developing `java-tuning-agent` itself.

```bash
scripts/install-codex-dev.sh /absolute/path/to/java-tuning-agent/pom.xml
scripts/install-cursor-dev.sh /path/to/project /absolute/path/to/java-tuning-agent/pom.xml
```

```powershell
.\scripts\install-codex-dev.ps1 C:\path\to\java-tuning-agent\pom.xml
.\scripts\install-cursor-dev.ps1 C:\path\to\project C:\path\to\java-tuning-agent\pom.xml
```

The dev MCP command includes `-q` intentionally so Maven does not write normal progress logs into stdio MCP.

Cursor dev installs still use the MCP server name `java-tuning-agent`, because the Cursor rule and workflow skill refer to that public server name. Codex dev installs use `java-tuning-agent-dev` so they can coexist with a release entry in `~/.codex/config.toml`.

The Cursor dev installer writes `.cursor/mcp.json` into the target project. In this repository that file is treated as generated local configuration and is ignored by git; keep `.cursor/rules` and `.cursor/skills` as the shareable pieces.

## After Installing

Restart or reload the client so it picks up new skills or instructions.

For Codex, `codex mcp list` should show `java-tuning-agent` enabled. Start a new Codex session and ask for `/java-tuning-agent-workflow`.

For Cursor, reopen the project or reload rules, then use `/java-tuning-agent-workflow` in Agent chat.

For GitHub Copilot, make sure `.github/copilot-instructions.md` and `.github/mcp.json` are committed or present in the project you want Copilot to use.
