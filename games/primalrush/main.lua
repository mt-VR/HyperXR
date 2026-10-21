-- PRIMAL RUSH: run, climb and leap using only your arms.
-- Original HyperXR game. Modes: Tag vs bots (offline), Time Trial. Multiplayer is a future module.
local V, Q = hx.vec3, hx.quat
local scene, ui, input, audio = hx.scene, hx.ui, hx.input, hx.audio
local Locomotion = require("locomotion")

local C = {
  ground = { 0.18, 0.30, 0.16, 1 }, trunk = { 0.36, 0.24, 0.16, 1 }, leaf = { 0.20, 0.55, 0.28, 1 },
  rock = { 0.45, 0.44, 0.42, 1 }, platform = { 0.55, 0.38, 0.22, 1 }, wall = { 0.25, 0.35, 0.22, 1 },
  it = { 1.0, 0.35, 0.10, 1 }, runner = { 0.55, 0.60, 0.95, 1 }, ring = { 1.0, 0.85, 0.2, 1 }, ringNext = { 0.3, 1.0, 0.6, 1 },
  hand = { 0.15, 0.12, 0.1, 1 },
}

local world, loco
local mode = "menu"
local bots = {}
local playerIt = false
local itTimer, score, tagCooldown = 0, 0, 0
local rings, ringIndex, trialTime, bestTime = {}, 1, 0, nil
local menuPanel, hud, hudText
local sfx = {}
local handViz = {}

-- ------------------------------------------------------------------ map
local function solid(e) return not (e and e.userData and e.userData.bot) end

local function block(pos, size, color)
  return scene.spawn({ position = pos, scale = size, color = color, body = { world = world } })
end

local function buildCanopy()
  scene.setBackground("color", { 0.55, 0.75, 0.9, 1 })
  scene.setLight(V(0.4, -1, 0.2), { 1.0, 0.95, 0.85 }, { 0.45, 0.5, 0.55 })
  block(V(0, -0.5, 0), V(40, 1, 40), C.ground)
  for _, w in ipairs({ { V(0, 2, -20), V(40, 4, 1) }, { V(0, 2, 20), V(40, 4, 1) }, { V(-20, 2, 0), V(1, 4, 40) }, { V(20, 2, 0), V(1, 4, 40) } }) do
    block(w[1], w[2], C.wall)
  end
  local trees = { { -6, -6 }, { 6, -7 }, { -9, 5 }, { 8, 6 }, { 0, -13 }, { -13, -2 }, { 13, -1 }, { 2, 11 } }
  for _, t in ipairs(trees) do
    block(V(t[1], 4, t[2]), V(0.9, 8, 0.9), C.trunk)
    scene.spawn({ mesh = scene.mesh.sphere, position = V(t[1], 8.5, t[2]), scale = V(4, 2.5, 4), color = C.leaf })
    block(V(t[1] + 1.2, 3, t[2]), V(1.6, 0.25, 1.6), C.platform)
  end
  for _, r in ipairs({ { 3, -3, 1.2 }, { -3, 2, 0.9 }, { 10, -10, 1.6 }, { -10, 10, 1.4 }, { 4, 4, 0.7 } }) do
    block(V(r[1], r[3] / 2, r[2]), V(r[3] * 1.4, r[3], r[3] * 1.2), C.rock)
  end
  -- high walkway between two trees
  block(V(0, 5, -9.8), V(10, 0.3, 1.2), C.platform)
end

