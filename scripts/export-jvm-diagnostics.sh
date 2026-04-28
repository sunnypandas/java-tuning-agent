#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Export JVM diagnostics for one Java process into a folder (offline analysis / java-tuning-agent bundle).

Usage:
  scripts/export-jvm-diagnostics.sh --export-dir <path> [--process-id <pid>] [--skip-heap-dump]

Options:
  --export-dir <path>   Output directory (created if missing). Required.
  --process-id <pid>    Target JVM PID. If omitted, list JVMs and choose interactively.
  --skip-heap-dump      Omit GC.heap_dump (no b6-heap-dump.hprof).
  -h, --help            Show this help.
EOF
}

EXPORT_DIR=""
PROCESS_ID=0
SKIP_HEAP_DUMP=0

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

echo
echo "Export finished:"
echo "  Directory: ${root}"
echo "  Files:"
ls -1 "${root}" | sed 's/^/    - /'
