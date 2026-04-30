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
  Target JVM PID. If omitted, lists JVMs via jcmd -l (fallback jps -lvm) and prompts for index or PID.

.PARAMETER SkipHeapDump
  Omit GC.heap_dump (no b6-heap-dump.hprof).

.PARAMETER GcLogPath
  Optional GC log file to copy into r1-gc-log.txt.

.PARAMETER AppLogPath
  Optional application log file to copy into r2-app-log.txt.

.PARAMETER SampleCount
  Repeated readonly samples to export into r3-repeated-samples.json. Default: 3.

.PARAMETER SampleIntervalSeconds
  Seconds between repeated samples. Default: 2.

.PARAMETER SkipRepeatedSamples
  Omit r3-repeated-samples.json.

.EXAMPLE
  .\scripts\export-jvm-diagnostics.ps1 -ExportDir 'D:\exports\case-001' -ProcessId 12345

.EXAMPLE
  .\scripts\export-jvm-diagnostics.ps1 -ExportDir 'D:\exports\case-001'

.EXAMPLE
  .\scripts\export-jvm-diagnostics.ps1 -ExportDir '.\out\diag' -ProcessId 12345 -SkipHeapDump
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ExportDir,

    [Parameter(Mandatory = $false, HelpMessage = 'Omitted = list jcmd -l / jps -lvm and pick interactively')]
    [int]$ProcessId = 0,

    [switch]$SkipHeapDump,

    [string]$GcLogPath = '',

    [string]$AppLogPath = '',

    [int]$SampleCount = 3,

    [int]$SampleIntervalSeconds = 2,

    [switch]$SkipRepeatedSamples
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

