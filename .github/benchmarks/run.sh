#!/usr/bin/env bash
set -euo pipefail

workload=${1:?Supply a workload}
mode=${2:?Supply steady or overload}
destination=${3:?Supply a new result directory}
tools=${4:?Supply the prepared benchmark tools directory}
: "${JAVA_HOME:?Set JAVA_HOME}"
support=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source-path=SCRIPTDIR
# shellcheck source=resources.sh
source "$support/resources.sh"
configure_resources
configuration=$(jq -ce --arg workload "$workload" '.[$workload] // error("Unknown workload")' "$support/workloads.json")
case "$mode" in
  steady) ;;
  overload) [[ $(jq -r .ramp <<< "$configuration") == true ]] \
    || { echo 'This workload does not support overload' >&2; exit 2; } ;;
  *) echo 'Mode must be steady or overload' >&2; exit 2 ;;
esac
[[ ! -e "$destination" ]] || { echo 'Refusing to overwrite results' >&2; exit 2; }
mkdir -p "$destination"
destination=$(realpath "$destination")
tools=$(realpath "$tools")
read -r selected rate connections < <(jq -r '[.selected,.rate,.connections] | @tsv' <<< "$configuration")
base=http://127.0.0.1:18080
server_group=''
server_pid=''
client_group=''
client_pid=''
monitor_pid=''
run_dir=''
recording=0
failed=0
server_cgroup=''

stop_group() {
  local owned=$1 native_pid=$2
  if [[ -n "$owned" ]]; then
    if [[ -n "$native_pid" ]]; then
      # Let /usr/bin/time survive the child and write its resource report.
      kill -TERM "$native_pid" 2>/dev/null || true
    else
      kill -TERM -- "-$owned" 2>/dev/null || true
    fi
    for ((attempt=0; attempt<50; attempt++)); do
      if ! kill -0 "$owned" 2>/dev/null; then break; fi
      sleep 0.1
    done
    kill -KILL -- "-$owned" 2>/dev/null || true
    wait "$owned" 2>/dev/null || true
  fi
}

cleanup() {
  if [[ "$recording" == 1 && -n "$server_pid" ]]; then
    timeout 5s "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.stop name=benchmark \
      > "$run_dir/jfr-cleanup.txt" 2>&1 || true
    recording=0
  fi
  if [[ -n "$monitor_pid" ]]; then
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
    monitor_pid=''
  fi
  stop_group "$client_group" "$client_pid"
  client_group=''
  client_pid=''
  stop_group "$server_group" "$server_pid"
  server_group=''
  server_pid=''
  if [[ -n "$server_cgroup" && -d "$server_cgroup" ]]; then
    capture_server_cgroup > "$run_dir/server-cgroup-after.txt"
    if ! verify_server_cgroup_limits || [[ ! $(< "$server_cgroup/memory.peak") =~ ^[0-9]+$ ]]; then
      echo INVALID_RESOURCE_EVIDENCE > "$run_dir/outcome.txt"
      failed=1
    fi
    if awk '$1 == "oom_kill" && $2 > 0 {found=1} END {exit !found}' "$server_cgroup/memory.events"; then
      echo MEMORY_LIMIT_EXCEEDED > "$run_dir/outcome.txt"
      failed=1
    fi
    sudo -n rmdir "$server_cgroup"
    server_cgroup=''
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cp "$support/workloads.json" "$destination/workloads.json"
jq -n --arg workload "$workload" --arg mode "$mode" --argjson fixture "$configuration" \
  --arg revision "$(git rev-parse HEAD)" --arg image "${ImageVersion:-unknown}" \
  --arg image_os "${ImageOS:-unknown}" \
  --argjson jvm_cpus "$jvm_cpus" --argjson jvm_memory_mib "$jvm_memory_mib" \
  '{workload:$workload,mode:$mode,fixture:$fixture,revision:$revision,image:$image,image_os:$image_os,
    repeats:3,warmup_seconds:30,measurement_seconds:180,heap:"256 MiB",collector:"G1",
    jvm_cpus:$jvm_cpus,jvm_memory_mib:$jvm_memory_mib,
    ramp_start:1000,ramp_end:25000,latency:"scheduled send to complete response"}' \
  > "$destination/configuration.json"
