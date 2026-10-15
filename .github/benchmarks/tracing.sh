#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

capture_process_cgroup() {
  local root=$1 membership=$2
  local relative current file
  if [[ ! -r "$membership" ]]; then
    printf 'Process cgroup membership: unavailable\n'
    return
  fi
  relative=$(awk -F: '$1 == "0" && $2 == "" {print substr($0, 4); exit}' "$membership")
  if [[ "$relative" != /* || "/$relative/" == *'/../'* || "/$relative/" == *'/./'* ]]; then
    printf 'Process cgroup v2 path: unavailable or outside the visible namespace\n'
    return
  fi
  root=${root%/}
  current="$root${relative%/}"
  if [[ ! -d "$current" ]]; then
    printf 'Process cgroup directory: unavailable (%s)\n' "$current"
    return
  fi
  printf 'Visible process/ancestor cgroup limits; namespace-hidden limits remain unknown.\n'
  while :; do
    for file in cpu.max memory.max; do
      printf '%s/%s: ' "$current" "$file"
      if [[ -r "$current/$file" ]]; then
        cat "$current/$file" || printf 'unavailable\n'
      else
        printf 'unavailable (no readable limit at this hierarchy level)\n'
      fi
    done
    [[ "$current" != "$root" ]] || break
    current=${current%/*}
  done
}

# Sourcing exposes only read-only metadata capture, without building or measuring anything.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return
fi

candidate=$1
baseline=$2
results=$3
expected_baseline=$4
mkdir -p "$results"
candidate_sha=$(git -C "$candidate" rev-parse HEAD)
baseline_sha=$(git -C "$baseline" rev-parse HEAD)
[[ "$candidate_sha" == "$GITHUB_SHA" && "$baseline_sha" == "$expected_baseline" ]]

libs="$candidate/microbenchmarks/build/install/microbenchmarks/lib"
candidate_core=("$libs"/shoostr-core-*.jar)
candidate_otel=("$libs"/shoostr-opentelemetry-*.jar)
baseline_core=("$baseline"/core/build/libs/shoostr-core-*.jar)
baseline_otel=("$baseline"/opentelemetry/build/libs/shoostr-opentelemetry-*.jar)
[[ ${#candidate_core[@]} == 1 && ${#candidate_otel[@]} == 1 ]]
[[ ${#baseline_core[@]} == 1 && ${#baseline_otel[@]} == 1 ]]

candidate_cp=''
baseline_cp="${baseline_core[0]}:${baseline_otel[0]}"
for jar in "$libs"/*.jar; do
  candidate_cp+="${candidate_cp:+:}$jar"
  if [[ "$jar" != "${candidate_core[0]}" && "$jar" != "${candidate_otel[0]}" ]]; then
    baseline_cp+=":$jar"
  fi
done
printf '%s\n' "$candidate_cp" > "$results/candidate-classpath.txt"
printf '%s\n' "$baseline_cp" > "$results/baseline-classpath.txt"
sha256sum "$libs"/*.jar "${baseline_core[0]}" "${baseline_otel[0]}" > "$results/checksums.txt"
{
  printf 'Candidate: %s\nBaseline: %s\nRunner image: %s %s\n' \
    "$candidate_sha" "$baseline_sha" "${ImageOS:-unknown}" "${ImageVersion:-unknown}"
  printf 'JMH forks: -Xms512m -Xmx512m -XX:+UseG1GC; driver: 128 MiB heap\n'
  printf 'No additional CPU affinity or process RAM cap is imposed in JMH mode.\n'
  java -Xms512m -Xmx512m -XX:+UseG1GC -XshowSettings:all -XX:+PrintFlagsFinal -version
  lscpu
  free -h
  ulimit -a
  cat /proc/self/status /proc/self/cgroup
  capture_process_cgroup /sys/fs/cgroup /proc/self/cgroup
} > "$results/environment.txt" 2>&1
{
  printf '## SDK tracing: paired JMH\n\nCandidate: %s; baseline: %s.\n\n' "$candidate_sha" "$baseline_sha"
  printf 'Same runner, fixture and dependencies; only project core/tracing jars differ. '
  printf 'Three forks, three 2s warmups and five 2s measurements per cell; GC profiling. '
  printf 'Warmup is not proof of JIT stability: inspect retained iteration trends.\n\n'
  printf 'This measures complete LocalConnector exchanges, not network throughput, exporters or helping frequency. '
  printf 'Heap: 512 MiB per fork, G1; no extra CPU affinity or total-memory cap. '
  printf 'HTTP-mode CPU/RAM inputs do not apply. Environment/flags are retained with native results.\n\n'
} >> "$GITHUB_STEP_SUMMARY"

configurations=(plain otelInstalled otelSelected otelUnselected otelAppWide)
executors=(default platform forkJoin)
for revision in baseline candidate; do
  classpath=$candidate_cp
  [[ "$revision" != baseline ]] || classpath=$baseline_cp
  java -Xms128m -Xmx128m -cp "$classpath" org.openjdk.jmh.Main ExtensionRequestBenchmark \
    -p configuration=plain,otelInstalled,otelSelected,otelUnselected,otelAppWide \
    -p execution=default,platform,forkJoin -p groups=1 -p outcome=matched \
    -f 0 -wi 0 -i 1 -r 100ms -foe true > "$results/$revision-smoke.log" 2>&1
done

cell=0
for configuration in "${configurations[@]}"; do
  for execution in "${executors[@]}"; do
    revisions=(baseline candidate)
    if ((cell % 2 == 1)); then
      revisions=(candidate baseline)
    fi
    for revision in "${revisions[@]}"; do
      classpath=$candidate_cp
      [[ "$revision" != baseline ]] || classpath=$baseline_cp
      output="$results/$configuration-$execution-$revision"
      java -Xms128m -Xmx128m -cp "$classpath" org.openjdk.jmh.Main ExtensionRequestBenchmark \
        -p configuration="$configuration" -p execution="$execution" -p groups=1 -p outcome=matched \
        -f 3 -wi 3 -i 5 -w 2s -r 2s -prof gc -foe true -rf json -rff "$output.json" \
        2>&1 | tee "$output.log"
      {
        printf '### %s / %s / %s\n\n```text\n' "$configuration" "$execution" "$revision"
        sed -n '/^Benchmark .*Mode /,$p' "$output.log"
        printf '```\n\n'
      } >> "$GITHUB_STEP_SUMMARY"
    done
    ((cell += 1))
  done
done

java -Xms128m -Xmx128m -cp "$candidate_cp" org.openjdk.jmh.Main ExtensionRequestBenchmark \
  -p configuration=otelSelected,otelUnselected -p execution=forkJoin -p groups=1 -p outcome=matched \
  -f 1 -wi 3 -i 5 -w 2s -r 2s -prof "jfr:dir=$results/jfr" -foe true \
  2>&1 | tee "$results/jfr.log"
recordings=("$results"/jfr/*/profile.jfr)
[[ ${#recordings[@]} == 2 ]]
for recording in "${recordings[@]}"; do
  jfr summary "$recording" > "${recording%.jfr}-summary.txt"
  jfr view --width 160 allocation-by-site "$recording" > "${recording%.jfr}-allocation.txt"
  jfr view --width 160 hot-methods "$recording" > "${recording%.jfr}-methods.txt"
done
printf 'Separate candidate JFR recordings and native views are retained; sampled profiles do not replace JMH bytes/op.\n' >> "$GITHUB_STEP_SUMMARY"
