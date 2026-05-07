#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$packDir = Split-Path -Parent $PSScriptRoot
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
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
    & $codex.Source mcp remove java-tuning-agent *> $null
    & $codex.Source mcp add java-tuning-agent -- java -jar $resolvedJar
    & $codex.Source mcp list
}
else {
    Write-Host "codex command not found. Add this to $codexHome/config.toml:"
    Write-Host '[mcp_servers.java-tuning-agent]'
    Write-Host 'command = "java"'
    Write-Host ('args = ["-jar", "{0}"]' -f ($resolvedJar -replace '\\', '\\'))
}

Write-Host "Installed Codex skill: $skillDst"
