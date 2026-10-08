#!/usr/bin/env bash
set -euo pipefail

workload=${1:?Supply a workload}
mode=${2:?Supply steady or overload}
destination=${3:?Supply a new result directory}
tools=${4:?Supply the prepared benchmark tools directory}
: "${JAVA_HOME:?Set JAVA_HOME}"
support=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
configuration=$(jq -ce --arg workload "$workload" '.[$workload] // error("Unknown workload")' "$support/workloads.json")
kind=$(jq -r .kind <<< "$configuration")
case "$mode:$kind" in
  steady:*|overload:http) ;;
  *) echo 'Overload requires an HTTP workload; mode must be steady or overload' >&2; exit 2 ;;
esac
[[ ! -e "$destination" ]] || { echo 'Refusing to overwrite results' >&2; exit 2; }
mkdir -p "$destination"
destination=$(realpath "$destination")
tools=$(realpath "$tools")
read -r script selected rate vus < <(jq -r '[.script,.selected,.rate,.vus] | @tsv' <<< "$configuration")
base=http://127.0.0.1:18080
if [[ "$kind" == ws ]]; then base=ws://127.0.0.1:18080; fi
server_group=''
server_pid=''
client_group=''
client_pid=''
monitor_pid=''
run_dir=''
recording=0
failed=0

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
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cp "$support/workloads.json" "$destination/workloads.json"
jq -n --arg workload "$workload" --arg mode "$mode" --argjson fixture "$configuration" \
  --arg revision "$(git rev-parse HEAD)" --arg image "${ImageVersion:-unknown}" \
  --arg image_os "${ImageOS:-unknown}" \
  '{workload:$workload,mode:$mode,fixture:$fixture,revision:$revision,image:$image,image_os:$image_os,
    repeats:3,warmup_seconds:10,measurement_seconds:180,heap:"256 MiB",collector:"G1",
    smoke_rates:[1000,2000,5000,10000,20000,50000,100000],smoke_step_seconds:15}' \
  > "$destination/configuration.json"
"$JAVA_HOME/bin/java" -version > "$destination/java.txt" 2>&1
"$tools/k6" version > "$destination/k6.txt"
{
  uname -a
  lscpu
  free -m
  df -h "$destination"
} > "$destination/host.txt"
sha256sum "$tools/k6" "$tools"/lib/*.jar "$support"/*.js "$support/workloads.json" \
  "$support/run.sh" "$support/report.mjs" "$support/go.mod" "$support/go.sum" \
  > "$destination/sha256.txt"
mapfile -t cpus < <(lscpu -p=CPU | sed '/^#/d')
[[ ${#cpus[@]} -ge 2 ]] || { echo 'At least two CPUs required' >&2; exit 2; }
split=$((${#cpus[@]} / 2))
server_cpus=$(IFS=,; echo "${cpus[*]:0:split}")
client_cpus=$(IFS=,; echo "${cpus[*]:split}")
printf 'SERVER_CPUS=%s\nCLIENT_CPUS=%s\n' "$server_cpus" "$client_cpus" > "$destination/affinity.txt"

load() {
  local phase=$1 length=$2 status=0 load_script=$script
  if [[ "$mode" == overload && "$phase" == measured ]]; then
    load_script=overload.js
  fi
  local dashboard=false
  if [[ "$phase" == measured ]]; then dashboard=true; fi
  setsid /usr/bin/time -v -o "$run_dir/$phase-client-resource.txt" \
    env K6_WEB_DASHBOARD="$dashboard" K6_WEB_DASHBOARD_PORT=-1 K6_WEB_DASHBOARD_PERIOD=5s \
    K6_WEB_DASHBOARD_EXPORT="$run_dir/report.html" \
    taskset -c "$client_cpus" "$tools/k6" run --no-usage-report --no-color --quiet \
    -e BASE_URL="$base" -e WORKLOAD="$selected" -e ROUTE_GROUPS=1000 \
    -e RATE="$rate" -e VUS="$vus" -e DURATION="$length" \
    -e FAILURE_DETAILS=1 \
    --summary-export "$run_dir/$phase-summary.json" \
    --out "json=$run_dir/$phase-metrics.json.gz" "$support/$load_script" \
    > "$run_dir/$phase-client.log" 2>&1 &
  client_group=$!
  if [[ "$phase" == measured ]]; then
    for ((attempt=0; attempt<100; attempt++)); do
      client_pid=$(pgrep -P "$client_group" -x k6 || true)
      if [[ -n "$client_pid" ]] || ! kill -0 "$client_group" 2>/dev/null; then break; fi
      sleep 0.1
    done
    if [[ "$client_pid" =~ ^[0-9]+$ ]]; then
      taskset -pc "$client_pid" > "$run_dir/client-affinity.txt"
      top -b -d 1 -w 160 -p "$server_pid,$client_pid" > "$run_dir/process-monitor.txt" &
      monitor_pid=$!
      printf 'SERVER_PID=%s\nCLIENT_PID=%s\n' "$server_pid" "$client_pid" > "$run_dir/monitor-pids.txt"
    else
      echo 'Generator monitoring unavailable' > "$run_dir/monitor-status.txt"
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
  return "$status"
}

for repeat in 1 2 3; do
  run_dir="$destination/trial-$repeat"
  mkdir "$run_dir"
  echo "::group::$workload trial $repeat ($mode)"
  date -u +%FT%TZ > "$run_dir/start.txt"
  setsid /usr/bin/time -v -o "$run_dir/server-resource.txt" \
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
  taskset -pc "$server_pid" > "$run_dir/server-affinity.txt"
  if ! load warmup 10s; then
    echo WARMUP_FAILED > "$run_dir/outcome.txt"
    failed=1
    cleanup
    echo '::endgroup::'
    continue
  fi
  "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.configure stackdepth=128 > "$run_dir/jfr-configure.txt"
  "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.start name=benchmark settings=profile disk=true \
    dumponexit=true filename="$run_dir/server.jfr" \
    'jdk.CPUTimeSample#enabled=true' 'jdk.CPUTimeSample#throttle=10ms' > "$run_dir/jfr-start.txt"
  recording=1
  "$JAVA_HOME/bin/jcmd" "$server_pid" GC.heap_info > "$run_dir/before-heap.txt"
  cat "/proc/$server_pid/stat" > "$run_dir/before-process-stat.txt"
  date -u +%FT%TZ > "$run_dir/measurement-start.txt"
  status=0
  load measured 180s || status=$?
  date -u +%FT%TZ > "$run_dir/measurement-end.txt"
  if [[ "$status" == 0 ]]; then
    echo PASS > "$run_dir/outcome.txt"
  elif [[ "$mode" == overload && "$status" == 99 ]]; then
    echo OVERLOAD > "$run_dir/outcome.txt"
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
  gzip -t "$run_dir/measured-metrics.json.gz"
  [[ -s "$run_dir/report.html" && -s "$run_dir/measured-summary.json" ]]
  cleanup
  echo '::endgroup::'
done
exit "$failed"
