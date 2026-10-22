-- HyperXR SDK test-suite. Run with a desktop LuaJIT 2.1 from the repository root:
--   luajit sdk/tests/run_tests.lua
-- Each test boots the real bootstrap.lua in a fresh state through the host stub.
package.path = "sdk/tests/?.lua;" .. package.path
local stub = require("host_stub")
local SDK = "app/src/main/assets/hxsdk"
local TMP = os.getenv("TMPDIR") or "/tmp"

local passed, failed = 0, 0
local function test(name, fn)
  local ok, err = pcall(fn)
  if ok then passed = passed + 1; print("ok    " .. name)
  else failed = failed + 1; print("FAIL  " .. name .. "\n      " .. tostring(err)) end
end
local function eq(a, b, msg) if a ~= b then error((msg or "") .. " expected " .. tostring(b) .. " got " .. tostring(a), 2) end end
local function near(a, b, tol, msg) if math.abs(a - b) > tol then error((msg or "") .. " expected ~" .. b .. " got " .. a, 2) end end
local function truthy(v, msg) if not v then error(msg or "expected truthy", 2) end end

local counter = 0
local function package_(main, extra)
  counter = counter + 1
  local dir = TMP .. "/hxtest_" .. counter
  os.execute("mkdir -p " .. dir)
  local f = io.open(dir .. "/main.lua", "w"); f:write(main); f:close()
  for name, src in pairs(extra or {}) do
    os.execute("mkdir -p " .. dir .. "/" .. (name:match("(.*)/") or "."))
    local g = io.open(dir .. "/" .. name, "w"); g:write(src); g:close()
  end
  return dir
end

local function boot(main, perms, opts, extra)
  opts = opts or {}
  local p = {}
  for _, k in ipairs(perms or {}) do p[k] = true end
  local cfg = { id = "com.test.pkg", name = "Test", version = "1.0.0", mode = opts.mode or "scene", entry = "main.lua",
    perms = p, strict = opts.strict, budget = opts.budget, memoryMB = opts.memoryMB }
  return stub.boot(SDK, package_(main, extra), cfg)
end