function Invoke-ToolTextRaw {
    param(
        [Parameter(Mandatory = $true)][string]$ExePath,
        [Parameter(Mandatory = $true)][string[]]$ToolArgs
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
    return @{
        Code = $code
        Text = (Convert-NativeLines $stdout)
    }
}

function Parse-JvmListLines {
    param([string]$Text)
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($line in ($Text -split "`r?`n")) {
        $t = $line.TrimEnd()
        if ([string]::IsNullOrWhiteSpace($t)) {
            continue
        }
        if ($t -match '^\s*(\d+)(?:\s+(.+))?$') {
            $desc = if ($Matches[2]) { $Matches[2].Trim() } else { '(no description)' }
            [void]$rows.Add([pscustomobject]@{ Pid = [int]$Matches[1]; Description = $desc })
        }
    }
    return ,$rows.ToArray()
}

function Remove-JcmdNoiseEntries {
    param([object[]]$Entries)
    if ($null -eq $Entries -or $Entries.Count -eq 0) {
        return ,@()
    }
    $filtered = @($Entries | Where-Object {
            $d = $_.Description
            $d -notmatch '(?i)sun\.tools\.jcmd\.JCmd' -and $d -notmatch '(?i)jdk\.jcmd[/\\]sun\.tools\.jcmd'
        })
    return ,$filtered
}

function Get-JavaVmEntries {
    param(
        [Parameter(Mandatory = $true)][string]$JcmdExe,
        [Parameter(Mandatory = $true)][string]$JpsExe
    )
    $r1 = Invoke-ToolTextRaw -ExePath $JcmdExe -ToolArgs @('-l')
    $entries = @()
    if ($r1.Code -eq 0 -and $r1.Text.Trim()) {
        $entries = Remove-JcmdNoiseEntries (Parse-JvmListLines $r1.Text)
    }
    if ($entries.Count -eq 0) {
        $r2 = Invoke-ToolTextRaw -ExePath $JpsExe -ToolArgs @('-lvm')
        if ($r2.Code -ne 0) {
            throw ("Could not list JVM processes.`njcmd -l exit=$($r1.Code)`n$($r1.Text)`n---`njps -lvm exit=$($r2.Code)`n$($r2.Text)")
        }
        if ($r2.Text.Trim()) {
            $entries = Remove-JcmdNoiseEntries (Parse-JvmListLines $r2.Text)
        }
    }
    return ,$entries
}

function Resolve-InteractivePid {
    param([object[]]$Entries)
    if ($null -eq $Entries -or $Entries.Count -eq 0) {
        throw 'No local Java processes found. Start a JVM on this machine, or run this shell as the same user as the target process.'
    }
    if ($Entries.Count -eq 1) {
        $only = $Entries[0]
        Write-Host ''
        Write-Host ('Using sole JVM: PID {0} — {1}' -f $only.Pid, $only.Description) -ForegroundColor Cyan
        return [int]$only.Pid
    }
    Write-Host ''
    Write-Host 'Java VMs (jcmd -l, or jps -lvm if needed):' -ForegroundColor Yellow
    $idx = 1
    foreach ($e in $Entries) {
        Write-Host ('  [{0,-4}] PID {1,-9} {2}' -f $idx, $e.Pid, $e.Description)
        $idx++
    }
    Write-Host ''
    while ($true) {
        $ans = Read-Host 'Enter line number [1-n] or target PID'
        $ans = $ans.Trim()
        if ($ans -notmatch '^\d+$') {
            Write-Host 'Enter digits only (index or PID).' -ForegroundColor Red
            continue
        }
        $n = [int]$ans
        if ($n -ge 1 -and $n -le $Entries.Count) {
            return [int]$Entries[$n - 1].Pid
        }
        $hit = @($Entries | Where-Object { $_.Pid -eq $n })
        if ($hit.Count -eq 1) {
            return [int]$hit[0].Pid
        }
        Write-Host 'No such index or PID in the list.' -ForegroundColor Red
    }
}

function Copy-OptionalArtifact {
    param(
        [string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$TargetPath,
        [Parameter(Mandatory = $true)][string]$AbsentPath,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if (-not [string]::IsNullOrWhiteSpace($SourcePath)) {
        if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
            throw "$Label path does not exist or is not a regular file: $SourcePath"
        }
        Copy-Item -LiteralPath $SourcePath -Destination $TargetPath -Force
        return $true
    }
    @(
        "$Label was not provided to this export script.",
        'Pass the source file with the corresponding -*Path option if available.'
    ) | Set-Content -LiteralPath $AbsentPath -Encoding utf8
    return $false
}

function Get-EpochMilliseconds {
    return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

function Convert-KbToBytes {
    param([string]$Value)
    return [int64]$Value * 1024L
}

function Parse-HeapInfoSample {
    param([string]$Text)
    $memory = [ordered]@{
        heapUsedBytes = 0
        heapCommittedBytes = 0
        heapMaxBytes = 0
    }
    if ($Text -match '(?i)garbage-first heap\s+total reserved\s+(\d+)K,\s*committed\s+(\d+)K,\s*used\s+(\d+)K') {
        $memory.heapMaxBytes = Convert-KbToBytes $Matches[1]
        $memory.heapCommittedBytes = Convert-KbToBytes $Matches[2]
        $memory.heapUsedBytes = Convert-KbToBytes $Matches[3]
    }
    elseif ($Text -match '(?i)garbage-first heap total\s+(\d+)K,\s*used\s+(\d+)K') {
        $memory.heapCommittedBytes = Convert-KbToBytes $Matches[1]
        $memory.heapUsedBytes = Convert-KbToBytes $Matches[2]
    }
    if ($Text -match '(?i)Metaspace\s+used\s+(\d+)K,\s*committed\s+(\d+)K,\s*reserved\s+(\d+)K') {
        $memory.metaspaceUsedBytes = Convert-KbToBytes $Matches[1]
    }
    return $memory
}

function Parse-GcUtilSample {
    param([string]$Text)
    $lines = @($Text -split "`r?`n" | Where-Object { $_.Trim() })
    if ($lines.Count -lt 2) {
        return [ordered]@{
            collector = 'unknown'
            youngGcCount = 0
            youngGcTimeMs = 0
            fullGcCount = 0
            fullGcTimeMs = 0
            oldUsagePercent = $null
        }
    }
    $values = @($lines[-1].Trim() -split '\s+')
    function Read-LongAt([int]$Index) {
        if ($values.Count -le $Index -or $values[$Index] -eq '-') { return 0L }
        return [int64][double]$values[$Index]
    }
    function Read-MillisAt([int]$Index) {
        if ($values.Count -le $Index -or $values[$Index] -eq '-') { return 0L }
        return [int64][Math]::Round(([double]$values[$Index]) * 1000.0)
    }
    $old = $null
    if ($values.Count -gt 3 -and $values[3] -ne '-') {
        $old = [double]$values[3]
    }
    return [ordered]@{
        collector = 'unknown'
        youngGcCount = Read-LongAt 6
        youngGcTimeMs = Read-MillisAt 7
        fullGcCount = Read-LongAt 8
        fullGcTimeMs = Read-MillisAt 9
        oldUsagePercent = $old
    }
}

function Parse-LoadedClassCount {
    param([string]$Text)
    $afterHeader = $false
    foreach ($line in ($Text -split "`r?`n")) {
        $t = $line.Trim()
        if ($t.StartsWith('Loaded') -and $t.Contains('Bytes')) {
            $afterHeader = $true
            continue
        }
        if ($afterHeader -and $t -match '^(\d+)\s+') {
            return [int64]$Matches[1]
        }
    }
    return $null
}

function Parse-LiveThreadCount {
    param([string]$Text)
    if ($Text -match '(?m)^java\.threads\.live(?:\s+|\s*=\s*)(\d+)\s*$') {
        return [int64]$Matches[1]
    }
    return $null
}

function Export-RepeatedSamples {
    param([Parameter(Mandatory = $true)][string]$OutFile)
    $started = Get-EpochMilliseconds
    $samples = New-Object System.Collections.Generic.List[object]
    $warnings = New-Object System.Collections.Generic.List[string]
    $missing = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $SampleCount; $i++) {
        if ($i -gt 0 -and $SampleIntervalSeconds -gt 0) {
            Start-Sleep -Seconds $SampleIntervalSeconds
        }
        $sampleWarnings = New-Object System.Collections.Generic.List[string]
        $heap = Invoke-ToolTextRaw -ExePath $jcmd -ToolArgs @($pidStr, 'GC.heap_info')
        $gc = Invoke-ToolTextRaw -ExePath $jstat -ToolArgs @('-gcutil', $pidStr)
        $class = Invoke-ToolTextRaw -ExePath $jstat -ToolArgs @('-class', $pidStr)
        $perf = Invoke-ToolTextRaw -ExePath $jcmd -ToolArgs @($pidStr, 'PerfCounter.print')
        if ($heap.Code -ne 0) { [void]$sampleWarnings.Add('GC.heap_info failed') }
        if ($gc.Code -ne 0) { [void]$sampleWarnings.Add('jstat -gcutil failed') }
        if ($class.Code -ne 0) { [void]$sampleWarnings.Add('jstat -class failed') }
        if ($perf.Code -ne 0) { [void]$sampleWarnings.Add('PerfCounter.print failed') }
        foreach ($warning in $sampleWarnings) {
            [void]$warnings.Add($warning)
        }
        [void]$samples.Add([ordered]@{
                sampledAtEpochMs = Get-EpochMilliseconds
                memory = Parse-HeapInfoSample $heap.Text
                gc = Parse-GcUtilSample $gc.Text
                threadCount = Parse-LiveThreadCount $perf.Text
                loadedClassCount = Parse-LoadedClassCount $class.Text
                warnings = @($sampleWarnings)
            })
    }
    if ($samples.Count -lt 2) {
        [void]$missing.Add('repeatedTrendAnalysis')
    }
    $payload = [ordered]@{
        pid = $resolvedPid
        samples = @($samples)
        warnings = @($warnings)
        missingData = @($missing)
        startedAtEpochMs = $started
        elapsedMs = [Math]::Max(0, (Get-EpochMilliseconds) - $started)
    }
    $payload | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutFile -Encoding utf8
}

function Export-NativeMemorySummary {
    param(
        [Parameter(Mandatory = $true)][string]$OutFile,
        [Parameter(Mandatory = $true)][string]$SkippedFile
    )
    $summary = Invoke-ToolTextRaw -ExePath $jcmd -ToolArgs @($pidStr, 'VM.native_memory', 'summary')
    if ($summary.Code -ne 0 -or [string]::IsNullOrWhiteSpace($summary.Text) -or $summary.Text -match '(?i)Native Memory Tracking.*(disabled|not enabled)') {
        @(
            'VM.native_memory summary was not available.',
            'Start the target JVM with -XX:NativeMemoryTracking=summary to collect this evidence.',
            '',
            'jcmd output:',
            $summary.Text
        ) | Set-Content -LiteralPath $SkippedFile -Encoding utf8
        return $false
    }
    $baseline = Invoke-ToolTextRaw -ExePath $jcmd -ToolArgs @($pidStr, 'VM.native_memory', 'baseline')
    Start-Sleep -Seconds 1
    $diff = Invoke-ToolTextRaw -ExePath $jcmd -ToolArgs @($pidStr, 'VM.native_memory', 'summary.diff')
    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add('VM.native_memory summary')
    [void]$lines.Add($summary.Text.TrimEnd())
    if ($baseline.Code -eq 0 -and $diff.Code -eq 0 -and -not [string]::IsNullOrWhiteSpace($diff.Text)) {
        [void]$lines.Add('')
        [void]$lines.Add('VM.native_memory summary.diff')
        [void]$lines.Add($diff.Text.TrimEnd())
    }
    $lines | Set-Content -LiteralPath $OutFile -Encoding utf8
    return $true
}

function Export-ResourceBudget {
    param([Parameter(Mandatory = $true)][string]$OutFile)
    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add('# Optional resource-budget evidence for OfflineBundleDraft.backgroundNotes.resourceBudget')
    try {
        $proc = Get-Process -Id $resolvedPid -ErrorAction Stop
        [void]$lines.Add("processRssBytes=$($proc.WorkingSet64)")
    }
    catch {
        [void]$lines.Add('# processRssBytes unavailable')
    }
    $lines | Set-Content -LiteralPath $OutFile -Encoding utf8
}

function New-ArtifactSource {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return [ordered]@{ filePath = ''; inlineText = '' }
    }
    return [ordered]@{ filePath = $Path; inlineText = '' }
}

function Read-TextOrEmpty {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        return [System.IO.File]::ReadAllText($Path)
    }
    return ''
}

