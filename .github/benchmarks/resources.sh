#!/usr/bin/env bash

# Sourced by the campaign and its tests. Only the JVM enters the memory cgroup;
# /usr/bin/time, k6, jcmd and artifact processing remain outside it.
select_cpus() {
  local permitted=$1 requested=$2 first last cpu range
  local -a ranges cpus=()
  [[ "$requested" =~ ^[1-9][0-9]{0,3}$ ]] || { echo 'JVM_CPUS must be a positive integer' >&2; return 2; }
  IFS=, read -ra ranges <<< "$permitted"
  for range in "${ranges[@]}"; do
    first=${range%-*}
    last=${range##*-}
    for ((cpu=first; cpu<=last; cpu++)); do cpus+=("$cpu"); done
  done
  (( requested < ${#cpus[@]} )) || { echo 'JVM_CPUS must leave at least one permitted CPU for k6' >&2; return 2; }
  server_cpus=$(IFS=,; echo "${cpus[*]:0:requested}")
  client_cpus=$(IFS=,; echo "${cpus[*]:requested}")
  export server_cpus client_cpus
}

configure_resources() {
  jvm_cpus=${JVM_CPUS:-1}
  jvm_memory_mib=${JVM_MEMORY_MIB:-1024}
  if [[ ! "$jvm_memory_mib" =~ ^[1-9][0-9]{0,6}$ ]] || (( jvm_memory_mib <= 256 )); then
    echo 'JVM_MEMORY_MIB must be an integer greater than the fixed 256 MiB heap' >&2
    return 2
  fi
  local available_mib permitted
  available_mib=$(awk '/^MemTotal:/ {print int($2/1024)}' /proc/meminfo)
  (( jvm_memory_mib <= available_mib )) || { echo 'JVM_MEMORY_MIB exceeds host RAM' >&2; return 2; }
  permitted=$(awk '/^Cpus_allowed_list:/ {print $2}' "/proc/$$/status")
  select_cpus "$permitted" "$jvm_cpus" || return
  jvm_memory_bytes=$((jvm_memory_mib * 1024 * 1024))
}

create_server_cgroup() {
  : "${server_cgroup:?Set the per-trial JVM cgroup}"
  [[ -f /sys/fs/cgroup/cgroup.controllers ]] \
    || { echo 'A cgroup v2 memory controller is required' >&2; return 2; }
  sudo -n mkdir "$server_cgroup" || return
  printf '%s\n' "$jvm_memory_bytes" | sudo -n tee "$server_cgroup/memory.max" > /dev/null || return
  printf '0\n' | sudo -n tee "$server_cgroup/memory.swap.max" > /dev/null || return
  [[ $(< "$server_cgroup/memory.max") == "$jvm_memory_bytes" \
    && $(< "$server_cgroup/memory.swap.max") == 0 ]] \
    || { echo 'Requested JVM memory/swap limits were not applied' >&2; return 2; }
}

verify_server_cgroup() {
  local pid=$1
  [[ $(< "/proc/$pid/cgroup") == "0::${server_cgroup#/sys/fs/cgroup}" \
    && $(< "$server_cgroup/memory.max") == "$jvm_memory_bytes" \
    && $(< "$server_cgroup/memory.swap.max") == 0 ]] \
    || { echo 'JVM is not in the requested memory-limited cgroup' >&2; return 2; }
}

capture_server_cgroup() {
  local file
  for file in memory.max memory.swap.max memory.current memory.peak memory.events; do
    printf '%s\n' "$file"
    cat "$server_cgroup/$file" || printf 'Unavailable\n'
  done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  # Move this launcher before exec, so the JVM retains the PID observed by time.
  printf '%s\n' "$$" | sudo -n tee "${1:?Supply the JVM cgroup}/cgroup.procs" > /dev/null || exit
  shift
  exec "$@"
fi
