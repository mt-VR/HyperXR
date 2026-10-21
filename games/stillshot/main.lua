-- STILLSHOT: time only moves when you move.
-- Original HyperXR game: procedural geometry, procedural sound, no third-party assets.
local V, Q = hx.vec3, hx.quat
local scene, ui, input, audio = hx.scene, hx.ui, hx.input, hx.audio
local levels = require("levels")

local C = {
  floor = { 0.10, 0.22, 0.26, 1 }, wall = { 0.16, 0.34, 0.38, 1 }, pillar = { 0.22, 0.44, 0.48, 1 },
  enemy = { 1.0, 0.62, 0.12, 1 }, enemyHead = { 1.0, 0.78, 0.30, 1 }, shard = { 1.0, 0.55, 0.10, 1 },
  gun = { 0.06, 0.06, 0.08, 1 }, bullet = { 0.72, 0.32, 1.0, 1 }, playerBullet = { 0.85, 0.95, 1.0, 1 },
}

local world
local level, levelIndex = nil, 1
local enemies, bullets, guns, shards, statics = {}, {}, {}, {}, {}
local held = { [0] = nil, [1] = nil }
local gazeGun = nil
local timeScale = 0.05
local state = "menu" -- menu | playing | dead | cleared | finished
local menuPanel, hudPanel
local sfx = {}
local lastHeadPos, lastHeadRot

-- ------------------------------------------------------------------ helpers
local function clamp(x, a, b) if x < a then return a elseif x > b then return b end return x end

local function spawnBox(pos, size, color, mass, extra)
  local def = { position = pos, scale = size, color = color, body = { world = world, mass = mass or 0 } }
  if extra then for k, v in pairs(extra) do def.body[k] = v end end
  return scene.spawn(def)
end

local function clearLevel()
  for _, list in ipairs({ enemies, bullets, guns, shards, statics }) do
    for i = #list, 1, -1 do
      local e = list[i]
      if e.root then e.root:destroy() end
      if e.parts then for _, p in ipairs(e.parts) do p:destroy() end end
      if e.destroy then e:destroy() end
      list[i] = nil
    end
  end
  held[0], held[1], gazeGun = nil, nil, nil
end

