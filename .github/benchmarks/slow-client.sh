#!/usr/bin/env bash
set -euo pipefail

[[ ${JVM_CPUS:-1} == 1 && ${JVM_MEMORY_MIB:-1024} == 1024 ]] \
  || { echo 'Slow-client diagnostics require 1 CPU, 1 GiB total memory and 256 MiB heap' >&2; exit 2; }
[[ $# == 2 ]] || { echo 'Supply a new result directory and prepared tools directory' >&2; exit 2; }
destination=$1
tools=$2
[[ ! -e "$destination" ]] || { echo 'Refusing to overwrite results' >&2; exit 2; }
mkdir -- "$destination"
destination=$(realpath "$destination")
printf 'FAILED\n' > "$destination/outcome.txt"
[[ -x "$tools/k6" && -d "$tools/lib" ]] \
  || { echo 'Missing prepared benchmark tools' >&2; exit 2; }
: "${JAVA_HOME:?Set JAVA_HOME}"
tools=$(realpath "$tools")
support=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source-path=SCRIPTDIR
# shellcheck source=resources.sh
source "$support/resources.sh"
for command in slowhttptest jq node curl taskset setsid top timeout; do
  command -v "$command" > /dev/null
done
configure_resources
base=http://127.0.0.1:18080
server_cgroup="/sys/fs/cgroup/shoostr-slow-client-$$"
server_group=''
server_pid=''
health_group=''
health_pid=''
load_group=''
load_pid=''
monitor_pid=''
recording=0
finished=0

stop_group() {
  local group=$1 native_pid=$2 attempt
  [[ -n "$group" ]] || return 0
  if [[ -n "$native_pid" ]]; then
    kill -TERM "$native_pid" 2>/dev/null || true
  else
    kill -TERM -- "-$group" 2>/dev/null || true
  fi
  for ((attempt=0; attempt<50; attempt++)); do
    if ! kill -0 "$group" 2>/dev/null; then break; fi
    sleep 0.1
  done
  kill -KILL -- "-$group" 2>/dev/null || true
  wait "$group" 2>/dev/null || true
}

stop_monitor() {
  if [[ -n "$monitor_pid" ]]; then
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
    monitor_pid=''
  fi
}

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  stop_monitor
  stop_group "$load_group" "$load_pid"
  stop_group "$health_group" "$health_pid"
  if [[ "$recording" == 1 ]]; then
    timeout 10s "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.stop name=slow-client \
      filename="$destination/server.jfr" > "$destination/jfr-stop.txt" 2>&1 || status=1
    "$JAVA_HOME/bin/jfr" print --events jdk.JavaErrorThrow "$destination/server.jfr" \
      > "$destination/final-java-errors.txt" 2>&1 || status=1
  fi
  stop_group "$server_group" "$server_pid"
  if [[ -d "$server_cgroup" ]]; then
    capture_server_cgroup > "$destination/server-cgroup-after.txt"
    cp "$server_cgroup/memory.events" "$destination/final-memory.events" || status=1
    # Use the same OOM invariant as the per-cycle verifier, including events after shutdown.
    node --input-type=module - "$support/slow-client-check.mjs" \
      "$destination/final-memory.events" "$destination/server.log" \
      "$destination/final-java-errors.txt" <<'NODE' || status=1
import fs from 'node:fs';
const { verifyMemory } = await import(process.argv[2]);
const errors = process.argv.slice(4).map(file => fs.readFileSync(file, 'utf8')).join('\n');
verifyMemory(fs.readFileSync(process.argv[3], 'utf8'), errors);
NODE
    sudo -n rmdir "$server_cgroup" || status=1
  fi
  if [[ "$status" == 0 && "$finished" == 1 ]]; then
    printf 'PASS\n' > "$destination/outcome.txt"
  else
    printf 'FAILED\n' > "$destination/outcome.txt"
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

now_ms() { date +%s%3N; }
control() { curl --silent --show-error --fail --max-time 2 -X POST "$base/$1"; }

snapshot() {
  local label=$1
  timeout 10s "$JAVA_HOME/bin/jcmd" "$server_pid" GC.heap_info > "$run_dir/$label-heap.txt"
  timeout 10s "$JAVA_HOME/bin/jcmd" "$server_pid" Thread.dump_to_file -format=json \
    "$run_dir/$label-threads.json" > "$run_dir/$label-thread-command.txt"
  cat "/proc/$server_pid/status" > "$run_dir/$label-status.txt"
  cat "/proc/$server_pid/stat" > "$run_dir/$label-stat.txt"
  ls -l "/proc/$server_pid/fd" > "$run_dir/$label-fds.txt"
  capture_server_cgroup > "$run_dir/$label-cgroup.txt"
}

sample() {
  local phase=$1 timestamp state fd_count
  state=$(curl --silent --show-error --fail --max-time 2 "$base/state")
  timestamp=$(now_ms)
  jq -ce --arg phase "$phase" --argjson timestamp "$timestamp" \
    '. + {phase:$phase,timestamp_ms:$timestamp}' <<< "$state" >> "$run_dir/state-samples.jsonl"
  fd_count=$(find "/proc/$server_pid/fd" -mindepth 1 -maxdepth 1 -printf '.\n' | wc -l)
  {
    printf 'timestamp_ms=%s phase=%s fd_count=%s memory_current=%s\n' \
      "$timestamp" "$phase" "$fd_count" "$(< "$server_cgroup/memory.current")"
    awk '/^(VmRSS|VmSize|Threads):/' "/proc/$server_pid/status"
  } >> "$run_dir/resource-samples.txt"
  if awk '$1 ~ /^oom/ && $2 > 0 {found=1} END {exit !found}' "$server_cgroup/memory.events" \
      || grep -qE '\bOutOfMemoryError\b' "$destination/server.log" \
      || grep -q 'health-failure' "$run_dir/health-client.log"; then
    echo 'Safety or healthy-probe failure; stopping the campaign' >&2
    return 1
  fi
}

native_pid() {
  local group=$1 name=$2 pid attempt
  for ((attempt=0; attempt<100; attempt++)); do
    pid=$(pgrep -P "$group" -x "$name" || true)
    if [[ "$pid" =~ ^[0-9]+$ ]]; then printf '%s\n' "$pid"; return 0; fi
    kill -0 "$group" 2>/dev/null || break
    sleep 0.1
  done
  echo "Cannot identify owned $name process" >&2
  return 1
}

jq -n --arg revision "$(git rev-parse HEAD)" --arg image "${ImageVersion:-unknown}" \
  --arg image_os "${ImageOS:-unknown}" \
  '{revision:$revision,image:$image,image_os:$image_os,jvm_cpus:1,jvm_memory_mib:1024,
    heap_mib:256,collector:"G1",swap_bytes:0,clients:[32,128],repeats:2,
    load_seconds:60,recovery_seconds:30,health_probe_target:100,health_probe_boundary_allowance:1,
    body_arguments:["-B","-r N","-l 60","-i 1","-s 65536","-x 8","-t POST"],
    blocked_wait_seconds:5,native_policy:"defaults; recorded without mutation"}' \
  > "$destination/configuration.json"
"$JAVA_HOME/bin/java" -version > "$destination/java.txt" 2>&1
"$tools/k6" version > "$destination/k6.txt"
# Native 1.9.0 prints its version/help and returns 1 for -h; -v is a verbosity argument.
status=0
slowhttptest -h > "$destination/slowhttptest.txt" 2>&1 || status=$?
[[ "$status" == 1 ]]
grep -q 'version 1.9.0' "$destination/slowhttptest.txt"
{
  uname -a
  lscpu
  free -m
  df -h "$destination"
  cat "/proc/$$/cgroup"
} > "$destination/host.txt"
sha256sum "$tools/k6" "$tools"/lib/*.jar "$(command -v slowhttptest)" \
  "$support/slow-client.sh" "$support/slow-client.js" "$support/slow-client-check.mjs" \
  "$support/resources.sh" > "$destination/sha256.txt"
printf 'SERVER_CPUS=%s\nCLIENT_CPUS=%s\n' "$server_cpus" "$client_cpus" > "$destination/affinity.txt"
create_server_cgroup
setsid /usr/bin/time -v -o "$destination/server-resource.txt" \
  bash "$support/resources.sh" "$server_cgroup" \
  taskset -c "$server_cpus" "$JAVA_HOME/bin/java" -Xms256m -Xmx256m -XX:+UseG1GC \
  -cp "$tools/lib/*" io.github.suppierk.shoostr.bench.SlowClientServer 18080 \
  > "$destination/server.log" 2>&1 &
server_group=$!
server_pid=$(native_pid "$server_group" java)
for ((attempt=0; attempt<300; attempt++)); do
  if grep -q 'READY 18080' "$destination/server.log"; then break; fi
  kill -0 "$server_pid"
  sleep 0.1
done
grep -q 'READY 18080' "$destination/server.log"
verify_server_cgroup "$server_pid"
[[ $(awk '/^Cpus_allowed_list:/ {print $2}' "/proc/$server_pid/status") == "$server_cpus" ]]
capture_server_cgroup > "$destination/server-cgroup-before.txt"
taskset -pc "$server_pid" > "$destination/server-affinity.txt"
cat "/proc/$server_pid/limits" > "$destination/server-limits.txt"
for command in VM.version VM.command_line VM.flags VM.info; do
  timeout 10s "$JAVA_HOME/bin/jcmd" "$server_pid" "$command" > "$destination/$command.txt"
done
timeout 10s "$JAVA_HOME/bin/jcmd" "$server_pid" VM.flags -all > "$destination/VM.flags-all.txt"
grep -Eq 'MaxHeapSize[[:space:]]+=[[:space:]]+268435456' "$destination/VM.flags-all.txt"
curl --silent --show-error --fail --max-time 2 "$base/configuration" > "$destination/native-configuration.txt"
timeout 10s "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.start name=slow-client settings=profile \
  disk=true maxsize=128m 'jdk.VirtualThreadStart#enabled=true' 'jdk.VirtualThreadEnd#enabled=true' \
  'jdk.JavaErrorThrow#enabled=true' > "$destination/jfr-start.txt"
recording=1

for kind in body blocked; do
  for clients in 32 128; do
    for repeat in 1 2; do
      run_dir="$destination/$kind-$clients-$repeat"
      mkdir "$run_dir"
      echo "::group::Slow-client $kind clients=$clients repeat=$repeat"
      [[ $(control arm) == armed ]]
      snapshot baseline
      setsid /usr/bin/time -v -o "$run_dir/health-client-resource.txt" \
        taskset -c "$client_cpus" "$tools/k6" run --no-usage-report --no-color --quiet \
        -e ROLE=health -e BASE_URL="$base" -e CLIENTS="$clients" \
        --summary-export "$run_dir/health-summary.json" --out "json=$run_dir/health-metrics.json.gz" \
        "$support/slow-client.js" > "$run_dir/health-client.log" 2>&1 &
      health_group=$!
      health_pid=$(native_pid "$health_group" k6)
      for ((attempt=0; attempt<100; attempt++)); do
        if grep -q 'READY health' "$run_dir/health-client.log"; then break; fi
        kill -0 "$health_pid"
        sleep 0.1
      done
      grep -q 'READY health' "$run_dir/health-client.log"
      start_ms=$(now_ms)
      if [[ "$kind" == body ]]; then
        setsid /usr/bin/time -v -o "$run_dir/load-client-resource.txt" \
          taskset -c "$client_cpus" slowhttptest -B -c "$clients" -r "$clients" -l 60 -i 1 \
          -s 65536 -x 8 -t POST -f application/octet-stream -u "$base/slow-body" \
          -g -o "$run_dir/body" > "$run_dir/load-client.log" 2>&1 &
        load_group=$!
        load_pid=$(native_pid "$load_group" slowhttptest)
      else
        setsid /usr/bin/time -v -o "$run_dir/load-client-resource.txt" \
          taskset -c "$client_cpus" "$tools/k6" run --no-usage-report --no-color --quiet \
          -e ROLE=blocked -e BASE_URL="$base" -e CLIENTS="$clients" \
          --summary-export "$run_dir/blocked-summary.json" --out "json=$run_dir/blocked-metrics.json.gz" \
          "$support/slow-client.js" > "$run_dir/load-client.log" 2>&1 &
        load_group=$!
        load_pid=$(native_pid "$load_group" k6)
      fi
      {
        for pid in "$health_pid" "$load_pid"; do
          printf 'PID=%s\n' "$pid"
          tr '\0' '\n' < "/proc/$pid/cmdline"
          cat "/proc/$pid/cgroup"
          taskset -pc "$pid"
        done
      } > "$run_dir/client-placement.txt"
      top -b -d 1 -w 160 -p "$server_pid,$health_pid,$load_pid" > "$run_dir/process-monitor.txt" &
      monitor_pid=$!
      sampled_load=0
      while kill -0 "$load_group" 2>/dev/null; do
        sample load
        elapsed_ms=$(($(now_ms) - start_ms))
        (( elapsed_ms < 75000 )) || { echo 'Load tool exceeded its finite budget' >&2; exit 1; }
        if (( elapsed_ms >= 10000 && sampled_load == 0 )); then
          snapshot load
          sampled_load=1
        fi
        sleep 1
      done
      status=0
      wait "$load_group" || status=$?
      load_group=''
      load_pid=''
      printf '%s\n' "$status" > "$run_dir/load-exit-code.txt"
      [[ "$status" == 0 ]]
      load_end_ms=$(now_ms)
      [[ $(control release) == released ]]
      while (( $(now_ms) - load_end_ms < 30000 )); do
        sample recovery
        sleep 1
      done
      recovery_end_ms=$(now_ms)
      jq -n --arg kind "$kind" --argjson start "$start_ms" --argjson end "$load_end_ms" \
        --argjson recovered "$recovery_end_ms" \
        '{kind:$kind,start_ms:$start,load_end_ms:$end,recovery_end_ms:$recovered}' > "$run_dir/phase.json"
      status=0
      while kill -0 "$health_group" 2>/dev/null; do
        (( $(now_ms) - start_ms < 115000 )) \
          || { echo 'Healthy probe tool exceeded its finite budget' >&2; exit 1; }
        sleep 1
      done
      wait "$health_group" || status=$?
      health_group=''
      health_pid=''
      printf '%s\n' "$status" > "$run_dir/health-exit-code.txt"
      [[ "$status" == 0 ]]
      stop_monitor
      curl --silent --show-error --fail --max-time 2 "$base/state" > "$run_dir/recovered-state.json"
      snapshot recovered
      timeout 10s "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.dump name=slow-client maxage=2m \
        filename="$run_dir/server.jfr" > "$run_dir/jfr-dump.txt"
      "$JAVA_HOME/bin/jfr" summary "$run_dir/server.jfr" > "$run_dir/jfr-summary.txt"
      "$JAVA_HOME/bin/jfr" print --events jdk.JavaErrorThrow "$run_dir/server.jfr" > "$run_dir/java-errors.txt"
      cp "$server_cgroup/memory.events" "$run_dir/memory.events"
      cp "$destination/server.log" "$run_dir/server.log"
      node "$support/slow-client-check.mjs" "$run_dir" "$clients" | tee "$run_dir/verification.json"
      echo '::endgroup::'
    done
  done
done
finished=1
