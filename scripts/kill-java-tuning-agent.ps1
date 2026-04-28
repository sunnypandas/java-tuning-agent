# Stops every Java process whose command line runs a java-tuning-agent*.jar.
# Run from project root: .\kill-java-tuning-agent.ps1

$ErrorActionPreference = 'Stop'

$pattern = 'java-tuning-agent.*\.jar'
$javaNames = @('java.exe', 'javaw.exe')

$targets = Get-CimInstance Win32_Process |
    Where-Object {
        $javaNames -contains $_.Name -and
        $_.CommandLine -and
        $_.CommandLine -match $pattern
    }

if (-not $targets) {
    Write-Host 'No running java-tuning-agent JAR processes found.'
    exit 0
}

foreach ($p in $targets) {
    Write-Host "Stopping PID $($p.ProcessId): $($p.CommandLine)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

Write-Host "Done. Stopped $($targets.Count) process(es)."
