#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectDir,

    [Parameter(Mandatory = $true)]
    [string]$PomPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$packDir = Split-Path -Parent $PSScriptRoot
$resolvedProject = (Resolve-Path -LiteralPath $ProjectDir).Path
$resolvedPom = (Resolve-Path -LiteralPath $PomPath).Path

$cursorDir = Join-Path $resolvedProject '.cursor'
$rulesDir = Join-Path $cursorDir 'rules'
$skillsDir = Join-Path $cursorDir 'skills'
$skillDst = Join-Path $skillsDir 'java-tuning-agent-workflow'

New-Item -ItemType Directory -Force -Path $rulesDir, $skillsDir | Out-Null
Copy-Item -Force -LiteralPath (Join-Path $packDir 'adapters/cursor/rules/java-tuning-agent-mcp.mdc') -Destination $rulesDir
if (Test-Path -LiteralPath $skillDst) {
    Remove-Item -Recurse -Force -LiteralPath $skillDst
}
Copy-Item -Recurse -Force -LiteralPath (Join-Path $packDir 'adapters/cursor/skills/java-tuning-agent-workflow') -Destination $skillDst

$mcp = [ordered]@{
    mcpServers = [ordered]@{
        'java-tuning-agent' = [ordered]@{
            command = 'mvn'
            args = @('-q', '-f', $resolvedPom, '-Pstdio-mcp-dev', 'spring-boot:run')
            env = [ordered]@{}
        }
    }
}
$mcp | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $cursorDir 'mcp.json')

Write-Host "Installed Cursor java-tuning-agent dev adapter into $resolvedProject"
