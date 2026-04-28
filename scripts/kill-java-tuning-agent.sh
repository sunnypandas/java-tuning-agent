#!/usr/bin/env bash

set -euo pipefail

pattern='java-tuning-agent.*\.jar'

if command -v pgrep >/dev/null 2>&1; then
  mapfile -t targets < <(pgrep -af 'java|javaw' | awk -v re="$pattern" '$0 ~ re { print }')
else
  mapfile -t targets < <(ps -eo pid=,command= | awk -v re="$pattern" '$0 ~ /java|javaw/ && $0 ~ re { print }')
fi

if [[ ${#targets[@]} -eq 0 ]]; then
  echo 'No running java-tuning-agent JAR processes found.'
  exit 0
fi

stopped=0
for line in "${targets[@]}"; do
  pid="$(printf '%s\n' "$line" | awk '{print $1}')"
  cmd="$(printf '%s\n' "$line" | cut -d' ' -f2-)"
  if [[ -z "${pid}" ]]; then
    continue
  fi
  echo "Stopping PID ${pid}: ${cmd}"
  if kill -9 "${pid}" >/dev/null 2>&1; then
    stopped=$((stopped + 1))
  fi
done

echo "Done. Stopped ${stopped} process(es)."
