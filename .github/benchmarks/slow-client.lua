local role

function init(args)
  role = args[1]
  assert(role == "health" or role == "blocked", "expected health or blocked role")
end

function response(status, headers, body)
  assert(status == 200, role .. "-failure: unexpected status")
  assert(role == "health" and body == "healthy"
      or role == "blocked" and (body == "elapsed" or body == "released"),
    role .. "-failure: unexpected body")
end
