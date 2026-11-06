local chunk = string.rep("x", 1023) .. "\n"
local fixtures = {
  plaintext = {200, "Hello, World!", "text/plain;charset=utf-8"},
  ["json-bytes"] = {200, '{"message":"Hello, World!"}', "application/json"},
  echo = {200, chunk, "application/octet-stream"},
  stream = {200, string.rep(chunk, 16), "application/octet-stream"},
  ["route-literal"] = {200, "Hello, World!", "text/plain;charset=utf-8"},
  ["route-parameter"] = {200, "order-42", "text/plain;charset=utf-8"},
  ["not-found"] = {404, "Not found", "text/plain;charset=utf-8"},
  ["wrong-method"] = {405, "Method not allowed", "text/plain;charset=utf-8"},
  ["static-shallow"] = {200, string.rep("x", 1024), "application/octet-stream"},
  ["static-nested"] = {200, string.rep("x", 1024), "application/octet-stream"}
}
local events = {}
for index = 0, 15 do events[#events + 1] = "data: " .. index .. ":" .. string.rep("x", 128) .. "\n\n" end
fixtures["sse-burst"] = {200, table.concat(events), "text/event-stream;charset=utf-8"}
fixtures["sse-paced"] = fixtures["sse-burst"]
local selected, expected, groups, files, iteration
local threads = 0

function setup(thread)
  threads = threads + 1
  thread:set("id", threads)
end

function init(args)
  selected = args[1] or "plaintext"
  expected = assert(fixtures[selected], "unknown workload")
  groups, files = tonumber(args[2] or "1000"), tonumber(args[3] or "1")
  assert(groups and groups >= 1 and groups % 1 == 0, "invalid route groups")
  assert(files and files >= 1 and files % 1 == 0, "invalid static files")
  iteration = id - 1
end

function request()
  local index = iteration
  iteration = iteration + 1
  if selected == "echo" then
    return wrk.format("POST", "/echo", {["Content-Type"] = "application/octet-stream"}, chunk)
  end
  if string.sub(selected, 1, 7) == "static-" then
    local directory = selected == "static-nested" and "nested/deeper/" or ""
    return wrk.format("GET", "/static/" .. directory .. "file-" .. (index % files) .. ".bin")
  end
  if selected == "route-literal" or selected == "route-parameter"
      or selected == "not-found" or selected == "wrong-method" then
    local prefix = "/api/resources/" .. ((index * 977) % groups) .. "/orders"
    local suffix = selected == "not-found" and "/missing/extra"
      or selected == "route-parameter" and "/order-42" or "/latest"
    return wrk.format(selected == "wrong-method" and "DELETE" or "GET", prefix .. suffix)
  end
  return wrk.format("GET", "/" .. selected)
end

function response(status, headers, body)
  assert(status == expected[1], "unexpected status: " .. status)
  assert(body == expected[2], "unexpected body")
  local normalized = {}
  for name, value in pairs(headers) do normalized[string.lower(name)] = value end
  assert(string.gsub(normalized["content-type"] or "", "%s", "") == expected[3], "unexpected content type")
  assert(normalized.date and string.match(normalized.date, "GMT$"), "missing HTTP date")
  if selected == "stream" or selected == "sse-burst" or selected == "sse-paced" then
    assert(string.lower(normalized["transfer-encoding"] or "") == "chunked", "expected chunked response")
  else
    assert(tonumber(normalized["content-length"]) == #expected[2], "unexpected content length")
  end
  if selected == "wrong-method" then
    local methods = {}
    for method in string.gmatch(normalized.allow or "", "[^, ]+") do methods[#methods + 1] = method end
    table.sort(methods)
    assert(table.concat(methods, ",") == "GET,POST", "unexpected allowed methods")
  end
  if string.sub(selected, 1, 7) == "static-" then
    assert(normalized["cache-control"] == "no-cache", "unexpected cache policy")
    assert(normalized["accept-ranges"] == "bytes", "unexpected range support")
    assert(string.match(normalized.etag or "", '^W/".+"$'), "missing weak ETag")
    assert(string.match(normalized["last-modified"] or "", "GMT$"), "missing modification date")
  end
end
