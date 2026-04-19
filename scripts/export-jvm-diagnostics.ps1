#Requires -Version 5.1
<#
.SYNOPSIS
  Export JVM diagnostics for one Java process into a folder (offline analysis / java-tuning-agent bundle).

.DESCRIPTION
  Writes files mapped to OfflineBundleDraft B1-B6 (names below).
  Uses the same jcmd/jstat commands as SafeJvmRuntimeCollector in this repo.

  Requires jcmd and jstat on PATH or under JAVA_HOME\bin.
  You must run as the same Windows user as the target JVM (or elevated) so jcmd can attach.

.PARAMETER ExportDir
  Output directory (created if missing).

.PARAMETER ProcessId
  Target JVM PID (decimal). Discover with jcmd / jps -lvm.

.PARAMETER SkipHeapDump
  Omit GC.heap_dump (no b6-heap-dump.hprof).

.EXAMPLE
  .\scripts\export-jvm-diagnostics.ps1 -ExportDir 'D:\exports\case-001' -ProcessId 12345

.EXAMPLE
  .\scripts\export-jvm-diagnostics.ps1 -ExportDir '.\out\diag' -ProcessId 12345 -SkipHeapDump
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ExportDir,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$ProcessId,

    [switch]$SkipHeapDump
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-JdkTool {
    param([Parameter(Mandatory = $true)][string]$Name)
    $exeName = if ($Name -eq 'jcmd' -or $Name -eq 'jstat') { "$Name.exe" } else { $Name }
    $found = Get-Command $Name -ErrorAction SilentlyContinue
    if ($found) {
        return $found.Source
    }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin' | Join-Path -ChildPath $exeName
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }
    throw "JDK tool not found: $Name. Add JDK bin to PATH or set JAVA_HOME."
}

function Convert-NativeLines {
    param([object]$NativeOutput)
    if ($null -eq $NativeOutput) {
        return ''
    }
    $lines = @($NativeOutput)
    $sb = New-Object System.Text.StringBuilder
    foreach ($ln in $lines) {
        if ($null -eq $ln) {
            continue
        }
        if ($ln -is [System.Management.Automation.ErrorRecord]) {
            [void]$sb.AppendLine($ln.Exception.Message.TrimEnd())
        }
        else {
            [void]$sb.AppendLine(([string]$ln).TrimEnd())
        }
    }
    return $sb.ToString().TrimEnd()
}

function Save-ToolOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExePath,
        [Parameter(Mandatory = $true)]
        [string[]]$ToolArgs,
        [Parameter(Mandatory = $true)]
        [string]$OutFile
    )
    $priorEa = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $stdout = & $ExePath @ToolArgs 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $priorEa
    }
    $text = Convert-NativeLines $stdout
    if ($code -ne 0) {
        $cmdLine = "$ExePath $($ToolArgs -join ' ')"
        throw ("Command failed (exit $code): $cmdLine" + [Environment]::NewLine + $text)
    }
    [System.IO.File]::WriteAllText($OutFile, $text.TrimEnd() + "`r`n", [System.Text.UTF8Encoding]::new($false))
}

function Add-Section {
    param([string]$Title, [string]$Body)
    if ($null -eq $Body) { $Body = '' }
    return "`r`n=== $Title ===`r`n" + ($Body.TrimEnd() + "`r`n")
}

$jcmd = Resolve-JdkTool -Name 'jcmd'
$jstat = Resolve-JdkTool -Name 'jstat'
$pidStr = "$ProcessId"

$root = $ExportDir.Trim()
if (-not [System.IO.Path]::IsPathRooted($root)) {
    $root = Join-Path (Get-Location).Path $root
}
$root = [System.IO.Path]::GetFullPath($root)
New-Item -ItemType Directory -Force -Path $root | Out-Null

$stamp = Get-Date
$stampUtc = $stamp.ToUniversalTime().ToString('o')