"$JAVA_HOME/bin/java" -version > "$destination/java.txt" 2>&1
"$tools/zrk" --version > "$destination/zrk.txt"
{
  uname -a
  lscpu
  free -m
  df -h "$destination"
} > "$destination/host.txt"
sha256sum "$tools/zrk" "$tools"/lib/*.jar "$support"/*.lua "$support/workloads.json" \
  "$support/run.sh" "$support/resources.sh" "$support/check-zrk.jq" "$support/install-zrk.sh" \
  > "$destination/sha256.txt"
printf 'SERVER_CPUS=%s\nCLIENT_CPUS=%s\n' "$server_cpus" "$client_cpus" > "$destination/affinity.txt"

load() {
  local phase=$1 length=$2 status=0 offered=$rate
  if [[ "$mode" == overload && "$phase" == measured ]]; then offered=1000:25000; fi
  setsid /usr/bin/time -v -o "$run_dir/$phase-client-resource.txt" \
    taskset -c "$client_cpus" "$tools/zrk" -t2 -c"$connections" -d"$length" -R"$offered" \
    --timeout 5s --plain --script "$support/http.lua" \
    --format json -o "$run_dir/$phase-summary.json" --hdr "$run_dir/$phase-latency.hgrm" \
    --timeseries "$run_dir/$phase-timeseries.ndjson" --timeseries-histogram \
    "$base" "$selected" 1000 > "$run_dir/$phase-client.log" 2>&1 &
  client_group=$!
  if [[ "$phase" == measured ]]; then
    for ((attempt=0; attempt<100; attempt++)); do
      client_pid=$(pgrep -P "$client_group" -x zrk || true)
      if [[ -n "$client_pid" ]] || ! kill -0 "$client_group" 2>/dev/null; then break; fi
      sleep 0.1
    done
    if [[ "$client_pid" =~ ^[0-9]+$ ]]; then
      taskset -pc "$client_pid" > "$run_dir/client-affinity.txt"
      top -b -d 1 -w 160 -p "$server_pid,$client_pid" > "$run_dir/process-monitor.txt" &
      monitor_pid=$!
      printf 'SERVER_PID=%s\nCLIENT_PID=%s\n' "$server_pid" "$client_pid" > "$run_dir/monitor-pids.txt"
    else
      echo 'Cannot monitor load generator' >&2
      return 1
    fi
  fi
  wait "$client_group" || status=$?
  client_group=''
  client_pid=''
  printf '%s\n' "$status" > "$run_dir/$phase-exit-code.txt"
  cat "$run_dir/$phase-client.log"
  if [[ -n "$monitor_pid" ]]; then
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
    monitor_pid=''
  fi
  if [[ "$status" == 0 ]]; then
    local last_rate=${offered##*:} first_rate=${offered%%:*} expected_class=2xx
    if [[ "$selected" == not-found || "$selected" == wrong-method ]]; then expected_class=4xx; fi
    jq -e --argjson seconds "${length%s}" --argjson connections "$connections" \
      --argjson rate "$first_rate" --argjson end_rate "$last_rate" \
      --arg status_class "$expected_class" -f "$support/check-zrk.jq" \
      "$run_dir/$phase-summary.json" > /dev/null || status=1
  fi
  return "$status"
}

for repeat in 1 2 3; do
  run_dir="$destination/trial-$repeat"
  mkdir "$run_dir"
  echo "::group::$workload trial $repeat ($mode)"
  date -u +%FT%TZ > "$run_dir/start.txt"
  server_cgroup="/sys/fs/cgroup/shoostr-benchmark-$$-$repeat"
  create_server_cgroup
  setsid /usr/bin/time -v -o "$run_dir/server-resource.txt" \
    bash "$support/resources.sh" "$server_cgroup" \
    taskset -c "$server_cpus" "$JAVA_HOME/bin/java" -Xms256m -Xmx256m -XX:+UseG1GC \
    -cp "$tools/lib/*" io.github.suppierk.shoostr.bench.ServerMain 18080 1000 \
    > "$run_dir/server.log" 2>&1 &
  server_group=$!
  for ((attempt=0; attempt<300; attempt++)); do
    server_pid=$(pgrep -P "$server_group" -x java || true)
    if [[ "$server_pid" =~ ^[0-9]+$ ]] && grep -q 'READY 18080' "$run_dir/server.log"; then break; fi
    if ! kill -0 "$server_group" 2>/dev/null; then break; fi
    sleep 0.1
  done
  if [[ ! "$server_pid" =~ ^[0-9]+$ ]] || ! grep -q 'READY 18080' "$run_dir/server.log"; then
    echo STARTUP_FAILED > "$run_dir/outcome.txt"
    failed=1
    cleanup
    echo '::endgroup::'
    continue
  fi
  verify_server_cgroup "$server_pid"
  capture_server_cgroup > "$run_dir/server-cgroup-before.txt"
  taskset -pc "$server_pid" > "$run_dir/server-affinity.txt"
  [[ $(grep '^Cpus_allowed_list:' "/proc/$server_pid/status") \
    == $(taskset -c "$server_cpus" grep '^Cpus_allowed_list:' /proc/self/status) ]]
  for command in VM.version VM.command_line VM.flags VM.info; do
    "$JAVA_HOME/bin/jcmd" "$server_pid" "$command" > "$run_dir/$command.txt"
    cat "$run_dir/$command.txt"
  done
  "$JAVA_HOME/bin/jcmd" "$server_pid" VM.flags -all > "$run_dir/VM.flags-all.txt"
  cat "/proc/$server_pid/limits" > "$run_dir/server-limits.txt"
  cat "$run_dir/server-affinity.txt" "$run_dir/server-limits.txt"
  [[ $(curl --fail --silent --show-error --max-time 5 "$base/virtual") == false ]]
  for fixture in plaintext json-bytes echo stream route-literal route-parameter not-found wrong-method sse-burst sse-paced; do
    "$tools/zrk" -t1 -c1 -d1s -R2 --timeout 5s --plain --script "$support/http.lua" \
      --format json -o "$run_dir/parity-$fixture.json" "$base" "$fixture" 1000 \
      > "$run_dir/parity-$fixture.log" 2>&1
    status_class=2xx
    if [[ "$fixture" == not-found || "$fixture" == wrong-method ]]; then status_class=4xx; fi
    jq -e --argjson seconds 1 --argjson connections 1 --argjson rate 2 --argjson end_rate 2 --arg status_class "$status_class" \
      -f "$support/check-zrk.jq" "$run_dir/parity-$fixture.json" > /dev/null
  done
  if ! load warmup 30s; then
    echo WARMUP_FAILED > "$run_dir/outcome.txt"
    failed=1
    cleanup
    echo '::endgroup::'
    continue
  fi
  "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.configure stackdepth=128 > "$run_dir/jfr-configure.txt"
  "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.start name=benchmark settings=profile disk=true \
    dumponexit=true filename="$run_dir/server.jfr" \
    'jdk.ExecutionSample#enabled=true' 'jdk.ExecutionSample#period=10ms' > "$run_dir/jfr-start.txt"
  recording=1
  "$JAVA_HOME/bin/jcmd" "$server_pid" GC.heap_info > "$run_dir/before-heap.txt"
  cat "/proc/$server_pid/stat" > "$run_dir/before-process-stat.txt"
  date -u +%FT%TZ > "$run_dir/measurement-start.txt"
  status=0
  load measured 180s || status=$?
  date -u +%FT%TZ > "$run_dir/measurement-end.txt"
  if [[ "$status" == 0 ]]; then
    echo PASS > "$run_dir/outcome.txt"
  else
    echo FAILED > "$run_dir/outcome.txt"
    failed=1
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo SERVER_EXITED > "$run_dir/outcome.txt"
    failed=1
    cleanup
    echo '::endgroup::'
    continue
  fi
  cat "/proc/$server_pid/stat" > "$run_dir/after-process-stat.txt"
  "$JAVA_HOME/bin/jcmd" "$server_pid" GC.heap_info > "$run_dir/after-heap.txt"
  "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.stop name=benchmark > "$run_dir/jfr-stop.txt"
  recording=0
  "$JAVA_HOME/bin/jfr" summary "$run_dir/server.jfr" > "$run_dir/jfr-summary.txt"
  for view in allocation-by-site allocation-by-class hot-methods gc-pauses gc-cpu-time; do
    "$JAVA_HOME/bin/jfr" view --width 120 "$view" "$run_dir/server.jfr" > "$run_dir/$view.txt"
  done
  jq -e -s 'length > 0 and all(.[]; (.t | type == "number") and
    (.latency_histogram | type == "string" and length > 0))' \
    "$run_dir/measured-timeseries.ndjson" > /dev/null
  cleanup
  for file in server.jfr jfr-summary.txt measured-summary.json measured-timeseries.ndjson measured-latency.hgrm \
    process-monitor.txt monitor-pids.txt server-resource.txt measured-client-resource.txt \
    allocation-by-site.txt allocation-by-class.txt hot-methods.txt gc-pauses.txt gc-cpu-time.txt \
    VM.version.txt VM.command_line.txt VM.flags.txt VM.flags-all.txt VM.info.txt \
    server-limits.txt server-affinity.txt client-affinity.txt server-cgroup-before.txt server-cgroup-after.txt; do
    [[ -s "$run_dir/$file" ]] || { echo "Missing benchmark evidence: $file" >&2; exit 1; }
  done
  echo '::endgroup::'
done
exit "$failed"