-- ------------------------------------------------------------------ bots (offline tag)
local function makeBot(pos)
  local b = { pos = pos, vel = V(0, 0, 0), it = false, hopTimer = math.random() }
  b.body = scene.spawn({ mesh = scene.mesh.sphere, position = pos, scale = 0.55, color = C.runner })
  b.body.userData = { bot = true }
  b.armL = scene.spawn({ mesh = scene.mesh.cylinder, position = V(-0.35, -0.15, 0), scale = V(0.12, 0.6, 0.12), color = C.hand })
  b.armR = scene.spawn({ mesh = scene.mesh.cylinder, position = V(0.35, -0.15, 0), scale = V(0.12, 0.6, 0.12), color = C.hand })
  b.armL:attach(b.body); b.armR:attach(b.body)
  bots[#bots + 1] = b
  return b
end

local function setBotIt(b, it)
  b.it = it
  b.body:setColor(it and C.it or C.runner)
end

local function updateBot(b, dt, playerPos)
  local target
  local nearestDist, nearest = math.huge, nil
  if b.it then
    -- chase the closest runner (player included)
    if not playerIt then nearest, nearestDist = playerPos, V.distance(playerPos, b.pos) end
    for _, o in ipairs(bots) do
      if o ~= b and not o.it then
        local d = V.distance(o.pos, b.pos)
        if d < nearestDist then nearest, nearestDist = o.pos, d end
      end
    end
    target = nearest
  else
    -- flee from whoever is "it"
    local threat = playerIt and playerPos or nil
    for _, o in ipairs(bots) do if o.it then threat = o.pos end end
    if threat then target = b.pos + V.normalize(V(b.pos.x - threat.x, 0, b.pos.z - threat.z)) * 5 end
  end
  local onGround = b.pos.y <= 0.56
  b.hopTimer = b.hopTimer - dt
  if onGround and b.hopTimer <= 0 and target then
    local dir = V.normalize(V(target.x - b.pos.x, 0, target.z - b.pos.z))
    b.vel = dir * (b.it and 4.2 or 3.6) + V(0, 3.2, 0)
    b.hopTimer = 0.35 + math.random() * 0.3
  end
  b.vel = b.vel + V(0, -9.81 * dt, 0)
  local np = b.pos + b.vel * dt
  local push, normal = world:overlapSphere(np, 0.3, solid)
  if normal then
    np = np + push
    local vn = V.dot(b.vel, normal)
    if vn < 0 then b.vel = b.vel - normal * vn end
    if normal.y > 0.6 then b.vel = b.vel * 0.85 end
  end
  if np.y < 0.55 then np = V(np.x, 0.55, np.z); if b.vel.y < 0 then b.vel = V(b.vel.x * 0.8, 0, b.vel.z * 0.8) end end
  b.pos = np
  b.body:setPosition(np)
  if V.lengthSq(V(b.vel.x, 0, b.vel.z)) > 0.01 then b.body:setRotation(Q.lookRotation(V(b.vel.x, 0, b.vel.z))) end
end

-- ------------------------------------------------------------------ rings (time trial)
local RING_POINTS = { { 3, 1.8, -3 }, { -4.8, 3.6, -6 }, { 0, 5.8, -9.8 }, { 7.2, 3.6, -7 }, { 9.2, 3.6, 6 }, { 2, 1.5, 6 }, { -7.8, 3.6, 5 } }

local function buildRings()
  for _, r in ipairs(rings) do r:destroy() end
  rings = {}
  for i, p in ipairs(RING_POINTS) do
    rings[i] = scene.spawn({ mesh = scene.mesh.cylinder, position = V(p[1], p[2], p[3]), scale = V(1.4, 0.06, 1.4),
      rotation = Q.axisAngle(V(1, 0, 0), math.pi / 2), color = C.ring, unlit = true })
  end
  ringIndex = 1
  rings[1]:setColor(C.ringNext)
end

-- ------------------------------------------------------------------ UI
local function setHud(t) if hudText then hudText:set({ text = t }) end end

local function closeMenu() if menuPanel then menuPanel:destroy(); menuPanel = nil end end

local function resetPlayer()
  loco = Locomotion.new(world, { origin = V(0, 0.3, 3), solid = solid })
  hx.spatial.setOrigin(loco.origin, Q.identity())
end

local function startTag()
  closeMenu()
  for _, b in ipairs(bots) do b.body:destroy() end
  bots = {}
  for i = 1, 4 do makeBot(V(-8 + i * 4, 0.6, -4 - i)) end
  setBotIt(bots[1], true)
  playerIt, score, itTimer, tagCooldown = false, 0, 0, 2
  for _, r in ipairs(rings) do r:setVisible(false) end
  resetPlayer()
  mode = "tag"
end

local function startTrial()
  closeMenu()
  for _, b in ipairs(bots) do b.body:destroy() end
  bots = {}
  buildRings()
  trialTime = 0
  resetPlayer()
  mode = "trial"
end

local function showMenu()
  closeMenu()
  local head, rot = hx.spatial.head()
  menuPanel = ui.panel({ width = 1.0, height = 0.72 })
  menuPanel:placeInFront(head, rot, 1.2, -0.05)
  menuPanel:add(ui.label({ text = "PRIMAL RUSH", size = 72, bold = true, color = C.it }))
  menuPanel:add(ui.label({ text = "Push the ground and walls with your hands to move. Swing and let go to leap. No hands? Look and tap to leap.",
    size = 26, maxWidth = 900, color = ui.theme.textDim }))
  local row = menuPanel:add(ui.row({ gap = 16, height = 90 }))
  row:add(ui.button({ text = "Tag vs bots", flex = 1, onClick = startTag }))
  row:add(ui.button({ text = "Time trial", flex = 1, onClick = startTrial }))
  menuPanel:add(ui.button({ text = "Multiplayer (future module: needs a sync server)", disabled = true }))
  if bestTime then menuPanel:add(ui.label({ text = string.format("Best time: %.2f s", bestTime), size = 28 })) end
end

-- ------------------------------------------------------------------ lifecycle
function onStart()
  world = hx.physics.world({ auto = false })
  math.randomseed(os.time())
  buildCanopy()
  sfx.tag = audio.synth({ wave = "square", freq = 880, freqEnd = 440, duration = 0.25, volume = 0.6 })
  sfx.ring = audio.synth({ wave = "sine", freq = 660, freqEnd = 1320, duration = 0.3, volume = 0.6 })
  sfx.slap = audio.synth({ wave = "noise", freq = 800, freqEnd = 200, duration = 0.08, volume = 0.5, decay = 0.07 })
  if hx.fs then
    local saved = hx.fs.read("best.txt")
    bestTime = saved and tonumber(saved) or nil
  end
  resetPlayer()
  hud = ui.panel({ width = 0.5, height = 0.12, draggable = false, padding = 12, background = { 0, 0, 0, 0.45 } })
  hudText = hud:add(ui.label({ text = "", size = 34, align = "center" }))
  showMenu()
end

local wasStuck = { [0] = false, [1] = false }

function onUpdate(dt)
  dt = math.min(dt, 1 / 30)
  local _, rot = hx.spatial.getOrigin()
  local headLocal = select(1, hx.spatial.headLocal())
  local handsLocal = {}
  local l, r = input.manipulators()
  local origin = hx.spatial.getOrigin()
  for side, m in pairs({ [0] = l, [1] = r }) do
    if m.active then
      -- manipulators are in world space; convert back to tracking space for the solver
      handsLocal[side] = Q.rotate(Q.conjugate(rot), m.position - origin)
    end
  end

  if mode ~= "menu" then
    loco:update(dt, handsLocal, headLocal, rot)
    if not input.anyManipulator() and input.tap() then
      local _, hr = hx.spatial.head()
      local push = world:overlapSphere(loco.origin + V(0, 0.3, 0), 0.5, solid)
      if loco.grounded or loco.origin.y < 0.6 or V.lengthSq(push) > 0 then loco:leap(Q.forward(hr), 5) end
    end
    if loco.origin.y < -5 then resetPlayer() end
    hx.spatial.setOrigin(loco.origin, rot)
    for side = 0, 1 do
      local stuck = loco.hands[side].stuck
      if stuck and not wasStuck[side] then audio.play(sfx.slap, { position = loco.hands[side].point, volume = 0.35 }) end
      wasStuck[side] = stuck
    end
  end

  local head, hr = hx.spatial.head()
  if hud then hud:placeInFront(head, hr, 1.0, 0.32) end

  if mode == "tag" then
    tagCooldown = math.max(0, tagCooldown - dt)
    for _, b in ipairs(bots) do updateBot(b, dt, head) end
    if playerIt then itTimer = itTimer + dt else score = score + dt end
    -- player tags a bot by touching it with a hand
    if playerIt and tagCooldown == 0 then
      for _, m in ipairs({ l, r }) do
        if m.active then
          for _, b in ipairs(bots) do
            if V.distance(m.position, b.pos) < 0.45 then
              playerIt = false; setBotIt(b, true); tagCooldown = 2; audio.play(sfx.tag)
              break
            end
          end
        end
      end
    end
    -- bots tag each other and the player
    for _, b in ipairs(bots) do
      if b.it and tagCooldown == 0 then
        if not playerIt and V.distance(b.pos, head) < 0.7 then
          playerIt = true; setBotIt(b, false); tagCooldown = 2; audio.play(sfx.tag)
        else
          for _, o in ipairs(bots) do
            if o ~= b and not o.it and V.distance(o.pos, b.pos) < 0.8 then
              setBotIt(b, false); setBotIt(o, true); tagCooldown = 1.5
              break
            end
          end
        end
      end
    end
    setHud(string.format("%s   free time %.1f s", playerIt and "YOU ARE IT" or "RUN!", score))
  elseif mode == "trial" then
    trialTime = trialTime + dt
    local ring = rings[ringIndex]
    if ring and V.distance(head, ring.position) < 0.9 then
      audio.play(sfx.ring)
      ring:setVisible(false)
      ringIndex = ringIndex + 1
      if rings[ringIndex] then
        rings[ringIndex]:setColor(C.ringNext)
      else
        if not bestTime or trialTime < bestTime then
          bestTime = trialTime
          if hx.fs then hx.fs.write("best.txt", string.format("%.3f", bestTime)) end
        end
        mode = "menu"
        showMenu()
      end
    end
    setHud(string.format("Ring %d/%d   %.2f s", math.min(ringIndex, #rings), #rings, trialTime))
  else
    setHud("")
  end
  if input.pressed("right", "PLUS") or input.pressed("left", "MINUS") then mode = "menu"; showMenu() end
end
