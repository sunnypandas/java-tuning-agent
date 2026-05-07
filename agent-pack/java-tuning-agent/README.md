# Java Tuning Agent Pack

This is the single distributable agent pack for `java-tuning-agent`.

Release users need two things:

1. `java-tuning-agent-<version>.jar`
2. this pack, usually as `java-tuning-agent-agent-pack-<version>.zip`

The jar exposes MCP tools. This pack teaches clients how to use those tools safely.

## Layout

```text
.codex-plugin/plugin.json
skills/java-tuning-agent-workflow/
adapters/cursor/
adapters/copilot/
mcp/release/
mcp/dev/
scripts/
```

## Codex

Install the skill and register the jar MCP server:

```bash
scripts/install-codex.sh /absolute/path/to/java-tuning-agent-0.1.0.jar
```

```powershell
.\scripts\install-codex.ps1 C:\path\to\java-tuning-agent-0.1.0.jar
```

For source-tree development:

```bash
scripts/install-codex-dev.sh /absolute/path/to/java-tuning-agent/pom.xml
scripts/install-cursor-dev.sh /path/to/project /absolute/path/to/java-tuning-agent/pom.xml
```

```powershell
.\scripts\install-codex-dev.ps1 C:\path\to\java-tuning-agent\pom.xml
.\scripts\install-cursor-dev.ps1 C:\path\to\project C:\path\to\java-tuning-agent\pom.xml
```

## Cursor

Install Cursor rules, skill, and jar MCP config into a project:

```bash
scripts/install-cursor.sh /path/to/project /absolute/path/to/java-tuning-agent-0.1.0.jar
```

```powershell
.\scripts\install-cursor.ps1 C:\path\to\project C:\path\to\java-tuning-agent-0.1.0.jar
```

## GitHub Copilot

Install Copilot instructions and MCP templates into a project:

```bash
scripts/install-copilot.sh /path/to/project /absolute/path/to/java-tuning-agent-0.1.0.jar
```

```powershell
.\scripts\install-copilot.ps1 C:\path\to\project C:\path\to\java-tuning-agent-0.1.0.jar
```

For Copilot CLI user-level MCP registration:

```bash
copilot mcp add java-tuning-agent --type stdio -- java -jar /absolute/path/to/java-tuning-agent-0.1.0.jar
```

## Dev Mode

Dev templates use Maven only for source-tree work and always pass `-q`:

```bash
mvn -q -f /path/to/pom.xml -Pstdio-mcp-dev spring-boot:run
```

Do not publish Maven-based configs as release MCP setup.

See [INSTALL.md](INSTALL.md) for when to use each script.
