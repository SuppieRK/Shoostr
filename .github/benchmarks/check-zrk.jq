# Check collection and response validation; throughput shortfall is reported natively.
def nonnegative: type == "number" and . >= 0;
.zrk_version == "3.0.0" and
(.requests | type == "number" and . > 0) and
(.duration_s | type == "number" and . >= $seconds) and
(.config.closed == false) and
(.config.connections == $connections) and
(.config.launched == $connections) and
(.config.streams == 1) and
(.config.disable_keepalive == false) and
(.config.deadline_ms == 0) and
(.config.record_timeouts == true) and
(.config.duration_s == $seconds) and
(.config.target_rate == $rate) and
(.config.target_rate_end == $end_rate) and
(.workload.response.calls == .requests) and
(.latency_us.p99 | nonnegative) and
(.achieved_rate | nonnegative) and
(.max_schedule_lag_us | nonnegative) and
(.latency_histogram | type == "string" and length > 0) and
([.errors.connect, .errors.read, .errors.write, .errors.timeout, .errors.deadline] | all(. == 0)) and
(.status_codes | to_entries | all(
  if .key == $status_class then .value > 0 else .value == 0 end)) and
([.status_codes[]] | add) == .requests and
(.errors.non_2xx_3xx == (if $status_class == "4xx" then .requests else 0 end))
