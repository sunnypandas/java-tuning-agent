#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PomPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$packDir = Split-Path -Parent $PSScriptRoot
$resolvedPom = (Resolve-Path -LiteralPath $PomPath).Path
$codexHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $HOME '.codex' }
$skillsDir = Join-Path $codexHome 'skills'
$skillDst = Join-Path $skillsDir 'java-tuning-agent-workflow'
$skillSrc = Join-Path $packDir 'skills/java-tuning-agent-workflow'

New-Item -ItemType Directory -Force -Path $skillsDir | Out-Null
if (Test-Path -LiteralPath $skillDst) {
    Remove-Item -Recurse -Force -LiteralPath $skillDst
}
Copy-Item -Recurse -Force -LiteralPath $skillSrc -Destination $skillDst

$codex = Get-Command codex -ErrorAction SilentlyContinue
if ($codex) {
    & $codex.Source mcp remove java-tuning-agent-dev *> $null
    & $codex.Source mcp add java-tuning-agent-dev -- mvn -q -f $resolvedPom -Pstdio-mcp-dev spring-boot:run
    & $codex.Source mcp list
    Write-Host 'Installed Codex dev MCP server: java-tuning-agent-dev'
}
else {
    Write-Host 'codex command not found. Skill was installed, but MCP was not registered.'
    Write-Host "Add this to $codexHome/config.toml manually if needed:"
    Write-Host '[mcp_servers.java-tuning-agent-dev]'
    Write-Host 'command = "mvn"'
    Write-Host ('args = ["-q", "-f", "{0}", "-Pstdio-mcp-dev", "spring-boot:run"]' -f ($resolvedPom -replace '\\', '\\'))
}

Write-Host "Installed Codex skill: $skillDst"
