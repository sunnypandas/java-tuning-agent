#!/usr/bin/env bash

set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar_pattern='java-tuning-agent.*\.jar'
main_class_pattern='com\.alibaba\.cloud\.ai\.examples\.javatuning\.JavaTuningAgentApplication'

targets=()
if command -v pgrep >/dev/null 2>&1; then
  while IFS= read -r line; do
    targets+=("$line")
  done < <(pgrep -af 'java|javaw|mvn|mvnw' | awk -v jar="$jar_pattern" -v main="$main_class_pattern" -v root="$project_root" '
    $0 ~ jar || $0 ~ main || ($0 ~ /spring-boot:run/ && ($0 ~ /java-tuning-agent/ || index($0, root) > 0)) { print }
  ')
else
  while IFS= read -r line; do
    targets+=("$line")
  done < <(ps -eo pid=,command= | awk -v jar="$jar_pattern" -v main="$main_class_pattern" -v root="$project_root" '
    $0 ~ /java|javaw|mvn|mvnw/ && ($0 ~ jar || $0 ~ main || ($0 ~ /spring-boot:run/ && ($0 ~ /java-tuning-agent/ || index($0, root) > 0))) { print }
  ')
fi

if [[ ${#targets[@]} -eq 0 ]]; then
  echo 'No running java-tuning-agent processes found.'
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
  if kill "${pid}" >/dev/null 2>&1; then
    stopped=$((stopped + 1))
    sleep 1
    if kill -0 "${pid}" >/dev/null 2>&1; then
      kill -9 "${pid}" >/dev/null 2>&1 || true
    fi
  fi
done

echo "Done. Stopped ${stopped} process(es)."
