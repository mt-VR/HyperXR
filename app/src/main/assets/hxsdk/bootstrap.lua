-- HyperXR SDK bootstrap (trusted). Runs with the full LuaJIT standard library, builds the
-- `hx` API for one package and executes the package inside a restricted environment.
--
-- Called by the native bridge as: bootstrap(native, config) -> { frame=, event=, shutdown= }
--   native : table of C functions (see app/src/main/cpp/lua_bridge.cpp)
--   config : { id, name, version, mode, entry, perms = {key=true}, strict, budget, memoryMB, window = {w,h} }
local N, config = ...

-- ------------------------------------------------------------------ privileged references
local _G0 = _G
local load, pcall, xpcall, error, type, tostring, tonumber = load, pcall, xpcall, error, type, tostring, tonumber
local pairs, ipairs, next, select, setmetatable, getmetatable = pairs, ipairs, next, select, setmetatable, getmetatable
local rawget, rawset, rawequal, unpack, assert = rawget, rawset, rawequal, unpack, assert
local string, table, math, os, coroutine, bit = string, table, math, os, coroutine, bit
local debug, jit_ = debug, rawget(_G0, "jit")
local collectgarbage = collectgarbage
local traceback = debug.traceback
local floor = math.floor

local perms = config.perms or {}
local unsafe = perms.unsafe == true
local strict = config.strict == true and not unsafe
local budget = config.budget or 20000000          -- VM instructions per callback (interpreter only)
local memoryLimitKb = (config.memoryMB or 192) * 1024

-- Shared string metatable must not be reachable from sandboxed code.
local stringMeta = debug.getmetatable("")
stringMeta.__metatable = false

if strict and jit_ then jit_.off() end

