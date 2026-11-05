def values: .values // .;
def usable($count; $latency):
  ($count | type == "number") and ($count <= 0 or ($latency | type == "number"));

.metrics as $metrics | $configuration[0] as $config |
($metrics.checks | values) as $checks |
($checks.passes | type == "number") and ($checks.fails | type == "number") and
(if $config.mode == "overload" then
  all($config.smoke_rates[]; . as $rate |
    usable(($metrics["http_reqs{scenario:step_\($rate)}"] | values | .count);
           ($metrics["http_req_duration{scenario:step_\($rate)}"] | values | .["p(95)"])))
else
  usable(($metrics[if $config.fixture.kind == "http" then "http_reqs{scenario:requests}" else "iterations" end] | values | .count);
         ($metrics[$config.fixture.metric] | values | .["p(95)"]))
end)