$metaFile = Join-Path $root 'export-metadata.txt'
@"
java-tuning-agent export-jvm-diagnostics
exportedAtLocal: $($stamp.ToString('o'))
exportedAtUtc:   $stampUtc
processId:       $ProcessId
host:            $env:COMPUTERNAME
skipHeapDump:    $SkipHeapDump
jcmd:            $jcmd
jstat:           $jstat
"@ | Set-Content -LiteralPath $metaFile -Encoding utf8

# --- B1 ---
$b1 = Join-Path $root 'b1-jvm-identity.txt'
Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'VM.command_line') -OutFile $b1

# --- B2 ---
$b2 = Join-Path $root 'b2-jdk-vm-version.txt'
Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'VM.version') -OutFile $b2

# --- B3 snapshot parts ---
$tmpDir = Join-Path $root '.tmp-export'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
try {
    $fFlags = Join-Path $tmpDir 'vmflags.txt'
    $fHeap = Join-Path $tmpDir 'heap_info.txt'
    $fGcUtil = Join-Path $tmpDir 'gcutil.txt'
    $fClass = Join-Path $tmpDir 'class.txt'
    $fPerf = Join-Path $tmpDir 'perf.txt'

    Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'VM.flags') -OutFile $fFlags
    Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'GC.heap_info') -OutFile $fHeap
    Save-ToolOutput -ExePath $jstat -ToolArgs @('-gcutil', $pidStr) -OutFile $fGcUtil
    Save-ToolOutput -ExePath $jstat -ToolArgs @('-class', $pidStr) -OutFile $fClass
    Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'PerfCounter.print') -OutFile $fPerf

    $b3 = Join-Path $root 'b3-runtime-snapshot.txt'
    $parts = @()
    $parts += 'Merged snapshot aligned with java-tuning-agent SafeJvmRuntimeCollector.'
    $parts += ('targetPid: ' + $ProcessId + '    utc: ' + $stampUtc)
    $parts += Add-Section 'VM.flags' ([System.IO.File]::ReadAllText($fFlags))
    $parts += Add-Section 'GC.heap_info' ([System.IO.File]::ReadAllText($fHeap))
    $parts += Add-Section 'jstat -gcutil' ([System.IO.File]::ReadAllText($fGcUtil))
    $parts += Add-Section 'jstat -class' ([System.IO.File]::ReadAllText($fClass))
    $parts += Add-Section 'PerfCounter.print' ([System.IO.File]::ReadAllText($fPerf))
    [System.IO.File]::WriteAllText($b3, ($parts -join ''), [System.Text.UTF8Encoding]::new($false))
}
finally {
    Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}

# --- B4 ---
$b4 = Join-Path $root 'b4-class-histogram.txt'
Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'GC.class_histogram') -OutFile $b4

# --- B5 ---
$b5 = Join-Path $root 'b5-thread-dump.txt'
Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'Thread.print') -OutFile $b5

# --- B6 ---
$b6Path = Join-Path $root 'b6-heap-dump.hprof'
if (-not $SkipHeapDump) {
    $absHeap = [System.IO.Path]::GetFullPath($b6Path)
    $heapLog = Join-Path $root 'b6-heap-dump-jcmd-output.txt'
    Save-ToolOutput -ExePath $jcmd -ToolArgs @($pidStr, 'GC.heap_dump', $absHeap) -OutFile $heapLog
    if (-not (Test-Path -LiteralPath $absHeap)) {
        Write-Warning "GC.heap_dump finished but file missing: $absHeap (disk space / permissions?)"
    }
}
else {
    Set-Content -LiteralPath (Join-Path $root 'b6-heap-dump-SKIPPED.txt') -Encoding utf8 -Value @(
        'Heap dump was skipped (-SkipHeapDump).',
        "Manual: jcmd $pidStr GC.heap_dump <absolute-path>.hprof"
    )
}

Write-Host ''
Write-Host 'Export finished:' -ForegroundColor Green
Write-Host "  Directory: $root"
Write-Host '  Files:'
Get-ChildItem -LiteralPath $root -File | Sort-Object Name | ForEach-Object { Write-Host ('    - ' + $_.Name) }
