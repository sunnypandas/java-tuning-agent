# Stops java-tuning-agent MCP processes started from a jar, main class, or spring-boot:run.
# Run from project root: .\scripts\kill-java-tuning-agent.ps1

$ErrorActionPreference = 'Stop'

$jarPattern = 'java-tuning-agent.*\.jar'
$mainClassPattern = 'com\.alibaba\.cloud\.ai\.examples\.javatuning\.JavaTuningAgentApplication'
$springBootRunPattern = 'spring-boot:run'
$javaNames = @('java.exe', 'javaw.exe')

$targets = Get-CimInstance Win32_Process |
    Where-Object {
        $javaNames -contains $_.Name -and
        $_.CommandLine -and
        (
            $_.CommandLine -match $jarPattern -or
            $_.CommandLine -match $mainClassPattern -or
            ($_.CommandLine -match $springBootRunPattern -and $_.CommandLine -match 'java-tuning-agent')
        )
    }

if (-not $targets) {
    Write-Host 'No running java-tuning-agent processes found.'
    exit 0
}

foreach ($p in $targets) {
    Write-Host "Stopping PID $($p.ProcessId): $($p.CommandLine)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

Write-Host "Done. Stopped $($targets.Count) process(es)."
