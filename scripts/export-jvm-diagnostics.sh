#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Export JVM diagnostics for one Java process into a folder (offline analysis / java-tuning-agent bundle).

Usage:
  scripts/export-jvm-diagnostics.sh --export-dir <path> [--process-id <pid>] [--skip-heap-dump]
                                    [--gc-log-path <path>] [--app-log-path <path>]
                                    [--sample-count <n>] [--sample-interval-seconds <n>]
                                    [--skip-repeated-samples]

Options:
  --export-dir <path>              Output directory (created if missing). Required.
  --process-id <pid>               Target JVM PID. If omitted, list JVMs and choose interactively.
  --skip-heap-dump                 Omit GC.heap_dump (no b6-heap-dump.hprof).
  --gc-log-path <path>             Copy a GC log into r1-gc-log.txt.
  --app-log-path <path>            Copy an application log into r2-app-log.txt.
  --sample-count <n>               Repeated readonly samples to export (default: 3).
  --sample-interval-seconds <n>    Seconds between repeated samples (default: 2).
  --skip-repeated-samples          Omit r3-repeated-samples.json.
  -h, --help                       Show this help.
EOF
}

EXPORT_DIR=""
PROCESS_ID=0
SKIP_HEAP_DUMP=0
GC_LOG_PATH=""
APP_LOG_PATH=""
SAMPLE_COUNT=3
SAMPLE_INTERVAL_SECONDS=2
SKIP_REPEATED_SAMPLES=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --export-dir)
      EXPORT_DIR="${2:-}"
      shift 2
      ;;
    --process-id)
      PROCESS_ID="${2:-}"
      shift 2
      ;;
    --skip-heap-dump)
      SKIP_HEAP_DUMP=1
      shift
      ;;
    --gc-log-path)
      GC_LOG_PATH="${2:-}"
      shift 2
      ;;
    --app-log-path)
      APP_LOG_PATH="${2:-}"
      shift 2
      ;;
    --sample-count)
      SAMPLE_COUNT="${2:-}"
      shift 2
      ;;
    --sample-interval-seconds)
      SAMPLE_INTERVAL_SECONDS="${2:-}"
      shift 2
      ;;
    --skip-repeated-samples)
      SKIP_REPEATED_SAMPLES=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$EXPORT_DIR" ]]; then
  echo "--export-dir is required." >&2
  usage >&2
  exit 2
fi

if ! [[ "$PROCESS_ID" =~ ^[0-9]+$ ]]; then
  echo "--process-id must be a non-negative integer." >&2
  exit 2
fi

if ! [[ "$SAMPLE_COUNT" =~ ^[0-9]+$ ]] || (( SAMPLE_COUNT < 1 )); then
  echo "--sample-count must be a positive integer." >&2
  exit 2
fi

