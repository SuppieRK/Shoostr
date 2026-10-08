#!/usr/bin/env bash
# Sequential native k6 runs; no project-specific load or statistics engine.
set -euo pipefail

: "${JAVA_HOME:?Set JAVA_HOME to the JDK used to build the fixtures}"
destination=${1:?Supply a new result directory}
mode=${MODE:-comparison}
case "$mode" in
  comparison) default_repeats=3; default_raw=1
    default_workloads='plaintext json-bytes echo stream route-literal route-parameter not-found wrong-method sse-burst sse-paced sse-slow ws-text ws-binary ws-slow' ;;
  smoke) default_repeats=1; default_raw=0
    default_workloads='plaintext'
    for workload in ${WORKLOADS:-$default_workloads}; do
      case "$workload" in
        plaintext|json-bytes|echo|stream|route-literal|route-parameter|not-found|wrong-method|route-catch-all|route-regex|route-regex-fallback) ;;
        *) cmdshape --raw echo "Smoke mode supports HTTP workloads only: $workload"; exit 1 ;;
      esac
    done ;;
  *) cmdshape --raw echo "Unknown MODE: $mode"; exit 1 ;;
esac
duration=${DURATION:-180s}
warmup=${WARMUP:-15s}
repeats=${REPEATS:-$default_repeats}
profile=${PROFILE:-0}
benchmark_home=${BENCHMARK_HOME:-benchmarks/build/install/benchmarks}
http_script=${HTTP_SCRIPT:-benchmarks/http.js}
raw_metrics=${RAW_METRICS:-$default_raw}
default_targets=rotate-by-repeat
if [[ "$mode" == smoke ]]; then default_targets='candidate jooby (alternating order)'; fi
smoke_rates=${SMOKE_RATES:-1000,2000,5000,10000,20000,50000,100000}
smoke_step_seconds=${SMOKE_STEP_SECONDS:-15}
server_cpus=${SERVER_CPUS:-0-7}
client_cpus=${CLIENT_CPUS:-8-15}
workloads=${WORKLOADS:-$default_workloads}
port=18080
server_pid=''
launcher_pid=''
client_pid=''
client_launcher_pid=''
monitor_pid=''
failed=0

stop_monitor() {
  if [[ -n "$monitor_pid" ]]; then
    cmdshape --raw kill -TERM "$monitor_pid" 2>/dev/null || cmdshape --raw true
    wait "$monitor_pid" || cmdshape --raw true
    monitor_pid=''
  fi
}

stop_server() {
  stop_monitor
  if [[ -n "$client_launcher_pid" ]]; then
    # The launch job owns its group before native PID discovery can complete.
    cmdshape --raw kill -TERM -- "-$client_launcher_pid" 2>/dev/null || cmdshape --raw true
    wait "$client_launcher_pid" || cmdshape --raw true
    client_pid=''
    client_launcher_pid=''
  fi
  if [[ -n "$server_pid" ]]; then
    cmdshape --raw kill -TERM "$server_pid" 2>/dev/null || cmdshape --raw true
    for ((attempt=0; attempt<300; attempt++)); do
      if ! cmdshape --raw kill -0 "$server_pid" 2>/dev/null; then break; fi
      cmdshape --raw sleep 0.1
    done
    if cmdshape --raw kill -0 "$server_pid" 2>/dev/null; then
      cmdshape --raw echo 'Owned benchmark JVM did not stop after 30 seconds; terminating it'
      cmdshape --raw kill -KILL "$server_pid" || cmdshape --raw true
    fi
    wait "$launcher_pid" || cmdshape --raw true
    launcher_pid=''
    server_pid=''
  fi
  if [[ -n "$launcher_pid" ]]; then
    # Own the launch group even when interruption precedes native PID discovery.
    cmdshape --raw kill -TERM -- "-$launcher_pid" 2>/dev/null || cmdshape --raw true
    wait "$launcher_pid" || cmdshape --raw true
    launcher_pid=''
  fi
}
trap stop_server EXIT
trap 'exit 130' INT TERM

if [[ -e "$destination" ]]; then
  cmdshape --raw echo "Refusing to overwrite existing campaign $destination"
  exit 1