function Write-OfflineDraftTemplate {
    param([Parameter(Mandatory = $true)][string]$OutFile)
    $heapPath = ''
    $heapCandidate = Join-Path $root 'b6-heap-dump.hprof'
    if (Test-Path -LiteralPath $heapCandidate -PathType Leaf) {
        $heapPath = [System.IO.Path]::GetFullPath($heapCandidate)
    }
    $nativePath = ''
    $nativeCandidate = Join-Path $root 'optional-native-memory-summary.txt'
    if (Test-Path -LiteralPath $nativeCandidate -PathType Leaf) {
        $nativePath = [System.IO.Path]::GetFullPath($nativeCandidate)
    }
    $gcLogOut = Join-Path $root 'r1-gc-log.txt'
    $appLogOut = Join-Path $root 'r2-app-log.txt'
    $repeatedOut = Join-Path $root 'r3-repeated-samples.json'
    $resourceBudget = Read-TextOrEmpty (Join-Path $root 'optional-resource-budget.txt')
    $draft = [ordered]@{
        jvmIdentityText = Read-TextOrEmpty (Join-Path $root 'b1-jvm-identity.txt')
        jdkInfoText = Read-TextOrEmpty (Join-Path $root 'b2-jdk-vm-version.txt')
        runtimeSnapshotText = Read-TextOrEmpty (Join-Path $root 'b3-runtime-snapshot.txt')
        classHistogram = New-ArtifactSource ([System.IO.Path]::GetFullPath((Join-Path $root 'b4-class-histogram.txt')))
        threadDump = New-ArtifactSource ([System.IO.Path]::GetFullPath((Join-Path $root 'b5-thread-dump.txt')))
        heapDumpAbsolutePath = $heapPath
        explicitlyNoGcLog = -not (Test-Path -LiteralPath $gcLogOut -PathType Leaf)
        explicitlyNoAppLog = -not (Test-Path -LiteralPath $appLogOut -PathType Leaf)
        explicitlyNoRepeatedSamples = -not (Test-Path -LiteralPath $repeatedOut -PathType Leaf)
        nativeMemorySummary = New-ArtifactSource $nativePath
        gcLogPathOrText = $(if (Test-Path -LiteralPath $gcLogOut -PathType Leaf) { [System.IO.Path]::GetFullPath($gcLogOut) } else { '' })
        appLogPathOrText = $(if (Test-Path -LiteralPath $appLogOut -PathType Leaf) { [System.IO.Path]::GetFullPath($appLogOut) } else { '' })
        repeatedSamplesPathOrText = $(if (Test-Path -LiteralPath $repeatedOut -PathType Leaf) { [System.IO.Path]::GetFullPath($repeatedOut) } else { '' })
        backgroundNotes = $(if ($resourceBudget.Trim()) { @{ resourceBudget = $resourceBudget } } else { @{} })
    }
    [ordered]@{
        draft = $draft
        proceedWithMissingRequired = $false
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutFile -Encoding utf8
}

$jcmd = Resolve-JdkTool -Name 'jcmd'
$jstat = Resolve-JdkTool -Name 'jstat'
$jps = Resolve-JdkTool -Name 'jps'

if ($ProcessId -lt 0) {
    throw 'ProcessId cannot be negative.'
}
if ($SampleCount -lt 1) {
    throw 'SampleCount must be a positive integer.'
}
if ($SampleIntervalSeconds -lt 0) {
    throw 'SampleIntervalSeconds must be zero or greater.'
}
$resolvedPid = 0
if ($ProcessId -gt 0) {
    $resolvedPid = $ProcessId
}
else {
    $vmEntries = Get-JavaVmEntries -JcmdExe $jcmd -JpsExe $jps
    $resolvedPid = Resolve-InteractivePid -Entries $vmEntries
}

$pidStr = "$resolvedPid"

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
processId:       $resolvedPid
pidSource:       $(if ($ProcessId -gt 0) { 'parameter' } else { 'interactive' })
host:            $env:COMPUTERNAME
skipHeapDump:    $SkipHeapDump
sampleCount:     $SampleCount
sampleIntervalS: $SampleIntervalSeconds
skipRepeated:    $SkipRepeatedSamples
gcLogPath:       $GcLogPath
appLogPath:      $AppLogPath
jcmd:            $jcmd
jstat:           $jstat
jps:             $jps
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
    $parts += ('targetPid: ' + $resolvedPid + '    utc: ' + $stampUtc)
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

# --- Recommended / enhanced offline evidence ---
[void](Copy-OptionalArtifact -SourcePath $GcLogPath -TargetPath (Join-Path $root 'r1-gc-log.txt') -AbsentPath (Join-Path $root 'r1-gc-log-NOT_COLLECTED.txt') -Label 'GC log')
[void](Copy-OptionalArtifact -SourcePath $AppLogPath -TargetPath (Join-Path $root 'r2-app-log.txt') -AbsentPath (Join-Path $root 'r2-app-log-NOT_COLLECTED.txt') -Label 'application log')

if (-not $SkipRepeatedSamples) {
    Export-RepeatedSamples -OutFile (Join-Path $root 'r3-repeated-samples.json')
}
else {
    Set-Content -LiteralPath (Join-Path $root 'r3-repeated-samples-SKIPPED.txt') -Encoding utf8 -Value @(
        'Repeated samples were skipped (-SkipRepeatedSamples).',
        'Manual: run inspectJvmRuntimeRepeated or rerun this script without -SkipRepeatedSamples.'
    )
}

[void](Export-NativeMemorySummary -OutFile (Join-Path $root 'optional-native-memory-summary.txt') -SkippedFile (Join-Path $root 'optional-native-memory-summary-SKIPPED.txt'))
Export-ResourceBudget -OutFile (Join-Path $root 'optional-resource-budget.txt')
Write-OfflineDraftTemplate -OutFile (Join-Path $root 'offline-draft-template.json')

Write-Host ''
Write-Host 'Export finished:' -ForegroundColor Green
Write-Host "  Directory: $root"
Write-Host '  Files:'
Get-ChildItem -LiteralPath $root -File | Sort-Object Name | ForEach-Object { Write-Host ('    - ' + $_.Name) }