if ! [[ "$SAMPLE_INTERVAL_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "--sample-interval-seconds must be a non-negative integer." >&2
  exit 2
fi

resolve_jdk_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/${name}" ]]; then
    echo "${JAVA_HOME}/bin/${name}"
    return 0
  fi
  echo "JDK tool not found: ${name}. Add JDK bin to PATH or set JAVA_HOME." >&2
  exit 1
}

run_capture_checked() {
  local outfile="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    :
  else
    local code=$?
    echo "Command failed (exit ${code}): $*" >&2
    echo "${output}" >&2
    exit 1
  fi
  printf '%s\n' "${output}" >"${outfile}"
}

run_capture_raw() {
  local output=""
  local code=0
  if output="$("$@" 2>&1)"; then
    code=0
  else
    code=$?
  fi
  printf '%s\n' "${code}"
  printf '%s\n' "${output}"
}

filter_jcmd_noise() {
  sed -E '/sun\.tools\.jcmd\.JCmd/I d; /jdk\.jcmd[\/\\]sun\.tools\.jcmd/I d'
}

list_java_vm_entries() {
  local jcmd="$1"
  local jps="$2"
  local data
  data="$(run_capture_raw "$jcmd" -l)"
  local code text
  code="$(printf '%s\n' "${data}" | sed -n '1p')"
  text="$(printf '%s\n' "${data}" | sed '1d')"

  local entries=""
  if [[ "${code}" == "0" && -n "${text//[[:space:]]/}" ]]; then
    entries="$(printf '%s\n' "${text}" | filter_jcmd_noise)"
  fi

  if [[ -z "${entries//[[:space:]]/}" ]]; then
    data="$(run_capture_raw "$jps" -lvm)"
    code="$(printf '%s\n' "${data}" | sed -n '1p')"
    text="$(printf '%s\n' "${data}" | sed '1d')"
    if [[ "${code}" != "0" ]]; then
      echo "Could not list JVM processes." >&2
      echo "jcmd -l failed and jps -lvm failed." >&2
      echo "${text}" >&2
      exit 1
    fi
    entries="$(printf '%s\n' "${text}" | filter_jcmd_noise)"
  fi

  printf '%s\n' "${entries}" | awk '
    /^[[:space:]]*$/ { next }
    {
      pid=$1
      if (pid ~ /^[0-9]+$/) {
        $1=""
        sub(/^[[:space:]]+/, "", $0)
        desc=$0
        if (desc == "") desc="(no description)"
        print pid "\t" desc
      }
    }
  '
}

resolve_interactive_pid() {
  local entries="$1"
  mapfile -t rows < <(printf '%s\n' "${entries}" | sed '/^[[:space:]]*$/d')
  local count="${#rows[@]}"

  if [[ "${count}" -eq 0 ]]; then
    echo "No local Java processes found. Start a JVM as the same user as this shell." >&2
    exit 1
  fi

  if [[ "${count}" -eq 1 ]]; then
    local only_pid only_desc
    only_pid="$(printf '%s' "${rows[0]}" | cut -f1)"
    only_desc="$(printf '%s' "${rows[0]}" | cut -f2-)"
    echo
    echo "Using sole JVM: PID ${only_pid} - ${only_desc}"
    printf '%s\n' "${only_pid}"
    return 0
  fi

  echo
  echo "Java VMs (jcmd -l, or jps -lvm if needed):"
  local i=1
  for row in "${rows[@]}"; do
    local pid desc
    pid="$(printf '%s' "${row}" | cut -f1)"
    desc="$(printf '%s' "${row}" | cut -f2-)"
    printf '  [%-4s] PID %-9s %s\n' "${i}" "${pid}" "${desc}"
    i=$((i + 1))
  done
  echo

  while true; do
    read -r -p "Enter line number [1-n] or target PID: " ans
    ans="${ans// /}"
    if ! [[ "${ans}" =~ ^[0-9]+$ ]]; then
      echo "Enter digits only (index or PID)." >&2
      continue
    fi

    if (( ans >= 1 && ans <= count )); then
      printf '%s\n' "${rows[ans-1]}" | cut -f1
      return 0
    fi

    local matched=""
    for row in "${rows[@]}"; do
      local pid
      pid="$(printf '%s' "${row}" | cut -f1)"
      if [[ "${pid}" == "${ans}" ]]; then
        matched="${pid}"
        break
      fi
    done
    if [[ -n "${matched}" ]]; then
      printf '%s\n' "${matched}"
      return 0
    fi
    echo "No such index or PID in the list." >&2
  done
}

copy_if_provided() {
  local source_path="$1"
  local target_path="$2"
  local absent_path="$3"
  local label="$4"
  if [[ -n "${source_path}" ]]; then
    if [[ ! -f "${source_path}" ]]; then
      echo "${label} path does not exist or is not a regular file: ${source_path}" >&2
      exit 2
    fi
    cp "${source_path}" "${target_path}"
    return 0
  fi
  {
    echo "${label} was not provided to this export script."
    echo "Pass the source file with the corresponding --*-path option if available."
  } >"${absent_path}"
  return 1
}

epoch_ms() {
  python3 -c 'import time; print(int(time.time() * 1000))'
}

collect_native_memory_summary() {
  local output_file="$1"
  local skipped_file="$2"
  local summary_data summary_code baseline_data baseline_code diff_data diff_code
  summary_data="$(run_capture_raw "${jcmd}" "${resolved_pid}" VM.native_memory summary)"
  summary_code="$(printf '%s\n' "${summary_data}" | sed -n '1p')"
  summary_text="$(printf '%s\n' "${summary_data}" | sed '1d')"
  if [[ "${summary_code}" != "0" || -z "${summary_text//[[:space:]]/}" || "${summary_text}" =~ [Nn]ative[[:space:]]+[Mm]emory[[:space:]]+[Tt]racking.*(disabled|not[[:space:]]+enabled) ]]; then
    {
      echo "VM.native_memory summary was not available."
      echo "Start the target JVM with -XX:NativeMemoryTracking=summary to collect this evidence."
      echo
      echo "jcmd output:"
      printf '%s\n' "${summary_text}"
    } >"${skipped_file}"
    return 1
  fi

  baseline_data="$(run_capture_raw "${jcmd}" "${resolved_pid}" VM.native_memory baseline)"
  baseline_code="$(printf '%s\n' "${baseline_data}" | sed -n '1p')"
  sleep 1
  diff_data="$(run_capture_raw "${jcmd}" "${resolved_pid}" VM.native_memory summary.diff)"
  diff_code="$(printf '%s\n' "${diff_data}" | sed -n '1p')"
  diff_text="$(printf '%s\n' "${diff_data}" | sed '1d')"

  {
    echo "VM.native_memory summary"
    printf '%s\n' "${summary_text}"
    if [[ "${baseline_code}" == "0" && "${diff_code}" == "0" && -n "${diff_text//[[:space:]]/}" ]]; then
      echo
      echo "VM.native_memory summary.diff"
      printf '%s\n' "${diff_text}"
    fi
  } >"${output_file}"
  return 0
}

collect_resource_budget() {
  local output_file="$1"
  local rss_bytes=""
  local rss_kb=""
  rss_kb="$(ps -o rss= -p "${resolved_pid}" 2>/dev/null | awk '{print $1}' | tail -n 1 || true)"
  if [[ "${rss_kb}" =~ ^[0-9]+$ ]]; then
    rss_bytes=$((rss_kb * 1024))
  fi

  local container_limit=""
  if [[ -r /sys/fs/cgroup/memory.max ]]; then
    container_limit="$(cat /sys/fs/cgroup/memory.max)"
    [[ "${container_limit}" == "max" ]] && container_limit=""
  elif [[ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]]; then
    container_limit="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)"
  fi

  local cpu_quota=""
  if [[ -r /sys/fs/cgroup/cpu.max ]]; then
    read -r quota period < /sys/fs/cgroup/cpu.max || true
    if [[ "${quota:-}" =~ ^[0-9]+$ && "${period:-}" =~ ^[0-9]+$ && "${period}" -gt 0 ]]; then
      cpu_quota="$(python3 -c 'import sys; print(float(sys.argv[1]) / float(sys.argv[2]))' "${quota}" "${period}")"
    fi
  fi

  {
    echo "# Optional resource-budget evidence for OfflineBundleDraft.backgroundNotes.resourceBudget"
    [[ -n "${container_limit}" ]] && echo "containerMemoryLimitBytes=${container_limit}"
    [[ -n "${rss_bytes}" ]] && echo "processRssBytes=${rss_bytes}"
    [[ -n "${cpu_quota}" ]] && echo "cpuQuotaCores=${cpu_quota}"
  } >"${output_file}"
  return 0
}

collect_repeated_samples() {
  local output_file="$1"
  python3 - "${jcmd}" "${jstat}" "${resolved_pid}" "${SAMPLE_COUNT}" "${SAMPLE_INTERVAL_SECONDS}" "${output_file}" <<'PY'
import json
import re
import subprocess
import sys
import time

jcmd, jstat, pid, sample_count, interval_seconds, output_file = sys.argv[1:]
sample_count = int(sample_count)
interval_seconds = int(interval_seconds)

def run(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return result.returncode, result.stdout

def kb(value):
    return int(value) * 1024

def parse_heap(text):
    memory = {
        "heapUsedBytes": 0,
        "heapCommittedBytes": 0,
        "heapMaxBytes": 0,
    }
    modern = re.search(r"(?i)garbage-first heap\s+total reserved\s+(\d+)K,\s*committed\s+(\d+)K,\s*used\s+(\d+)K", text)
    legacy = re.search(r"(?i)garbage-first heap total\s+(\d+)K,\s*used\s+(\d+)K", text)
    if modern:
        memory["heapMaxBytes"] = kb(modern.group(1))
        memory["heapCommittedBytes"] = kb(modern.group(2))
        memory["heapUsedBytes"] = kb(modern.group(3))
    elif legacy:
        memory["heapCommittedBytes"] = kb(legacy.group(1))
        memory["heapUsedBytes"] = kb(legacy.group(2))
    metaspace = re.search(r"(?i)Metaspace\s+used\s+(\d+)K,\s*committed\s+(\d+)K,\s*reserved\s+(\d+)K", text)
    if metaspace:
        memory["metaspaceUsedBytes"] = kb(metaspace.group(1))
    return memory

def parse_gcutil(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) < 2:
        return {
            "collector": "unknown",
            "youngGcCount": 0,
            "youngGcTimeMs": 0,
            "fullGcCount": 0,
            "fullGcTimeMs": 0,
            "oldUsagePercent": None,
        }
    values = lines[-1].split()
    def as_int(index):
        if len(values) <= index or values[index] == "-":
            return 0
        return int(float(values[index]))
    def seconds_ms(index):
        if len(values) <= index or values[index] == "-":
            return 0
        return int(round(float(values[index]) * 1000.0))
    old = None
    if len(values) > 3 and values[3] != "-":
        old = float(values[3])
    return {
        "collector": "unknown",
        "youngGcCount": as_int(6),
        "youngGcTimeMs": seconds_ms(7),
        "fullGcCount": as_int(8),
        "fullGcTimeMs": seconds_ms(9),
        "oldUsagePercent": old,
    }

def parse_loaded_classes(text):
    after_header = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("Loaded") and "Bytes" in stripped:
            after_header = True
            continue
        if after_header:
            match = re.match(r"(\d+)\s+", stripped)
            if match:
                return int(match.group(1))
    return None

def parse_live_threads(text):
    match = re.search(r"(?m)^java\.threads\.live(?:\s+|\s*=\s*)(\d+)\s*$", text)
    return int(match.group(1)) if match else None

started = int(time.time() * 1000)
samples = []
warnings = []
missing = []
for index in range(sample_count):
    if index > 0 and interval_seconds > 0:
        time.sleep(interval_seconds)
    sampled = int(time.time() * 1000)
    sample_warnings = []
    code_heap, heap_text = run([jcmd, pid, "GC.heap_info"])
    code_gc, gc_text = run([jstat, "-gcutil", pid])
    code_class, class_text = run([jstat, "-class", pid])
    code_perf, perf_text = run([jcmd, pid, "PerfCounter.print"])
    if code_heap != 0:
        sample_warnings.append("GC.heap_info failed")
    if code_gc != 0:
        sample_warnings.append("jstat -gcutil failed")
    if code_class != 0:
        sample_warnings.append("jstat -class failed")
    if code_perf != 0:
        sample_warnings.append("PerfCounter.print failed")
    sample = {
        "sampledAtEpochMs": sampled,
        "memory": parse_heap(heap_text),
        "gc": parse_gcutil(gc_text),
        "threadCount": parse_live_threads(perf_text),
        "loadedClassCount": parse_loaded_classes(class_text),
        "warnings": sample_warnings,
    }
    samples.append(sample)
    warnings.extend(sample_warnings)

if len(samples) < 2:
    missing.append("repeatedTrendAnalysis")

payload = {
    "pid": int(pid),
    "samples": samples,
    "warnings": warnings,
    "missingData": missing,
    "startedAtEpochMs": started,
    "elapsedMs": max(0, int(time.time() * 1000) - started),
}
with open(output_file, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY
}

write_offline_draft_template() {
  local output_file="$1"
  local heap_path=""
  if [[ -f "${root}/b6-heap-dump.hprof" ]]; then
    heap_path="${root}/b6-heap-dump.hprof"
  fi
  local native_path=""
  if [[ -f "${root}/optional-native-memory-summary.txt" ]]; then
    native_path="${root}/optional-native-memory-summary.txt"
  fi
  local gc_log_path=""
  local no_gc_log="true"
  if [[ -f "${root}/r1-gc-log.txt" ]]; then
    gc_log_path="${root}/r1-gc-log.txt"
    no_gc_log="false"
  fi
  local app_log_path=""
  local no_app_log="true"
  if [[ -f "${root}/r2-app-log.txt" ]]; then
    app_log_path="${root}/r2-app-log.txt"
    no_app_log="false"
  fi
  local repeated_path=""
  local no_repeated="true"
  if [[ -f "${root}/r3-repeated-samples.json" ]]; then
    repeated_path="${root}/r3-repeated-samples.json"
    no_repeated="false"
  fi
  python3 - "${output_file}" "${root}" "${heap_path}" "${native_path}" "${gc_log_path}" "${no_gc_log}" "${app_log_path}" "${no_app_log}" "${repeated_path}" "${no_repeated}" <<'PY'
import json
import pathlib
import sys

output_file, root, heap_path, native_path, gc_log_path, no_gc_log, app_log_path, no_app_log, repeated_path, no_repeated = sys.argv[1:]
root_path = pathlib.Path(root)

def read_text(name):
    path = root_path / name
    return path.read_text(encoding="utf-8") if path.exists() else ""

def source(path):
    return {"filePath": path, "inlineText": ""} if path else {"filePath": "", "inlineText": ""}

resource_budget = read_text("optional-resource-budget.txt")
draft = {
    "jvmIdentityText": read_text("b1-jvm-identity.txt"),
    "jdkInfoText": read_text("b2-jdk-vm-version.txt"),
    "runtimeSnapshotText": read_text("b3-runtime-snapshot.txt"),
    "classHistogram": source(str(root_path / "b4-class-histogram.txt")),
    "threadDump": source(str(root_path / "b5-thread-dump.txt")),
    "heapDumpAbsolutePath": heap_path,
    "explicitlyNoGcLog": no_gc_log == "true",
    "explicitlyNoAppLog": no_app_log == "true",
    "explicitlyNoRepeatedSamples": no_repeated == "true",
    "nativeMemorySummary": source(native_path),
    "gcLogPathOrText": gc_log_path,
    "appLogPathOrText": app_log_path,
    "repeatedSamplesPathOrText": repeated_path,
    "backgroundNotes": {"resourceBudget": resource_budget} if resource_budget.strip() else {},
}
with open(output_file, "w", encoding="utf-8") as fh:
    json.dump({"draft": draft, "proceedWithMissingRequired": False}, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY
}

jcmd="$(resolve_jdk_tool jcmd)"
jstat="$(resolve_jdk_tool jstat)"
jps="$(resolve_jdk_tool jps)"

if (( PROCESS_ID < 0 )); then
  echo "ProcessId cannot be negative." >&2
  exit 2
fi

resolved_pid=0
pid_source="interactive"
if (( PROCESS_ID > 0 )); then
  resolved_pid="${PROCESS_ID}"
  pid_source="parameter"
else
  entries="$(list_java_vm_entries "${jcmd}" "${jps}")"
  resolved_pid="$(resolve_interactive_pid "${entries}")"
fi

root="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "${EXPORT_DIR}")"
mkdir -p "${root}"

stamp_local="$(date +"%Y-%m-%dT%H:%M:%S%z")"
stamp_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
host_name="$(hostname)"

cat >"${root}/export-metadata.txt" <<EOF
java-tuning-agent export-jvm-diagnostics
exportedAtLocal: ${stamp_local}
exportedAtUtc:   ${stamp_utc}
processId:       ${resolved_pid}
pidSource:       ${pid_source}
host:            ${host_name}
skipHeapDump:    ${SKIP_HEAP_DUMP}
sampleCount:     ${SAMPLE_COUNT}
sampleIntervalS: ${SAMPLE_INTERVAL_SECONDS}
skipRepeated:    ${SKIP_REPEATED_SAMPLES}
gcLogPath:       ${GC_LOG_PATH}
appLogPath:      ${APP_LOG_PATH}
jcmd:            ${jcmd}
jstat:           ${jstat}
jps:             ${jps}
EOF

# --- B1 ---
run_capture_checked "${root}/b1-jvm-identity.txt" "${jcmd}" "${resolved_pid}" VM.command_line

# --- B2 ---
run_capture_checked "${root}/b2-jdk-vm-version.txt" "${jcmd}" "${resolved_pid}" VM.version

# --- B3 snapshot parts ---
tmp_dir="${root}/.tmp-export"
mkdir -p "${tmp_dir}"
cleanup_tmp() { rm -rf "${tmp_dir}" >/dev/null 2>&1 || true; }
trap cleanup_tmp EXIT

run_capture_checked "${tmp_dir}/vmflags.txt" "${jcmd}" "${resolved_pid}" VM.flags
run_capture_checked "${tmp_dir}/heap_info.txt" "${jcmd}" "${resolved_pid}" GC.heap_info
run_capture_checked "${tmp_dir}/gcutil.txt" "${jstat}" -gcutil "${resolved_pid}"
run_capture_checked "${tmp_dir}/class.txt" "${jstat}" -class "${resolved_pid}"
run_capture_checked "${tmp_dir}/perf.txt" "${jcmd}" "${resolved_pid}" PerfCounter.print

{
  echo "Merged snapshot aligned with java-tuning-agent SafeJvmRuntimeCollector."
  echo "targetPid: ${resolved_pid}    utc: ${stamp_utc}"
  echo
  echo "=== VM.flags ==="
  cat "${tmp_dir}/vmflags.txt"
  echo
  echo "=== GC.heap_info ==="
  cat "${tmp_dir}/heap_info.txt"
  echo
  echo "=== jstat -gcutil ==="
  cat "${tmp_dir}/gcutil.txt"
  echo
  echo "=== jstat -class ==="
  cat "${tmp_dir}/class.txt"
  echo
  echo "=== PerfCounter.print ==="
  cat "${tmp_dir}/perf.txt"
  echo
} >"${root}/b3-runtime-snapshot.txt"

# --- B4 ---
run_capture_checked "${root}/b4-class-histogram.txt" "${jcmd}" "${resolved_pid}" GC.class_histogram

# --- B5 ---
run_capture_checked "${root}/b5-thread-dump.txt" "${jcmd}" "${resolved_pid}" Thread.print

# --- B6 ---
b6_path="${root}/b6-heap-dump.hprof"
if (( SKIP_HEAP_DUMP == 0 )); then
  run_capture_checked "${root}/b6-heap-dump-jcmd-output.txt" "${jcmd}" "${resolved_pid}" GC.heap_dump "${b6_path}"
  if [[ ! -f "${b6_path}" ]]; then
    echo "Warning: GC.heap_dump finished but file missing: ${b6_path} (disk space / permissions?)" >&2
  fi
else
  {
    echo "Heap dump was skipped (--skip-heap-dump)."
    echo "Manual: jcmd ${resolved_pid} GC.heap_dump <absolute-path>.hprof"
  } >"${root}/b6-heap-dump-SKIPPED.txt"
fi

# --- Recommended / enhanced offline evidence ---
copy_if_provided "${GC_LOG_PATH}" "${root}/r1-gc-log.txt" "${root}/r1-gc-log-NOT_COLLECTED.txt" "GC log" || true
copy_if_provided "${APP_LOG_PATH}" "${root}/r2-app-log.txt" "${root}/r2-app-log-NOT_COLLECTED.txt" "application log" || true

if (( SKIP_REPEATED_SAMPLES == 0 )); then
  collect_repeated_samples "${root}/r3-repeated-samples.json"
else
  {
    echo "Repeated samples were skipped (--skip-repeated-samples)."
    echo "Manual: run inspectJvmRuntimeRepeated or rerun this script without --skip-repeated-samples."
  } >"${root}/r3-repeated-samples-SKIPPED.txt"
fi

collect_native_memory_summary "${root}/optional-native-memory-summary.txt" "${root}/optional-native-memory-summary-SKIPPED.txt" || true
collect_resource_budget "${root}/optional-resource-budget.txt"
write_offline_draft_template "${root}/offline-draft-template.json"

echo
echo "Export finished:"
echo "  Directory: ${root}"
echo "  Files:"
ls -1 "${root}" | sed 's/^/    - /'