fi
cmdshape --raw mkdir -p "$destination"
cmdshape --raw printf '%s\n' \
  "DURATION=$duration" "WARMUP=$warmup" "REPEATS=$repeats" "PROFILE=$profile" \
  "MODE=$mode" "RAW_METRICS=$raw_metrics" \
  "SMOKE_RATES=$smoke_rates" "SMOKE_STEP_SECONDS=$smoke_step_seconds" \
  "SERVER_CPUS=$server_cpus" "CLIENT_CPUS=$client_cpus" \
  "WORKLOADS=$workloads" "TARGETS=${TARGETS:-$default_targets}" \
  "BENCHMARK_HOME=$benchmark_home" "HTTP_SCRIPT=$http_script" \
  "HTTP_RATE=${HTTP_RATE:-1000}" "WARMUP_RATE=${WARMUP_RATE:-same-as-measured}" \
  "CANDIDATE_ROUTING=${CANDIDATE_ROUTING:-0}" \
  "FAILURE_DETAILS=${FAILURE_DETAILS:-0}" \
  "JAVA_HOME=$JAVA_HOME" "PORT=$port" > "$destination/configuration.txt"
cmdshape --raw git rev-parse HEAD > "$destination/revision.txt"
cmdshape --raw git diff -- benchmarks > "$destination/fixtures.patch"
cmdshape --raw git diff --binary HEAD > "$destination/worktree.patch"
source_root=$(cmdshape --raw git rev-parse --show-toplevel)
source_destination=$(cmdshape --raw realpath --relative-to="$source_root" "$destination")
source_paths=(.)
if [[ "$source_destination" != ../* ]]; then
  source_paths+=(":(top,exclude,literal)$source_destination")
fi
cmdshape --raw git ls-files --others --exclude-standard -z -- "${source_paths[@]}" \
  | cmdshape --raw tar --null -T - -czf "$destination/untracked-sources.tar.gz"
cmdshape --raw "$JAVA_HOME/bin/java" -version > "$destination/java.txt" 2>&1
cmdshape --raw .scratch/tools/k6 version > "$destination/k6.txt"
if [[ "$mode" == comparison ]]; then
  cmdshape --raw .scratch/tools/k6-sse version > "$destination/k6-sse.txt"
fi
cmdshape --raw uname -a > "$destination/host.txt"
cmdshape --raw lscpu >> "$destination/host.txt"
cmdshape --raw lscpu -e=CPU,CORE,SOCKET >> "$destination/host.txt"
cmdshape --raw getconf CLK_TCK > "$destination/clock-ticks.txt"
cmdshape --raw git status --short > "$destination/worktree.txt"
cmdshape --raw ls "$benchmark_home/lib" > "$destination/runtime.txt"
cmdshape --raw sha256sum benchmarks/build.gradle benchmarks/compare.sh \
  benchmarks/http.js benchmarks/sse.js benchmarks/sse-slow.js \
  benchmarks/ramp.js \
  benchmarks/websocket.js benchmarks/websocket-slow.js \
  benchmarks/src/main/java/io/github/suppierk/shoostr/bench/ServerMain.java \
  "$http_script" "$benchmark_home"/lib/*.jar > "$destination/sha256.txt"
cmdshape --raw git diff --no-index /dev/null benchmarks/compare.sh \
  > "$destination/runner.patch" || cmdshape --raw true

load() {
  local phase=$1 length=$2
  local load_script=$script load_rate=$rate status=0
  local output_args=()
  if [[ "$phase" == warmup ]]; then
    load_rate=${WARMUP_RATE:-$rate}
    if [[ "$load_script" == benchmarks/ramp.js ]]; then load_script=benchmarks/http.js; fi
  fi
  if [[ "$raw_metrics" == 1 ]]; then output_args=(--out "json=$run_dir/$phase-metrics.json.gz"); fi
  # Bash gives this background job an isolated process group, including wrappers.
  set -m
  cmdshape --raw /usr/bin/time -v -o "$run_dir/$phase-client-resource.txt" \
    cmdshape --raw taskset -c "$client_cpus" cmdshape --raw bash -c '
      set -e
      cmdshape --raw taskset -pc "$$" > "$1"
      shift
      exec cmdshape --raw "$@"
    ' benchmark-client "$run_dir/$phase-client-launch-affinity.txt" "$client" run \
    --no-usage-report --no-color -q \
    -e BASE_URL="$base" -e ROUTE_GROUPS=1000 -e WORKLOAD="$selected" \
    -e CANDIDATE_ROUTING="${CANDIDATE_ROUTING:-0}" \
    -e FAILURE_DETAILS="${FAILURE_DETAILS:-0}" \
    -e RATE="$load_rate" -e VUS="$vus" -e DURATION="$length" \
    -e MODE="$mode" -e SMOKE_RATES="$smoke_rates" -e SMOKE_STEP_SECONDS="$smoke_step_seconds" \
    --summary-export "$run_dir/$phase-summary.json" \
    "${output_args[@]}" "$load_script" \
    > "$run_dir/$phase-client.log" 2>&1 &
  client_launcher_pid=$!
  set +m
  cmdshape --raw echo "$client_launcher_pid" > "$run_dir/$phase-client-process-group.txt"
  # Resolve client ownership in every phase so interruption also stops warmup.
  for ((attempt=0; attempt<100; attempt++)); do
    for candidate_pid in $(cmdshape --raw pgrep -x 'k6|k6-sse' || cmdshape --raw true); do
      if [[ -r "/proc/$candidate_pid/cmdline" ]] && \
        cmdshape --raw tr '\0' '\n' < "/proc/$candidate_pid/cmdline" | \
        cmdshape --raw rg -Fxq -- "$run_dir/$phase-summary.json"; then
        client_pid=$candidate_pid
        break
      fi
    done
    if [[ -n "$client_pid" ]] || ! cmdshape --raw kill -0 "$client_launcher_pid" 2>/dev/null; then break; fi
    cmdshape --raw sleep 0.1
  done
  if [[ "$mode" == smoke && "$phase" == measured ]]; then
    if [[ -n "$client_pid" ]]; then
      cmdshape --raw top -b -d 1 -w 160 -p "$server_pid,$client_pid" \
        > "$run_dir/process-monitor.txt" 2>&1 &
      monitor_pid=$!
      cmdshape --raw printf 'SERVER_PID=%s\nCLIENT_PID=%s\n' "$server_pid" "$client_pid" \
        > "$run_dir/monitor-pids.txt"
    else
      if cmdshape --raw kill -0 "$client_launcher_pid" 2>/dev/null; then
        cmdshape --raw echo 'Native client PID not resolved within 10 seconds; generator monitoring unavailable' > "$run_dir/monitor-status.txt"
      else
        cmdshape --raw echo 'Client exited before native process monitoring attached' > "$run_dir/monitor-status.txt"
      fi
    fi
  fi
  wait "$client_launcher_pid" || status=$?
  cmdshape --raw echo "$status" > "$run_dir/$phase-client-exit-code.txt"
  stop_monitor
  client_pid=''
  client_launcher_pid=''
  return "$status"
}

for ((repeat=1; repeat<=repeats; repeat++)); do
  case $((repeat % 3)) in
    1) targets='candidate jooby javalin' ;;
    2) targets='jooby javalin candidate' ;;
    0) targets='javalin candidate jooby' ;;
  esac
  if [[ "$mode" == smoke ]]; then
    if ((repeat % 2)); then targets='candidate jooby'; else targets='jooby candidate'; fi
  fi
  targets=${TARGETS:-$targets}
  for workload in $workloads; do
    for target in $targets; do
      if [[ "${CANDIDATE_ROUTING:-0}" == 1 && "$target" != candidate ]]; then
        cmdshape --raw echo 'CANDIDATE_ROUTING fixtures require TARGETS=candidate; competitors do not provide these fixtures'
        exit 1
      fi
      run_dir="$destination/repeat-$repeat/$workload/$target"
      cmdshape --raw mkdir -p "$run_dir"
      if [[ -e "$run_dir/server-resource.txt" ]]; then
        cmdshape --raw echo "Refusing to overwrite $run_dir"
        exit 1
      fi
      script=$http_script
      client=.scratch/tools/k6
      selected=$workload
      base="http://127.0.0.1:$port"
      rate=${HTTP_RATE:-1000}
      vus=${VUS:-256}
      if [[ "$mode" == smoke ]]; then script=benchmarks/ramp.js; fi
      case "$workload" in
        sse-burst|sse-paced)
          script=benchmarks/sse.js; client=.scratch/tools/k6-sse
          selected=${workload#sse-}; rate=50; vus=128 ;;
        sse-slow)
          script=benchmarks/sse-slow.js; client=.scratch/tools/k6-sse
          rate=10; vus=64 ;;
        ws-text|ws-binary)
          script=benchmarks/websocket.js; selected=${workload#ws-}
          base="ws://127.0.0.1:$port"; rate=50; vus=128 ;;
        ws-slow)
          script=benchmarks/websocket-slow.js
          base="ws://127.0.0.1:$port"; rate=10; vus=64 ;;
      esac
      cmdshape --raw printf '%s\n' \
        "REPEAT=$repeat" "TARGET=$target" "WORKLOAD=$workload" \
        "SELECTED=$selected" "RATE=$rate" "VUS=$vus" \
        "MODE=$mode" "RAW_METRICS=$raw_metrics" \
        "SMOKE_RATES=$smoke_rates" "SMOKE_STEP_SECONDS=$smoke_step_seconds" \
        "SCRIPT=$script" "CLIENT=$client" "BASE_URL=$base" \
        > "$run_dir/configuration.txt"
      if [[ "$mode" == smoke ]]; then
        cmdshape --raw "$client" inspect --execution-requirements \
          -e MODE=smoke -e WORKLOAD="$selected" -e ROUTE_GROUPS=1000 \
          -e CANDIDATE_ROUTING="${CANDIDATE_ROUTING:-0}" \
          -e SMOKE_RATES="$smoke_rates" -e SMOKE_STEP_SECONDS="$smoke_step_seconds" \
          -e VUS="$vus" "$script" > "$run_dir/execution-plan.json" 2> "$run_dir/validation.log"
      fi
      if [[ "$mode" == smoke ]]; then
        cmdshape --raw echo "START repeat=$repeat workload=$workload target=$target smoke-rates=$smoke_rates step-seconds=$smoke_step_seconds"
      else
        cmdshape --raw echo "START repeat=$repeat workload=$workload target=$target rate=$rate duration=$duration"
      fi
      cmdshape --raw date -u +%FT%TZ > "$run_dir/start.txt"
      process_pattern="^$JAVA_HOME/bin/java .*io.github.suppierk.shoostr.bench.ServerMain $target $port 1000$"
      if cmdshape --raw pgrep -f "$process_pattern" > /dev/null; then
        cmdshape --raw echo 'A matching benchmark JVM already exists; refusing to adopt it'
        exit 1
      fi
      # Isolate the server and its wrappers before attempting native PID discovery.
      set -m
      cmdshape --raw /usr/bin/time -v -o "$run_dir/server-resource.txt" \
        cmdshape --raw taskset -c "$server_cpus" "$JAVA_HOME/bin/java" \
        -Xms256m -Xmx256m -XX:+UseG1GC \
        -cp "$benchmark_home/lib/*" \
        io.github.suppierk.shoostr.bench.ServerMain "$target" "$port" 1000 \
        > "$run_dir/server.log" 2>&1 &
      launcher_pid=$!
      set +m
      server_pid=''
      ready=0
      for ((attempt=0; attempt<100; attempt++)); do
        server_pid=$(cmdshape --raw pgrep -f "$process_pattern" || cmdshape --raw true)
        if [[ "$server_pid" == *$'\n'* ]]; then
          server_pid=''
          cmdshape --raw echo 'Ambiguous JVM ownership; stop the benchmark processes manually'
          exit 1
        fi
        if [[ -n "$server_pid" ]] && cmdshape --raw rg -q "READY $target $port" "$run_dir/server.log"; then
          ready=1
          break
        fi
        if ! cmdshape --raw kill -0 "$launcher_pid" 2>/dev/null; then
          cmdshape --raw echo "Server startup failed; inspect $run_dir/server.log"
          exit 1
        fi
        cmdshape --raw sleep 0.1
      done
      if [[ "$ready" != 1 || -z "$server_pid" ]]; then
        cmdshape --raw echo 'Could not resolve exactly one benchmark JVM'
        exit 1
      fi
      cmdshape --raw taskset -pc "$server_pid" > "$run_dir/server-launch-affinity.txt"
      if ! load warmup "$warmup"; then
        cmdshape --raw echo "Warmup failed: $run_dir/warmup-client.log"
        exit 1
      fi
      if [[ "$profile" == 1 ]]; then
        cmdshape --raw "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.configure stackdepth=128 \
          > "$run_dir/jfr-configure.txt"
        cmdshape --raw "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.start \
          name=benchmark settings=profile dumponexit=true filename="$run_dir/server.jfr" \
          'jdk.CPUTimeSample#enabled=true' 'jdk.CPUTimeSample#throttle=10ms' \
          > "$run_dir/jfr-start.txt"
        cmdshape --raw "$JAVA_HOME/bin/jcmd" "$server_pid" GC.heap_info \
          > "$run_dir/before-heap.txt"
      fi
      cmdshape --raw sed -n '1p' "/proc/$server_pid/stat" > "$run_dir/before-process-stat.txt"
      cmdshape --raw ps -p "$server_pid" -o pid,etimes,time,pcpu,rss,nlwp > "$run_dir/before-process.txt"
      cmdshape --raw date -u +%FT%TZ > "$run_dir/measurement-start.txt"
      if load measured "$duration"; then
        cmdshape --raw echo PASS > "$run_dir/outcome.txt"
      else
        failed=1
        cmdshape --raw echo FAILED > "$run_dir/outcome.txt"
      fi
      cmdshape --raw date -u +%FT%TZ > "$run_dir/measurement-end.txt"
      server_alive=0
      if cmdshape --raw kill -0 "$server_pid" 2>/dev/null && [[ -r "/proc/$server_pid/stat" ]]; then
        server_alive=1
        cmdshape --raw sed -n '1p' "/proc/$server_pid/stat" > "$run_dir/after-process-stat.txt" || cmdshape --raw true
        cmdshape --raw ps -p "$server_pid" -o pid,etimes,time,pcpu,rss,nlwp > "$run_dir/after-process.txt" || cmdshape --raw true
        cmdshape --raw echo ALIVE > "$run_dir/server-outcome.txt"
      else
        failed=1
        cmdshape --raw echo EXITED > "$run_dir/server-outcome.txt"
        cmdshape --raw echo FAILED > "$run_dir/outcome.txt"
      fi
      if [[ "$profile" == 1 && "$server_alive" == 1 ]]; then
        cmdshape --raw "$JAVA_HOME/bin/jcmd" "$server_pid" GC.heap_info \
          > "$run_dir/after-heap.txt"
        cmdshape --raw "$JAVA_HOME/bin/jcmd" "$server_pid" JFR.stop name=benchmark \
          > "$run_dir/jfr-stop.txt"
        cmdshape --raw "$JAVA_HOME/bin/jfr" summary "$run_dir/server.jfr" \
          > "$run_dir/jfr-summary.txt"
        cmdshape --raw "$JAVA_HOME/bin/jfr" view --width 160 allocation-by-site "$run_dir/server.jfr" \
          > "$run_dir/allocation-by-site.txt"
        cmdshape --raw "$JAVA_HOME/bin/jfr" view --width 160 allocation-by-class "$run_dir/server.jfr" \
          > "$run_dir/allocation-by-class.txt"
        cmdshape --raw "$JAVA_HOME/bin/jfr" view --width 160 hot-methods "$run_dir/server.jfr" \
          > "$run_dir/hot-methods.txt"
        cmdshape --raw "$JAVA_HOME/bin/jfr" view --width 160 gc-pauses "$run_dir/server.jfr" \
          > "$run_dir/gc-pauses.txt"
        cmdshape --raw "$JAVA_HOME/bin/jfr" view --width 160 gc-cpu-time "$run_dir/server.jfr" \
          > "$run_dir/gc-cpu-time.txt"
        cmdshape --raw "$JAVA_HOME/bin/jfr" print --json --events jdk.ObjectAllocationSample \
          --stack-depth 128 "$run_dir/server.jfr" > "$run_dir/allocation-samples.json"
      fi
      stop_server
      cmdshape --raw echo "END repeat=$repeat workload=$workload target=$target"
    done
  done
done
exit "$failed"