-- ------------------------------------------------------------------ guns
local function makeGun(pos)
  local g = { ammo = 6, cooldown = 0 }
  g.root = scene.spawn({ position = pos, scale = V(0.04, 0.09, 0.2), color = C.gun,
    body = { world = world, mass = 0.8, friction = 0.8, size = V(0.04, 0.09, 0.2) } })
  g.root.userData = { kind = "gun", gun = g }
  g.barrel = scene.spawn({ position = V(0, 0.02, -0.12), scale = V(0.025, 0.025, 0.08), color = C.gun })
  g.barrel:attach(g.root)
  g.grip = scene.spawn({ position = V(0, -0.07, 0.06), scale = V(0.035, 0.1, 0.045), color = C.gun })
  g.grip:attach(g.root)
  g.parts = { g.barrel, g.grip }
  guns[#guns + 1] = g
  return g
end

local function muzzle(g)
  return g.root.position + Q.rotate(g.root.rotation, V(0, 0.02, -0.18)), Q.forward(g.root.rotation)
end

-- ------------------------------------------------------------------ bullets
local function fireBullet(origin, dir, fromPlayer)
  local b = scene.spawn({ mesh = scene.mesh.sphere, position = origin, scale = fromPlayer and 0.03 or 0.045,
    color = fromPlayer and C.playerBullet or C.bullet, unlit = true })
  b.vel = dir * (fromPlayer and 22 or 7)
  b.fromPlayer = fromPlayer
  b.life = 6
  bullets[#bullets + 1] = b
  audio.play(fromPlayer and sfx.shot or sfx.enemyShot, { position = origin, volume = fromPlayer and 0.9 or 0.6 })
end

local function shoot(g)
  if g.cooldown > 0 then return end
  if g.ammo <= 0 then audio.play(sfx.empty, { position = g.root.position }); g.cooldown = 0.3 return end
  g.ammo = g.ammo - 1
  g.cooldown = 0.25
  local o, d = muzzle(g)
  fireBullet(o, d, true)
end

-- ------------------------------------------------------------------ enemies
local function shatter(e, impulseDir)
  if e.dead then return end
  e.dead = true
  audio.play(sfx.shatter, { position = e.root.position })
  local base = e.root.position
  for i = 1, 14 do
    local off = V((math.random() - 0.5) * 0.4, math.random() * 1.6, (math.random() - 0.5) * 0.3)
    local s = scene.spawn({ position = base + off, scale = V(0.08 + math.random() * 0.08, 0.08 + math.random() * 0.1, 0.06),
      rotation = Q.euler(math.random() * 3, math.random() * 3, 0), color = C.shard,
      body = { world = world, mass = 0.2, restitution = 0.3 } })
    local dir = V.normalize(off + (impulseDir or V(0, 0, 0)) * 2 + V(0, 0.4, 0))
    world:impulse(s, dir * (0.4 + math.random() * 0.6))
    s.life = 4
    shards[#shards + 1] = s
  end
  e.root:destroy()
  for _, p in ipairs(e.parts) do p:destroy() end
  e.parts = {}
end

local function makeEnemy(pos, kind)
  local e = { kind = kind or "walker", fireTimer = 1.5 + math.random() * 1.5, speed = kind == "runner" and 1.2 or 0.6 }
  e.root = scene.spawn({ position = pos + V(0, 0.75, 0), scale = V(0.42, 1.1, 0.26), color = C.enemy,
    body = { world = world, mass = 0, kinematic = true, size = V(0.42, 1.5, 0.26) } })
  e.root.userData = { kind = "enemy", enemy = e }
  e.head = scene.spawn({ mesh = scene.mesh.sphere, position = V(0, 0.75, 0), scale = V(0.5, 0.26 / 1.1, 0.8), color = C.enemyHead })
  e.head:attach(e.root)
  e.arm = scene.spawn({ position = V(0.28, 0.2, -0.3), scale = V(0.18, 0.1, 1.0), color = C.enemy })
  e.arm:attach(e.root)
  e.parts = { e.head, e.arm }
  if kind ~= "brawler" then
    e.gun = scene.spawn({ position = V(0.28, 0.25, -0.75), scale = V(0.1, 0.12, 0.6), color = C.gun })
    e.gun:attach(e.root)
    e.parts[#e.parts + 1] = e.gun
  end
  enemies[#enemies + 1] = e
  return e
end

local function enemyThink(e, dt, headPos)
  local p = e.root.position
  local toPlayer = V(headPos.x - p.x, 0, headPos.z - p.z)
  local dist = V.length(toPlayer)
  local dir = dist > 1e-3 and toPlayer / dist or V(0, 0, -1)
  -- steer around pillars: probe ahead and slide sideways when blocked
  local hit = world:raycast(p + V(0, -0.2, 0), dir, 0.9, function(ent) return ent and ent.userData == nil end)
  if hit then dir = V.normalize(dir + V.cross(V(0, 1, 0), dir) * 1.5) end
  local keep = e.kind == "brawler" and 0.6 or 3.0
  if dist > keep then
    local np = p + dir * (e.speed * dt)
    e.root:setPosition(np)
  end
  e.root:setRotation(Q.lookRotation(dir))
  if e.kind == "brawler" then
    if dist < 0.75 then return "hit" end
  else
    e.fireTimer = e.fireTimer - dt
    if e.fireTimer <= 0 and dist < 14 then
      e.fireTimer = 2.2 + math.random() * 1.2
      local o = e.root.position + Q.rotate(e.root.rotation, V(0.28, 0.25, -1.1))
      fireBullet(o, V.normalize(headPos - o), false)
    end
  end
end

-- ------------------------------------------------------------------ level setup
local function buildLevel(i)
  clearLevel()
  level = levels[i]
  scene.setBackground("color", { 0.03, 0.07, 0.09, 1 })
  scene.setLight(V(-0.3, -1, -0.5), { 0.9, 0.95, 1.0 }, { 0.30, 0.38, 0.42 })
  statics[#statics + 1] = spawnBox(V(0, -0.5, 0), V(30, 1, 30), C.floor, 0)
  for _, w in ipairs({ { V(0, 2, -15), V(30, 4, 0.5) }, { V(0, 2, 15), V(30, 4, 0.5) }, { V(-15, 2, 0), V(0.5, 4, 30) }, { V(15, 2, 0), V(0.5, 4, 30) } }) do
    statics[#statics + 1] = spawnBox(w[1], w[2], C.wall, 0)
  end
  for _, p in ipairs(level.pillars) do statics[#statics + 1] = spawnBox(V(p[1], 1.5, p[2]), V(p[3] or 0.8, 3, p[4] or 0.8), C.pillar, 0) end
  for _, t in ipairs(level.tables or {}) do statics[#statics + 1] = spawnBox(V(t[1], 0.45, t[2]), V(1.0, 0.9, 0.6), C.wall, 0) end
  for _, g in ipairs(level.guns) do makeGun(V(g[1], g[2], g[3])) end
  for _, e in ipairs(level.enemies) do makeEnemy(V(e[1], 0, e[2]), e[3]) end
  hx.spatial.setOrigin(V(0, 0, 0), Q.identity())
  if not input.anyManipulator() then
    gazeGun = makeGun(V(0, 1.2, -0.4))
    gazeGun.ammo = 99
  end
end

-- ------------------------------------------------------------------ UI
local function showMenu(title, subtitle, buttonText, action)
  if menuPanel then menuPanel:destroy() end
  local head, rot = hx.spatial.head()
  menuPanel = ui.panel({ width = 0.9, height = 0.55 })
  menuPanel:placeInFront(head, rot, 1.3, -0.1)
  menuPanel:add(ui.label({ text = title, size = 72, bold = true, align = "center", color = { 1, 0.7, 0.2, 1 } }))
  menuPanel:add(ui.label({ text = subtitle, size = 28, align = "center", maxWidth = 820, color = ui.theme.textDim }))
  menuPanel:add(ui.spacer({}))
  menuPanel:add(ui.button({ text = buttonText, size = 36, onClick = action }))
end

local function startLevel(i)
  levelIndex = i
  buildLevel(i)
  if menuPanel then menuPanel:destroy(); menuPanel = nil end
  state = "playing"
end

-- ------------------------------------------------------------------ main loop
function onStart()
  world = hx.physics.world({ auto = false })
  math.randomseed(os.time())
  sfx.shot = audio.synth({ wave = "noise", freq = 900, freqEnd = 120, duration = 0.18, volume = 0.8, decay = 0.15 })
  sfx.enemyShot = audio.synth({ wave = "square", freq = 220, freqEnd = 90, duration = 0.25, volume = 0.5 })
  sfx.shatter = audio.synth({ wave = "noise", freq = 3000, freqEnd = 400, duration = 0.5, volume = 0.9, decay = 0.45 })
  sfx.empty = audio.synth({ wave = "square", freq = 1400, duration = 0.04, volume = 0.3 })
  sfx.death = audio.synth({ wave = "saw", freq = 300, freqEnd = 40, duration = 1.2, volume = 0.7 })
  sfx.clear = audio.synth({ wave = "sine", freq = 440, freqEnd = 880, duration = 0.6, volume = 0.6 })
  buildLevel(1)
  showMenu("STILLSHOT", "Time moves only when you move. Pick up a pistol (grip / ZR), fire with pinch or R. Without hands or controllers, look and tap to shoot.",
    "Start", function() startLevel(1) end)
end

local function measureMotion(dt)
  local head, rot = hx.spatial.headLocal()
  local motion = 0
  if lastHeadPos and dt > 0 then
    motion = V.distance(head, lastHeadPos) / dt + Q.angleBetween(rot, lastHeadRot) / dt * 0.25
  end
  lastHeadPos, lastHeadRot = head, rot
  local l, r = input.manipulators()
  for _, m in ipairs({ l, r }) do
    if m.active then motion = motion + V.length(m.velocity) * 0.6 end
  end
  return motion
end

local function updateHeld(dt)
  local manips = { input.manipulators() }
  for side = 0, 1 do
    local m = manips[side + 1]
    local g = held[side]
    if m.active then
      if not g and m.gripPressed then
        local best, bd = nil, 0.25
        for _, cand in ipairs(guns) do
          local d = V.distance(cand.root.position, m.position)
          if d < bd and cand ~= held[1 - side] then best, bd = cand, d end
        end
        if best then held[side] = best; g = best end
      elseif g and m.gripReleased then
        -- throw: the body keeps the hand velocity
        world:drive(g.root, m.position, m.rotation, 1 / 60)
        g.root.body.vel = m.velocity * 1.4
        g.thrown = true
        held[side] = nil
        g = nil
      end
      if g then
        world:drive(g.root, m.position + Q.rotate(m.rotation, V(0, 0.02, -0.05)), m.rotation, math.max(dt, 1 / 120), 30)
        g.thrown = false
        if m.triggerPressed then shoot(g) end
      end
    end
  end
  if gazeGun then
    local head, rot = hx.spatial.head()
    local pos = head + Q.rotate(rot, V(0.12, -0.18, -0.35))
    world:drive(gazeGun.root, pos, rot, math.max(dt, 1 / 120), 30)
    if input.tap() then shoot(gazeGun) end
  end
end

local function updateBullets(sdt, headPos)
  for i = #bullets, 1, -1 do
    local b = bullets[i]
    local from = b.position
    local step = b.vel * sdt
    local len = V.length(step)
    b.life = b.life - sdt
    local hit = len > 0 and world:raycast(from, step / len, len, function(ent)
      return not (ent and ent.userData and ent.userData.kind == "gun" and b.fromPlayer)
    end)
    local remove = b.life <= 0
    if hit then
      remove = true
      local ud = hit.entity and hit.entity.userData
      if ud and ud.kind == "enemy" and b.fromPlayer then shatter(ud.enemy, V.normalize(b.vel)) end
    else
      b:setPosition(from + step)
    end
    if not b.fromPlayer and V.distance(b.position, headPos) < 0.16 then
      remove = true
      state = "dead"
    end
    -- player bullets destroy enemy bullets they cross
    if b.fromPlayer and not remove then
      for j = #bullets, 1, -1 do
        local o = bullets[j]
        if not o.fromPlayer and V.distance(o.position, b.position) < 0.12 then o.life = 0; remove = true end
      end
    end
    if remove then b:destroy(); table.remove(bullets, i) end
  end
end

local function punchesAndThrows()
  local l, r = input.manipulators()
  for _, m in ipairs({ l, r }) do
    if m.active and V.length(m.velocity) > 2.2 then
      for _, e in ipairs(enemies) do
        if not e.dead and V.distance(e.root.position + V(0, 0.4, 0), m.position) < 0.45 then shatter(e, V.normalize(m.velocity)) end
      end
    end
  end
  for _, g in ipairs(guns) do
    if g.thrown and V.length(g.root.body.vel) > 3 then
      for _, e in ipairs(enemies) do
        if not e.dead and V.distance(e.root.position + V(0, 0.3, 0), g.root.position) < 0.5 then shatter(e, V.normalize(g.root.body.vel)) end
      end
    end
  end
end

function onUpdate(dt)
  local motion = measureMotion(dt)
  if state ~= "playing" then
    timeScale = 1
    world:setTimeScale(1)
    world:step(dt)
    return
  end
  -- Time follows player motion: a floor keeps the world alive, a ceiling keeps it stable.
  local target = clamp(0.04 + motion * 0.9, 0.04, 1.0)
  timeScale = timeScale + (target - timeScale) * clamp(dt * 12, 0, 1)
  world:setTimeScale(timeScale)
  local sdt = dt * timeScale

  local head = hx.spatial.head()
  for _, g in ipairs(guns) do g.cooldown = math.max(0, g.cooldown - dt) end
  updateHeld(dt)
  for _, e in ipairs(enemies) do
    if not e.dead and enemyThink(e, sdt, head) == "hit" then state = "dead" end
  end
  updateBullets(sdt, head)
  punchesAndThrows()
  world:step(dt)
  for i = #shards, 1, -1 do
    local s = shards[i]
    s.life = s.life - sdt
    if s.life <= 0 then s:destroy(); table.remove(shards, i) end
  end

  local alive = 0
  for _, e in ipairs(enemies) do if not e.dead then alive = alive + 1 end end
  if state == "dead" then
    audio.play(sfx.death)
    showMenu("SHATTERED", "You were hit. Level " .. levelIndex .. " restarts instantly.", "Retry", function() startLevel(levelIndex) end)
  elseif alive == 0 then
    audio.play(sfx.clear)
    if levelIndex < #levels then
      state = "cleared"
      showMenu("STILL. SHOT.", level.name .. " cleared.", "Next level", function() startLevel(levelIndex + 1) end)
    else
      state = "finished"
      showMenu("STILL. SHOT.", "All " .. #levels .. " levels cleared.", "Play again", function() startLevel(1) end)
    end
  end
  -- quick restart: B / L+R together
  if input.pressed("right", "B") then startLevel(levelIndex) end
end