-- ------------------------------------------------------------------ sandbox
test("sandbox hides dangerous globals", function()
  local rt, n = boot([[
    results = {}
    results.io = io == nil
    results.os_execute = os.execute == nil
    results.debug = debug == nil
    results.ffi = ffi == nil and (pcall(require, "ffi") == false)
    results.jit = jit == nil
    results.package = package == nil
    results.dofile = dofile == nil and loadfile == nil
    results.dump = string.dump == nil
    results.strmeta = getmetatable("") == false
    results.gc = pcall(collectgarbage, "stop") == false
    results.bytecode = select(2, load("\27LJ\2junk")) ~= nil
    results.hx_readonly = pcall(function() hx.scene = nil end) == false
    results.no_net = hx.net == nil
    results.no_camera = hx.camera == nil
    results.has_ui = hx.ui ~= nil
    results.require_traversal = pcall(require, "..secret") == false
  ]], { "ui" })
  rt.frame(1 / 60)
  local env = rt.sandbox
  eq(#n.errors, 0, "errors: " .. table.concat(n.errors, ";"))
  for k, v in pairs(env.results) do truthy(v, "check failed: " .. k) end
end)

test("load always binds the sandbox environment", function()
  local rt, n = boot([[
    local f = load("return io")
    leaked = f()
    local g = load("x = 5")
    g()
  ]], {})
  rt.frame(1 / 60)
  eq(rt.sandbox.leaked, nil)
  eq(rt.sandbox.x, 5)
end)

test("execution budget stops runaway scripts (strict interpreter mode)", function()
  local rt, n = boot([[
    function onUpdate(dt)
      while true do end
    end
  ]], { "ui" }, { strict = true, budget = 200000 })
  rt.frame(1 / 60)
  truthy(rt.isCrashed(), "should have crashed")
  truthy(n.errors[1]:find("budget"), n.errors[1])
end)

test("pcall cannot swallow a budget kill", function()
  local rt, n = boot([[
    function onUpdate(dt)
      for i = 1, 100 do pcall(function() while true do end end) end
      survived = true
    end
  ]], {}, { strict = true, budget = 100000 })
  rt.frame(1 / 60)
  truthy(rt.isCrashed())
  eq(rt.sandbox.survived, nil)
end)

test("memory limit enforced", function()
  local rt, n = boot([[
    function onUpdate()
      local t = {}
      for i = 1, 1e8 do t[i] = {i, i, i} end
    end
  ]], {}, { strict = true, budget = 200000, memoryMB = 8 })
  rt.frame(1 / 60)
  truthy(rt.isCrashed())
end)

test("script errors crash cleanly with an error panel", function()
  local rt, n = boot([[ function onUpdate() local x = nil; x.y = 1 end ]], { "ui" })
  rt.frame(1 / 60)
  truthy(rt.isCrashed())
  rt.frame(1 / 60)
  truthy(next(n.panels) ~= nil, "error panel shown")
end)

test("package-local require works", function()
  local rt, n = boot([[ local m = require("lib.util"); value = m.answer ]], {}, nil,
    { ["lib/util.lua"] = "return { answer = 42 }" })
  rt.frame(1 / 60)
  eq(rt.sandbox.value, 42)
end)

test("filesystem and network APIs require their permissions", function()
  local rt = boot([[ hasFs = hx.fs ~= nil; hasNet = hx.net ~= nil; hasHands = hx.hands ~= nil ]], { "filesystem" })
  rt.frame(1 / 60)
  eq(rt.sandbox.hasFs, true); eq(rt.sandbox.hasNet, false); eq(rt.sandbox.hasHands, false)
end)

-- ------------------------------------------------------------------ physics
local function physicsEnv(main) return boot(main, { "physics" }) end

test("box settles on the ground without sinking", function()
  local rt = physicsEnv([[
    world = hx.physics.world{ auto = false }
    ground = hx.scene.spawn{ position = hx.V3(0, -0.5, 0), scale = hx.V3(10, 1, 10), body = { world = world, mass = 0 } }
    box = hx.scene.spawn{ position = hx.V3(0, 2, 0), scale = hx.V3(0.4, 0.4, 0.4), body = { world = world, mass = 2 } }
    for i = 1, 240 do world:step(1/60) end
    y = box.body.pos.y
    sleeping = box.body.sleeping
  ]])
  rt.frame(1 / 60)
  near(rt.sandbox.y, 0.2, 0.02, "resting height")
  eq(rt.sandbox.sleeping, true, "should sleep")
end)

test("stack of three boxes stays stable", function()
  local rt = physicsEnv([[
    world = hx.physics.world{ auto = false }
    hx.scene.spawn{ position = hx.V3(0, -0.5, 0), scale = hx.V3(10, 1, 10), body = { world = world } }
    boxes = {}
    for i = 1, 3 do boxes[i] = hx.scene.spawn{ position = hx.V3(0, 0.25 + (i-1) * 0.5, 0), scale = hx.V3(0.5,0.5,0.5), body = { world = world, mass = 1 } } end
    for i = 1, 300 do world:step(1/60) end
    top = boxes[3].body.pos
  ]])
  rt.frame(1 / 60)
  local top = rt.sandbox.top
  near(top.y, 1.25, 0.04, "top height")
  near(top.x, 0, 0.03, "top drift x")
end)

test("sphere bounces with restitution and rolls to rest", function()
  local rt = physicsEnv([[
    world = hx.physics.world{ auto = false }
    hx.scene.spawn{ position = hx.V3(0, -0.5, 0), scale = hx.V3(10, 1, 10), body = { world = world } }
    ball = hx.scene.spawn{ mesh = hx.scene.mesh.sphere, position = hx.V3(0, 2, 0), scale = 0.2, body = { world = world, mass = 0.5, restitution = 0.8 } }
    maxAfterBounce = 0
    local bounced = false
    for i = 1, 240 do
      world:step(1/60)
      local v = ball.body.vel.y
      if v > 0.5 then bounced = true end
      if bounced then maxAfterBounce = math.max(maxAfterBounce, ball.body.pos.y) end
    end
  ]])
  rt.frame(1 / 60)
  truthy(rt.sandbox.maxAfterBounce > 0.8, "bounce height " .. rt.sandbox.maxAfterBounce)
end)

test("raycast hits nearest body with correct normal", function()
  local rt = physicsEnv([[
    world = hx.physics.world{ auto = false }
    near_ = hx.scene.spawn{ position = hx.V3(0, 0, -2), body = { world = world } }
    far_ = hx.scene.spawn{ position = hx.V3(0, 0, -5), body = { world = world } }
    hit = world:raycast(hx.V3(0, 0, 0), hx.V3(0, 0, -1), 20)
    miss = world:raycast(hx.V3(0, 0, 0), hx.V3(0, 1, 0), 20)
  ]])
  rt.frame(1 / 60)
  local hit = rt.sandbox.hit
  truthy(hit and hit.entity == rt.sandbox.near_)
  near(hit.distance, 1.5, 1e-4)
  near(hit.normal.z, 1, 1e-4)
  eq(rt.sandbox.miss, nil)
end)

test("hinged door swings but stays on its hinge", function()
  local rt = physicsEnv([[
    world = hx.physics.world{ auto = false, gravity = hx.V3(0, 0, 0) }
    door = hx.scene.spawn{ position = hx.V3(0.5, 1, 0), scale = hx.V3(1, 2, 0.05), body = { world = world, mass = 5 } }
    world:hinge(door, hx.V3(0, 1, 0), hx.V3(0, 1, 0))
    world:impulse(door, hx.V3(0, 0, -3), hx.V3(1, 1, 0))
    for i = 1, 60 do world:step(1/60) end
    center = door.body.pos
    yaw = hx.quat.yaw(door.body.rot)
  ]])
  rt.frame(1 / 60)
  local c = rt.sandbox.center
  near(math.sqrt(c.x * c.x + c.z * c.z), 0.5, 0.03, "distance from hinge")
  truthy(math.abs(rt.sandbox.yaw) > 0.3, "door should rotate, yaw=" .. rt.sandbox.yaw)
end)

test("time scale slows the simulation", function()
  local rt = physicsEnv([[
    world = hx.physics.world{ auto = false, gravity = hx.V3(0, 0, 0) }
    a = hx.scene.spawn{ mesh = hx.scene.mesh.sphere, scale = 0.1, body = { world = world, mass = 1, velocity = hx.V3(1, 0, 0) } }
    world:setTimeScale(0.1)
    for i = 1, 60 do world:step(1/60) end
  ]])
  rt.frame(1 / 60)
  near(rt.sandbox.a.body.pos.x, 0.1, 0.01)
end)

-- ------------------------------------------------------------------ UI + input
test("button click via gaze ray and screen tap", function()
  local rt, n = boot([[
    clicks = 0
    panel = hx.ui.panel{ width = 0.6, height = 0.4, position = hx.V3(0, 1.6, -1) }
    btn = panel:add(hx.ui.button{ text = "Go", flex = 1, onClick = function() clicks = clicks + 1 end })
  ]], { "ui", "input", "spatial" })
  rt.frame(1 / 60)
  truthy(#n.uiCmds > 0, "panel drew commands")
  truthy(n.panels[1], "panel submitted")
  n.inputs[241] = 1; rt.frame(1 / 60)
  n.inputs[241] = 1; rt.frame(1 / 60)
  n.inputs[241] = 0; rt.frame(1 / 60)
  eq(rt.sandbox.clicks, 1, "exactly one click")
end)

test("joy-con R button selects with controller ray", function()
  local rt, n = boot([[
    clicks = 0
    panel = hx.ui.panel{ width = 0.6, height = 0.4, position = hx.V3(0.5, 1.2, -1) }
    panel:add(hx.ui.button{ text = "A", flex = 1, onClick = function() clicks = clicks + 1 end })
  ]], { "ui", "input" })
  local c = 208
  n.inputs[c] = 1; n.inputs[c + 1] = 0.5; n.inputs[c + 2] = 1.2; n.inputs[c + 3] = 0
  rt.frame(1 / 60)
  n.inputs[c + 8] = 1; rt.frame(1 / 60); rt.frame(1 / 60)
  n.inputs[c + 8] = 0; rt.frame(1 / 60)
  eq(rt.sandbox.clicks, 1)
end)

test("direct touch presses a button with the index fingertip", function()
  local rt, n = boot([[
    clicks = 0
    panel = hx.ui.panel{ width = 0.4, height = 0.3, position = hx.V3(0, 1.3, -0.4) }
    panel:add(hx.ui.button{ text = "T", flex = 1, onClick = function() clicks = clicks + 1 end })
  ]], { "ui", "input", "hand_tracking" })
  local h = 96
  n.inputs[h] = 1; n.inputs[h + 1] = 0.9
  local function tip(z) n.inputs[h + 5 + 8 * 3] = 0; n.inputs[h + 5 + 8 * 3 + 1] = 1.3; n.inputs[h + 5 + 8 * 3 + 2] = z end
  tip(-0.35); rt.frame(1 / 60)
  tip(-0.395); rt.frame(1 / 60)
  tip(-0.398); rt.frame(1 / 60)
  tip(-0.35); rt.frame(1 / 60)
  eq(rt.sandbox.clicks, 1)
end)

test("gaze dwell clicks a button once without any tap", function()
  local rt, n = boot([[
    clicks = 0
    panel = hx.ui.panel{ width = 0.6, height = 0.4, position = hx.V3(0, 1.6, -1) }
    panel:add(hx.ui.button{ text = "Go", flex = 1, onClick = function() clicks = clicks + 1 end })
  ]], { "ui", "input", "spatial" })
  n.inputs[242] = 1.0 -- dwell seconds
  for i = 1, 180 do rt.frame(1 / 60) end -- 3 s staring at the button
  eq(rt.sandbox.clicks, 1, "exactly one dwell click")
end)

test("pinch on a hand ray clicks a far button", function()
  local rt, n = boot([[
    clicks = 0
    panel = hx.ui.panel{ width = 0.8, height = 0.6, position = hx.V3(0.4, 1.2, -1.5) }
    panel:add(hx.ui.button{ text = "Far", flex = 1, onClick = function() clicks = clicks + 1 end })
  ]], { "ui", "input", "hand_tracking" })
  local h = 96
  n.inputs[h] = 1; n.inputs[h + 1] = 0.9
  -- index MCP (joint 5) on the line from the right shoulder (0.17, 1.38, 0.05) to the panel centre
  local sx, sy, sz = 0.17, 1.38, 0.05
  local tx, ty, tz = 0.4, 1.2, -1.5
  local k = 0.3
  n.inputs[h + 5 + 5 * 3] = sx + (tx - sx) * k; n.inputs[h + 5 + 5 * 3 + 1] = sy + (ty - sy) * k; n.inputs[h + 5 + 5 * 3 + 2] = sz + (tz - sz) * k
  n.inputs[h + 3] = 1; rt.frame(1 / 60)           -- open palm: hover
  n.inputs[h + 3] = 2; rt.frame(1 / 60); rt.frame(1 / 60) -- pinch
  n.inputs[h + 3] = 1; rt.frame(1 / 60)           -- release
  eq(rt.sandbox.clicks, 1)
end)

test("list selection and window mode", function()
  local rt, n = boot([[
    selected = nil
    list = hx.window:add(hx.ui.list{ items = { "a", "b", "c" }, flex = 1, itemHeight = 100, onSelect = function(i) selected = i end })
  ]], { "ui", "input" }, { mode = "window" })
  rt.frame(1 / 60)
  -- the window is 0.9 x 0.6 at z=-1.1, y=1.45; first item lies near the top (pixels 28..128)
  -- aim the gaze at panel pixel y ~ 80 => y_world = 1.45 + 0.3 - 0.08
  local y = 1.45 + 0.3 - 0.08
  local pitch = math.atan2(y - 1.6, 1.1)
  n.inputs[7] = math.sin(pitch / 2); n.inputs[10] = math.cos(pitch / 2)
  rt.frame(1 / 60)
  n.inputs[241] = 1; rt.frame(1 / 60)
  n.inputs[241] = 0; rt.frame(1 / 60)
  eq(rt.sandbox.selected, 1)
end)

test("tweens animate and finish", function()
  local rt = boot([[
    obj = { v = 0 }
    hx.ui.tween(obj, "v", 10, 0.5, "linear", function() done = true end)
  ]], { "ui" })
  for i = 1, 40 do rt.frame(1 / 60) end
  near(rt.sandbox.obj.v, 10, 1e-6)
  eq(rt.sandbox.done, true)
end)

-- ------------------------------------------------------------------ bundled packages
local function bootGame(dir, perms, mode, frames)
  local p = {}
  for _, k in ipairs(perms) do p[k] = true end
  local cfg = { id = "pkg", name = dir, version = "1.0.0", mode = mode, entry = "main.lua", perms = p }
  local rt, n = stub.boot(SDK, dir, cfg)
  local t = 0
  for i = 1, frames do
    t = t + 1 / 60
    -- scripted input: look around, wave the right hand, press buttons periodically
    n.inputs[0] = t
    local yaw = math.sin(t * 0.7) * 0.6
    n.inputs[8] = math.sin(yaw / 2); n.inputs[10] = math.cos(yaw / 2)
    local h = 96
    n.inputs[h] = 1; n.inputs[h + 1] = 0.9; n.inputs[h + 2] = (i % 90 < 30) and 1 or 0; n.inputs[h + 3] = (i % 90 < 30) and 2 or 1
    for j = 0, 20 do
      n.inputs[h + 5 + j * 3] = 0.25 + math.sin(t * 3) * 0.2
      n.inputs[h + 5 + j * 3 + 1] = 1.1 + math.cos(t * 2) * 0.3 + j * 0.005
      n.inputs[h + 5 + j * 3 + 2] = -0.35
    end
    local l = 16
    n.inputs[l] = 1; n.inputs[l + 1] = 0.9
    for j = 0, 20 do
      n.inputs[l + 5 + j * 3] = -0.25 - math.sin(t * 3) * 0.2
      n.inputs[l + 5 + j * 3 + 1] = 1.1 - math.cos(t * 2) * 0.3
      n.inputs[l + 5 + j * 3 + 2] = -0.3
    end
    n.inputs[241] = (i % 120 == 0) and 1 or 0
    n.inputs[208] = 1; n.inputs[208 + 1] = 0.2; n.inputs[208 + 2] = 1.2; n.inputs[208 + 3] = -0.2
    n.inputs[208 + 8] = (i % 45 < 5) and 1 or 0
    rt.frame(1 / 60)
    if rt.isCrashed() then error("crashed at frame " .. i .. ": " .. rt.isCrashed()) end
  end
  return rt, n
end

for _, g in ipairs({
  { "games/stillshot", { "ui", "audio", "input", "spatial", "physics", "hand_tracking" }, "scene" },
  { "games/forgelab", { "ui", "audio", "input", "spatial", "physics", "hand_tracking" }, "scene" },
  { "games/primalrush", { "ui", "audio", "input", "spatial", "physics", "hand_tracking" }, "scene" },
  { "games/clock", { "ui", "input" }, "window" },
  { "games/calculator", { "ui", "input" }, "window" },
  { "games/calendar", { "ui", "input", "filesystem" }, "window" },
}) do
  local f = io.open(g[1] .. "/main.lua")
  if f then
    f:close()
    test("package " .. g[1] .. " runs 1200 frames", function()
      local rt, n = bootGame(g[1], g[2], g[3], 1200)
      truthy(n.draws > 0 or next(n.panels) ~= nil, "renders something")
    end)
  end
end

test("calculator parser: precedence, errors and no code execution", function()
  local rt = select(1, bootGame("games/calculator", { "ui", "input" }, "window", 2))
  local ev = rt.sandbox.calculator_evaluate
  eq(ev("2+3*4"), 14)
  eq(ev("(2+3)*4"), 20)
  eq(ev("-2^2"), -4)
  eq(ev("2^3^2"), 512)
  eq(select(2, ev("1/0")), "divisão por zero")
  eq(ev("os.exit()"), nil)
  eq(ev("1.2.3"), nil)
end)

print(string.format("\n%d passed, %d failed", passed, failed))
os.exit(failed == 0 and 0 or 1)