local function log(level, ...)
  local parts = {}
  for i = 1, select("#", ...) do parts[#parts + 1] = tostring((select(i, ...))) end
  N.log(level, table.concat(parts, " "))
end

-- ------------------------------------------------------------------ SDK modules (trusted)
local function loadSdk(name, ...)
  local src = N.sdk(name)
  if not src then error("missing SDK module " .. name) end
  local fn = assert(load(src, "@hxsdk/" .. name .. ".lua", "t", _G0))
  return fn(...)
end

local vm = loadSdk("vmath")
local V, Q = vm.V, vm.Q
local physicsLib = loadSdk("physics", vm)

-- ------------------------------------------------------------------ text texture cache
local text = {}
do
  local cache, count, frame = {}, 0, 0
  function text.get(str, size, c, bold, maxWidth)
    c = c or { 1, 1, 1, 1 }
    local key = str .. "\0" .. size .. "\0" .. c[1] .. "," .. c[2] .. "," .. c[3] .. "," .. (c[4] or 1) .. (bold and "b" or "") .. (maxWidth or "")
    local e = cache[key]
    if not e then
      local id, w, h = N.call("text", str, size, c[1], c[2], c[3], c[4] or 1, bold and 1 or 0, maxWidth or 0)
      e = { id = id or 0, w = w or 0, h = h or 0, key = key }
      cache[key] = e
      count = count + 1
    end
    e.used = frame
    return e
  end
  function text.touch(e) e.used = frame end
  function text.frame()
    frame = frame + 1
    if frame % 120 == 0 and count > 64 then
      for k, e in pairs(cache) do
        if frame - e.used > 600 then N.call("free_texture", e.id); cache[k] = nil; count = count - 1 end
      end
    end
  end
end

local ui = loadSdk("ui", { native = N, vmath = vm, text = text })

-- ------------------------------------------------------------------ input
local IN = {
  TIME = 0, DT = 1, MR = 2, HANDS_AVAILABLE = 3,
  HEAD_POS = 4, HEAD_ROT = 7,
  HAND = { [0] = 16, [1] = 96 }, HAND_STRIDE = 80,
  CTRL = { [0] = 176, [1] = 208 },
  GAZE = 240,
}
local BUTTONS = { R = 1, L = 2, ZR = 4, ZL = 8, PLUS = 16, MINUS = 32, HOME = 64, A = 128, B = 256, X = 512, Y = 1024, SL = 2048, SR = 4096, STICK = 8192 }
local GESTURES = { [0] = "none", "open_palm", "pinch", "point", "fist", "grab" }
local f = N.input

local rig = { pos = V(0, 0, 0), rot = Q.identity() }
local function toWorld(p) return rig.pos + Q.rotate(rig.rot, p) end
local function rotToWorld(q) return rig.rot * q end

local state = {
  head = { position = V(0, 1.6, 0), rotation = Q.identity() },
  hands = {}, controllers = {}, prevButtons = { [0] = 0, [1] = 0 }, buttons = { [0] = 0, [1] = 0 },
  tap = false, prevTap = false,
}

local function readInput()
  local hp = V(f(IN.HEAD_POS), f(IN.HEAD_POS + 1), f(IN.HEAD_POS + 2))
  local hq = Q.new(f(IN.HEAD_ROT), f(IN.HEAD_ROT + 1), f(IN.HEAD_ROT + 2), f(IN.HEAD_ROT + 3))
  state.head.local_ = { position = hp, rotation = hq }
  state.head.position = toWorld(hp)
  state.head.rotation = rotToWorld(hq)
  for h = 0, 1 do
    local base = IN.HAND[h]
    local hand = state.hands[h] or { joints = {} }
    hand.tracked = f(base) > 0.5
    hand.confidence = f(base + 1)
    hand.pinch = f(base + 2)
    hand.gesture = GESTURES[floor(f(base + 3))] or "none"
    hand.depthEstimated = f(base + 4) > 1.5
    if hand.tracked then
      for j = 0, 20 do
        local o = base + 5 + j * 3
        hand.joints[j] = toWorld(V(f(o), f(o + 1), f(o + 2)))
      end
    end
    state.hands[h] = hand
    local cb = IN.CTRL[h]
    local c = state.controllers[h] or {}
    c.connected = f(cb) > 0.5
    c.origin = toWorld(V(f(cb + 1), f(cb + 2), f(cb + 3)))
    c.rotation = rotToWorld(Q.new(f(cb + 4), f(cb + 5), f(cb + 6), f(cb + 7)))
    c.direction = Q.forward(c.rotation)
    state.prevButtons[h] = state.buttons[h]
    state.buttons[h] = c.connected and floor(f(cb + 8)) or 0
    c.stick = { x = f(cb + 9), y = f(cb + 10) }
    c.positionFromHand = f(cb + 11) > 0.5
    state.controllers[h] = c
  end
  state.prevTap = state.tap
  state.tap = f(IN.GAZE + 1) > 0.5
end

local function pointers()
  local list = {}
  local anyController = false
  for h = 0, 1 do
    local c = state.controllers[h]
    if c.connected then
      anyController = true
      list[#list + 1] = { id = 10 + h, ray = { o = c.origin, d = c.direction }, down = bit.band(state.buttons[h], BUTTONS.R + BUTTONS.ZR + BUTTONS.A) ~= 0 }
    end
  end
  for h = 0, 1 do
    local hand = state.hands[h]
    if hand.tracked and not anyController then
      list[#list + 1] = { id = 20 + h, touch = hand.joints[8] }
    end
  end
  -- gaze pointer with screen tap / viewer button, always available
  list[#list + 1] = { id = 1, ray = { o = state.head.position, d = Q.forward(state.head.rotation) }, down = state.tap }
  return list
end

-- ------------------------------------------------------------------ scene
local MESH = { cube = 1, sphere = 2, plane = 3, cylinder = 4, quad = 5 }
local FLAG = { MODEL = 1, UNLIT = 2, DOUBLE_SIDED = 4, TRANSPARENT = 8, CAMERA = 16 }
local entities, entityCount = {}, 0
local worlds = {}
local header = {
  lightDir = V(-0.4, -1, -0.3), lightColor = { 1, 0.97, 0.92 }, ambient = { 0.32, 0.33, 0.4 },
  clear = { 0.05, 0.05, 0.08, 1 }, background = 1,
}

local Entity = {}
Entity.__index = Entity
function Entity:setPosition(p)
  self.position = V.copy(p)
  if self.body then self.body.pos = V.copy(p); self.body.vel = V(0, 0, 0); self.body.sleeping = false end
  return self
end
function Entity:setRotation(q)
  self.rotation = Q.copy(q)
  if self.body then self.body.rot = Q.copy(q); self.body.angVel = V(0, 0, 0) end
  return self
end
function Entity:setScale(s) self.scale = type(s) == "number" and V(s, s, s) or V.copy(s) return self end
function Entity:setColor(r, g, b, a)
  if type(r) == "table" then r, g, b, a = r[1], r[2], r[3], r[4] end
  self.color = { r, g, b, a or 1 }
  return self
end
function Entity:setVisible(v) self.visible = v ~= false return self end
function Entity:attach(parent) self.parent = parent return self end
function Entity:worldPose()
  local p, r = self.position, self.rotation
  local par = self.parent
  local depth = 0
  while par and depth < 16 do
    p = par.position + Q.rotate(par.rotation, p) -- offsets are metres in the parent frame; scale is not inherited
    r = par.rotation * r
    par = par.parent
    depth = depth + 1
  end
  return p, r
end
function Entity:destroy()
  if self.destroyed then return end
  self.destroyed = true
  if self.body and self.world then self.world:remove(self.body) end
  entities[self] = nil
  entityCount = entityCount - 1
end

local scene = {}
scene.mesh = MESH

--- hx.scene.spawn{mesh=hx.scene.mesh.cube | model=handle, position, rotation, scale, color={r,g,b,a},
---                texture=, unlit=, transparent=, doubleSided=, body={world=, mass=, shape=, ...}, name=}
function scene.spawn(def)
  def = def or {}
  if entityCount >= 4000 then error("entity limit reached (4000)", 2) end
  local e = setmetatable({
    name = def.name,
    mesh = def.mesh or (def.model and 0) or MESH.cube,
    model = def.model,
    position = def.position and V.copy(def.position) or V(0, 0, 0),
    rotation = def.rotation and Q.copy(def.rotation) or Q.identity(),
    scale = def.scale and (type(def.scale) == "number" and V(def.scale, def.scale, def.scale) or V.copy(def.scale)) or V(1, 1, 1),
    color = def.color or { 1, 1, 1, 1 },
    texture = def.texture or 0,
    visible = def.visible ~= false,
    flags = (def.model and FLAG.MODEL or 0) + (def.unlit and FLAG.UNLIT or 0) + (def.doubleSided and FLAG.DOUBLE_SIDED or 0)
      + (def.transparent and FLAG.TRANSPARENT or 0) + (def.camera and FLAG.CAMERA or 0),
    userData = def.userData,
  }, Entity)
  if def.body then
    local w = def.body.world
    if not w then error("body.world is required", 2) end
    local bd = {}
    for k, v in pairs(def.body) do bd[k] = v end
    bd.world = nil
    bd.position = e.position
    bd.rotation = e.rotation
    if not bd.shape then bd.shape = (e.mesh == MESH.sphere) and "sphere" or "box" end
    if bd.shape == "sphere" and not bd.radius then bd.radius = 0.5 * e.scale.x end
    if bd.shape == "box" and not bd.size then bd.size = V.copy(e.scale) end
    bd.userData = e
    e.body = w._raw:add(bd)
    e.world = w._raw
  end
  entities[e] = true
  entityCount = entityCount + 1
  return e
end

function scene.count() return entityCount end
function scene.clear() for e in pairs(entities) do e:destroy() end end
function scene.setLight(dir, colorRGB, ambientRGB)
  header.lightDir = V.normalize(dir)
  if colorRGB then header.lightColor = colorRGB end
  if ambientRGB then header.ambient = ambientRGB end
end
--- "color" (solid clear colour), "environment" (current HyperXR environment) or "passthrough" (camera when MR is active)
function scene.setBackground(mode, clearColor)
  header.background = ({ color = 0, environment = 1, passthrough = 2 })[mode] or 1
  if clearColor then header.clear = clearColor end
end

local function submitScene()
  local count = 0
  for e in pairs(entities) do
    if e.body then e.position = e.body.pos; e.rotation = e.body.rot end
    if e.visible then
      local p, r = e:worldPose()
      local s, c = e.scale, e.color
      N.draw(e.model or e.mesh, e.texture, e.flags, c[1], c[2], c[3], c[4] or 1, p.x, p.y, p.z, r.x, r.y, r.z, r.w, s.x, s.y, s.z)
      count = count + 1
    end
  end
  local h = header
  N.header(0, rig.pos.x); N.header(1, rig.pos.y); N.header(2, rig.pos.z)
  N.header(3, rig.rot.x); N.header(4, rig.rot.y); N.header(5, rig.rot.z); N.header(6, rig.rot.w)
  N.header(7, h.lightDir.x); N.header(8, h.lightDir.y); N.header(9, h.lightDir.z)
  N.header(10, h.lightColor[1]); N.header(11, h.lightColor[2]); N.header(12, h.lightColor[3])
  N.header(13, h.ambient[1]); N.header(14, h.ambient[2]); N.header(15, h.ambient[3])
  N.header(16, h.clear[1]); N.header(17, h.clear[2]); N.header(18, h.clear[3]); N.header(19, h.clear[4] or 1)
  N.header(20, h.background)
  return count
end

-- ------------------------------------------------------------------ physics wrapper
local physics = {}
--- hx.physics.world{gravity=vec3, auto=true}: auto-stepped worlds advance after onUpdate each frame.
function physics.world(opts)
  opts = opts or {}
  local raw = physicsLib.newWorld(opts)
  local w = { _raw = raw, auto = opts.auto ~= false }
  function w:step(dt) raw:step(dt) end
  function w:setTimeScale(s) raw.timeScale = math.max(0, math.min(4, s)) end
  function w:getTimeScale() return raw.timeScale end
  function w:raycast(o, d, maxDist, filter)
    local hit = raw:raycast(o, d, maxDist, filter and function(b) return filter(b.userData, b) end)
    if hit then hit.entity = hit.body.userData end
    return hit
  end
  function w:overlapSphere(c, r, filter) return raw:overlapSphere(c, r, filter and function(b) return filter(b.userData, b) end) end
  function w:addStatic(def) def.mass = 0; return raw:add(def) end
  function w:addBody(def) return raw:add(def) end
  function w:removeBody(b) raw:remove(b) end
  function w:hinge(entity, pivotWorld, axisWorld)
    local b = entity.body
    local half = 0.5
    local a1 = pivotWorld + axisWorld * half
    local a2 = pivotWorld - axisWorld * half
    local inv = Q.conjugate(b.rot)
    raw:addPointJoint(b, Q.rotate(inv, a1 - b.pos), nil, a1)
    raw:addPointJoint(b, Q.rotate(inv, a2 - b.pos), nil, a2)
  end
  function w:pointJoint(a, localA, b, localB) return raw:addPointJoint(a.body, localA, b and b.body, localB) end
  function w:impulse(entity, imp, point) raw:applyImpulse(entity.body, imp, point) end
  function w:drive(entity, pos, rot, dt, maxSpeed) raw:drive(entity.body, pos, rot, dt, maxSpeed) end
  function w:bodies() return raw.bodies end
  function w:stats() return { bodies = #raw.bodies, contacts = raw.contactsThisStep } end
  worlds[#worlds + 1] = w
  return w
end

-- ------------------------------------------------------------------ handles from host
local function call(name, ...) return N.call(name, ...) end

local assets = {}
function assets.model(path) local id, err = call("load_model", path); if not id then error(err or ("cannot load model " .. path), 2) end return id end
function assets.texture(path) local id, err = call("load_texture", path); if not id then error(err or ("cannot load texture " .. path), 2) end return id end
function assets.text(path) local s, err = call("asset_text", path); if not s then error(err or ("cannot read " .. path), 2) end return s end
function assets.modelBounds(id) local x0, y0, z0, x1, y1, z1 = call("model_bounds", id); return V(x0, y0, z0), V(x1, y1, z1) end

local audio = {}
function audio.load(path) return call("audio_load", path) end
--- Procedural sound (no asset needed): {wave="sine|square|saw|noise", freq=, freqEnd=, duration=, volume=, attack=, decay=}
function audio.synth(p)
  p = p or {}
  return call("audio_synth", p.wave or "sine", p.freq or 440, p.freqEnd or p.freq or 440, p.duration or 0.2, p.volume or 0.6, p.attack or 0.005, p.decay or 0.1)
end
function audio.play(id, opts)
  opts = opts or {}
  local pos = opts.position
  return call("audio_play", id, opts.volume or 1, opts.pitch or 1, opts.loop and 1 or 0, pos and 1 or 0, pos and pos.x or 0, pos and pos.y or 0, pos and pos.z or 0)
end
function audio.stop(voice) call("audio_stop", voice) end

local fs = {}
function fs.read(path) return call("fs_read", path) end
function fs.write(path, data) return call("fs_write", path, tostring(data)) end
function fs.list(dir) local s = call("fs_list", dir or ""); local out = {}; for n in (s or ""):gmatch("[^\n]+") do out[#out + 1] = n end return out end
function fs.delete(path) return call("fs_delete", path) end

local pendingRequests, nextRequest = {}, 1
local net = {}
--- Asynchronous HTTPS request to a host allowed by the manifest. callback(status, body, err)
function net.request(method, url, body, callback)
  local id = nextRequest
  nextRequest = nextRequest + 1
  pendingRequests[id] = callback
  local ok, err = call("net_request", id, method, url, body or "")
  if not ok then pendingRequests[id] = nil; error(err or "request rejected", 2) end
  return id
end
function net.get(url, callback) return net.request("GET", url, nil, callback) end

local camera = {}
--- Texture handle of the live camera image (GPU only; pixels are never readable from Lua).
function camera.texture() return call("camera_texture") end

local spatial = {}
function spatial.head() return state.head.position, state.head.rotation end
function spatial.headLocal() return state.head.local_.position, state.head.local_.rotation end
function spatial.setOrigin(pos, rot) rig.pos = V.copy(pos); if rot then rig.rot = Q.copy(rot) end end
function spatial.getOrigin() return V.copy(rig.pos), Q.copy(rig.rot) end
function spatial.recenter() N.header(21, 1) end
function spatial.mixedReality() return f(IN.MR) > 0.5 end

local hands = {}
local HANDS = { left = 0, right = 1 }
function hands.get(which)
  local h = state.hands[HANDS[which] or which]
  if not h then return nil end
  return h
end
function hands.available() return f(IN.HANDS_AVAILABLE) > 0.5 end
hands.JOINT = { WRIST = 0, THUMB_TIP = 4, INDEX_MCP = 5, INDEX_TIP = 8, MIDDLE_MCP = 9, MIDDLE_TIP = 12, RING_TIP = 16, PINKY_TIP = 20 }

local input = {}
input.BUTTON = BUTTONS
function input.controller(which)
  local i = HANDS[which] or which
  local c = state.controllers[i]
  if not c then return nil end
  return c
end
function input.down(which, button) local i = HANDS[which] or which return bit.band(state.buttons[i] or 0, BUTTONS[button] or 0) ~= 0 end
function input.pressed(which, button)
  local i = HANDS[which] or which
  local m = BUTTONS[button] or 0
  return bit.band(state.buttons[i] or 0, m) ~= 0 and bit.band(state.prevButtons[i] or 0, m) == 0
end
function input.released(which, button)
  local i = HANDS[which] or which
  local m = BUTTONS[button] or 0
  return bit.band(state.buttons[i] or 0, m) == 0 and bit.band(state.prevButtons[i] or 0, m) ~= 0
end
--- Unified manipulators (one per side): tracked hand when available, otherwise a connected
--- controller. Each entry: {side, source="hand"|"controller", position, rotation, direction,
--- grip, gripPressed, gripReleased, trigger, triggerPressed, velocity}. Hand data is only
--- used when the package holds the hand_tracking permission.
local manip = { [0] = {}, [1] = {} }
local function updateManipulators(dt)
  for side = 0, 1 do
    local m = manip[side]
    local c = state.controllers[side]
    local h = state.hands[side]
    local prevPos, prevGrip, prevTrig = m.position, m.grip, m.trigger
    m.side = side == 0 and "left" or "right"
    if c and c.connected then
      m.active, m.source = true, "controller"
      m.rotation = c.rotation
      m.position = (perms.hand_tracking and h and h.tracked) and h.joints[9] or c.origin
      local b = state.buttons[side]
      m.grip = bit.band(b, BUTTONS.ZR + BUTTONS.ZL + BUTTONS.SR) ~= 0
      m.trigger = bit.band(b, BUTTONS.R + BUTTONS.L + BUTTONS.A) ~= 0
    elseif perms.hand_tracking and h and h.tracked then
      m.active, m.source = true, "hand"
      local wrist, mid, idx, pinky = h.joints[0], h.joints[9], h.joints[5], h.joints[17]
      local fwd = V.normalize(mid - wrist)
      local across = V.normalize(idx - pinky)
      local up = V.cross(across, fwd)
      if side == 0 then up = -up end
      m.rotation = Q.lookRotation(fwd, up)
      m.position = mid
      m.grip = h.gesture == "fist" or h.gesture == "grab"
      m.trigger = h.gesture == "pinch"
    else
      m.active, m.source = false, "none"
      m.grip, m.trigger = false, false
    end
    if m.active then
      m.direction = Q.forward(m.rotation)
      m.velocity = (prevPos and dt > 0) and (m.position - prevPos) / dt or V(0, 0, 0)
    else
      m.velocity = V(0, 0, 0)
    end
    m.gripPressed = m.grip and not prevGrip
    m.gripReleased = (not m.grip) and prevGrip or false
    m.triggerPressed = m.trigger and not prevTrig
  end
end
function input.manipulators() return manip[0], manip[1] end
function input.anyManipulator() return manip[0].active or manip[1].active end

function input.tap() return state.tap and not state.prevTap end
function input.tapHeld() return state.tap end
function input.gazeRay() return state.head.position, Q.forward(state.head.rotation) end

local timeApi = { now = function() return N.clock() end, dt = 0, frame = 0 }

local app = {}
function app.exit() N.header(22, 1) end
app.id, app.name, app.version, app.mode = config.id, config.name, config.version, config.mode

-- ------------------------------------------------------------------ sandbox environment
local function copyTable(t, except)
  local c = {}
  for k, v in pairs(t) do if not (except and except[k]) then c[k] = v end end
  return c
end

local function readonly(t, name)
  return setmetatable({}, {
    __index = t,
    __newindex = function() error("hx." .. name .. " is read-only", 2) end,
    __metatable = false,
  })
end

local sandbox = {}
local safeString = copyTable(string, { dump = not unsafe })
local maxRep = 16 * 1024 * 1024
safeString.rep = function(s, n, sep)
  if #s * (n or 0) > maxRep then error("string.rep result too large", 2) end
  return string.rep(s, n, sep)
end

sandbox._VERSION = "Lua 5.1 (HyperXR sandbox)"
for _, k in ipairs({ "assert", "error", "ipairs", "next", "pairs", "pcall", "select", "tonumber", "tostring", "type",
  "unpack", "xpcall", "rawequal", "rawget", "rawset", "setmetatable", "getmetatable" }) do
  sandbox[k] = _G0[k]
end
sandbox.string = safeString
sandbox.table = copyTable(table)
sandbox.math = copyTable(math)
sandbox.bit = copyTable(bit)
sandbox.coroutine = copyTable(coroutine)
sandbox.os = { clock = os.clock, time = os.time, date = function(fmt, t) if fmt and fmt:sub(1, 1) == "!" then fmt = fmt:sub(2) end return os.date(fmt, t) end, difftime = os.difftime }
sandbox.print = function(...) log(1, ...) end
sandbox.collectgarbage = function(opt) if opt == nil or opt == "count" then return collectgarbage("count") end if opt == "step" then return collectgarbage("step") end error("collectgarbage option not allowed", 2) end
sandbox._G = sandbox

-- load: text chunks only, always bound to the sandbox (or a table supplied by the script)
sandbox.load = function(chunk, name, mode, env)
  if type(chunk) ~= "string" and type(chunk) ~= "function" then return nil, "invalid chunk" end
  if type(chunk) == "string" and chunk:byte(1) == 27 and not unsafe then return nil, "bytecode is not allowed" end
  return load(chunk, name and ("=" .. tostring(name)) or "=(load)", unsafe and (mode or "bt") or "t", type(env) == "table" and env or sandbox)
end
sandbox.loadstring = function(s, name) return sandbox.load(s, name) end

-- require: package-local Lua modules only
local modules = {}
sandbox.require = function(name)
  if type(name) ~= "string" or not name:match("^[%w_%.]+$") then error("invalid module name", 2) end
  if modules[name] ~= nil then return modules[name] end
  local path = name:gsub("%.", "/") .. ".lua"
  local src, err = call("asset_text", path)
  if not src then error("module '" .. name .. "' not found: " .. tostring(err), 2) end
  local fn, cerr = load(src, "@" .. path, "t", sandbox)
  if not fn then error(cerr, 2) end
  modules[name] = true
  local r = fn(name)
  if r ~= nil then modules[name] = r end
  return modules[name]
end

if unsafe then
  -- Explicitly granted by the user: no sandbox protections.
  sandbox.ffi = require and pcall(require, "ffi") and require("ffi") or nil
  sandbox.jit = jit_
  sandbox.debug = debug
  sandbox.os = os
  sandbox.io = rawget(_G0, "io")
end

local hx = {}
hx.vec3 = V
hx.quat = Q
hx.V3 = vm.vec3
hx.Q4 = vm.quat
hx.log = function(...) log(1, ...) end
hx.warn = function(...) log(2, ...) end
hx.time = timeApi
hx.app = readonly(app, "app")
hx.assets = readonly(assets, "assets") -- reading the package's own files is always allowed
hx.permissions = readonly(copyTable(perms), "permissions")

local gated = {
  { "ui", "ui", ui }, { "audio", "audio", audio }, { "input", "input", input },
  { "spatial", "spatial", spatial }, { "physics", "physics", physics }, { "hand_tracking", "hands", hands },
  { "filesystem", "fs", fs }, { "network", "net", net }, { "camera", "camera", camera },
}
for _, g in ipairs(gated) do
  if perms[g[1]] then hx[g[2]] = readonly(g[3], g[2]) end
end
-- Scene graph is part of the base runtime; window-mode packages simply do not use it.
hx.scene = readonly(scene, "scene")
if config.mode == "window" and perms.ui then
  local w = config.window or {}
  hx.window = ui.panel({ width = w.w or 0.9, height = w.h or 0.6, position = V(0, 1.45, -1.1) })
  hx.window._isMainWindow = true
end
sandbox.hx = readonly(hx, "")

-- ------------------------------------------------------------------ execution budget
local killed = nil
local BOOT_SOURCE = debug.getinfo(1, "S").source
local getinfo = debug.getinfo
local function budgetHook()
  -- Never interrupt the trusted bootstrap frames themselves (e.g. guarded() cleanup).
  local info = getinfo(2, "S")
  if info and info.source == BOOT_SOURCE then return end
  if killed then error(killed, 0) end
  -- From here on every VM instruction raises, so pcall inside the script cannot keep it alive.
  debug.sethook(budgetHook, "", 1)
  if collectgarbage("count") > memoryLimitKb then
    collectgarbage("collect")
    if collectgarbage("count") > memoryLimitKb then killed = "memory limit exceeded"; error(killed, 0) end
  end
  killed = "execution budget exceeded"
  error(killed, 0)
end

local function guardedWith(limit, fn, ...)
  if unsafe then return xpcall(fn, traceback, ...) end
  debug.sethook(budgetHook, "", limit)
  local res = { xpcall(fn, traceback, ...) }
  debug.sethook()
  if killed then return false, killed end
  return unpack(res)
end
local function guarded(fn, ...) return guardedWith(budget, fn, ...) end

-- ------------------------------------------------------------------ lifecycle
local crashed = nil
local function crash(msg)
  crashed = tostring(msg)
  log(3, "package crashed: " .. crashed)
  N.call("game_error", crashed)
  ui._reset()
  local p = ui.panel({ width = 0.9, height = 0.5, position = state.head.position + Q.rotate(Q.axisAngle(V(0, 1, 0), Q.yaw(state.head.rotation)), V(0, 0, -1.1)), rotation = Q.axisAngle(V(0, 1, 0), Q.yaw(state.head.rotation)) })
  p:add(ui.label({ text = config.name .. " parou", size = 44, bold = true }))
  p:add(ui.label({ text = crashed:sub(1, 300), size = 24, maxWidth = 820, color = ui.theme.textDim }))
  p:add(ui.button({ text = "Sair", onClick = function() N.header(22, 1) end }))
end

local entryFn, loadErr
do
  local src, err = call("asset_text", config.entry)
  if not src then loadErr = err or "entry not found" else entryFn, loadErr = load(src, "@" .. config.entry, "t", sandbox) end
end

local started = false
local function start()
  readInput()
  if not entryFn then crash(loadErr); return end
  -- loading and onStart get a larger budget: building levels is legitimately heavier than a frame
  local ok, err = guardedWith(budget * 20, entryFn)
  if not ok then crash(err); return end
  local onStart = rawget(sandbox, "onStart")
  if onStart then
    ok, err = guardedWith(budget * 20, onStart)
    if not ok then crash(err); return end
  end
  started = true
end

local frameNo = 0
local function frame(dt)
  frameNo = frameNo + 1
  timeApi.dt, timeApi.frame = dt, frameNo
  readInput()
  updateManipulators(dt)
  text.frame()
  if not started and not crashed then start() end
  ui.processPointers(pointers())
  ui.update(dt)
  if not crashed then
    local onUpdate = rawget(sandbox, "onUpdate")
    if onUpdate then
      local ok, err = guarded(onUpdate, dt)
      if not ok then crash(err) end
    end
    if not crashed then
      for _, w in ipairs(worlds) do
        if w.auto then
          local ok, err = guarded(w.step, w, dt)
          if not ok then crash(err) break end
        end
      end
    end
  end
  local draws = submitScene()
  ui.submit()
  return draws
end

local function event(kind, id, status, data)
  if kind == "net" then
    local cb = pendingRequests[id]
    pendingRequests[id] = nil
    if cb and not crashed then
      local ok, err = guarded(cb, status, status >= 0 and data or nil, status < 0 and data or nil)
      if not ok then crash(err) end
    end
  elseif kind == "pause" or kind == "resume" then
    local fn = rawget(sandbox, kind == "pause" and "onPause" or "onResume")
    if fn and not crashed then guarded(fn) end
  end
end

local function shutdown()
  local fn = rawget(sandbox, "onExit")
  if fn and not crashed then guarded(fn) end
end

return { frame = frame, event = event, shutdown = shutdown, ui = ui, sandbox = sandbox, isCrashed = function() return crashed end }
