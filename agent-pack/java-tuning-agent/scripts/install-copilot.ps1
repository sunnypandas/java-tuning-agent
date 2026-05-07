#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectDir,

    [Parameter(Mandatory = $true)]
    [string]$JarPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$packDir = Split-Path -Parent $PSScriptRoot
$resolvedProject = (Resolve-Path -LiteralPath $ProjectDir).Path
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$githubDir = Join-Path $resolvedProject '.github'

New-Item -ItemType Directory -Force -Path $githubDir | Out-Null
Copy-Item -Force -LiteralPath (Join-Path $packDir 'adapters/copilot/copilot-instructions.md') -Destination (Join-Path $githubDir 'copilot-instructions.md')

$mcp = [ordered]@{
    mcpServers = [ordered]@{
        'java-tuning-agent' = [ordered]@{
            type = 'stdio'
            command = 'java'
            args = @('-jar', $resolvedJar)
            tools = @('*')
            timeout = 600000
        }
    }
}

$json = $mcp | ConvertTo-Json -Depth 8
$json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $githubDir 'mcp.json')
$json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $resolvedProject '.mcp.json')

Write-Host "Installed GitHub Copilot java-tuning-agent adapter into $resolvedProject"
