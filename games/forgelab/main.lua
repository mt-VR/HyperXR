-- FORGE LAB: physics sandbox with grabbing, tools, mechanisms and challenges.
-- Original HyperXR game. All geometry and sound are procedural.
local V, Q = hx.vec3, hx.quat
local scene, ui, input, audio = hx.scene, hx.ui, hx.input, hx.audio

local C = {
  floor = { 0.20, 0.21, 0.24, 1 }, wall = { 0.30, 0.32, 0.36, 1 }, trim = { 0.95, 0.55, 0.15, 1 },
  crate = { 0.75, 0.52, 0.28, 1 }, ball = { 0.25, 0.75, 0.95, 1 }, metal = { 0.55, 0.57, 0.62, 1 },
  handle = { 0.35, 0.22, 0.12, 1 }, glass = { 0.6, 0.9, 1.0, 0.35 }, plate = { 0.9, 0.2, 0.25, 1 },
  plateOn = { 0.2, 0.9, 0.4, 1 }, gate = { 0.85, 0.75, 0.15, 1 }, hand = { 0.95, 0.95, 1.0, 0.9 },
  body = { 0.4, 0.42, 0.5, 1 }, hoop = { 0.95, 0.3, 0.6, 1 },
}

local world
local grabbables = {}
local held = { [0] = nil, [1] = nil }
local handProxy = { [0] = nil, [1] = nil }
local bodyProxy
local gazeHeld = nil
local challenges = {}
local hudPanel, hudLabels = nil, {}
local sfx = {}
local gate, gateTargetY = nil, 0
local plate, plateTrigger, plateActive = nil, nil, false
local glassPanes = {}
local hoopTrigger, hoopScored = nil, false
local lever, platform
local rigPos, rigYaw = V(0, 0, 1.5), 0

local function box(pos, size, color, mass, extra)
  local def = { position = pos, scale = size, color = color, body = { world = world, mass = mass or 0 } }
  for k, v in pairs(extra or {}) do def.body[k] = v end
  return scene.spawn(def)
end

local function grabbable(e, kind)
  e.userData = { kind = kind or "prop", grabbable = true }
  grabbables[#grabbables + 1] = e
  return e
end

-- ------------------------------------------------------------------ level
local function buildLab()
  scene.setBackground("color", { 0.08, 0.08, 0.1, 1 })
  scene.setLight(V(0.3, -1, -0.4), { 1.0, 0.96, 0.9 }, { 0.35, 0.36, 0.42 })
  box(V(0, -0.25, 0), V(14, 0.5, 14), C.floor)
  box(V(0, 2.5, -7), V(14, 5, 0.3), C.wall); box(V(0, 2.5, 7), V(14, 5, 0.3), C.wall)
  box(V(-7, 2.5, 0), V(0.3, 5, 14), C.wall); box(V(7, 2.5, 0), V(0.3, 5, 14), C.wall)
  scene.spawn({ position = V(0, 0.02, -6.84), scale = V(14, 0.04, 0.02), color = C.trim, unlit = true })

  -- workbench with props
  box(V(0, 0.45, -0.6), V(2.0, 0.9, 0.8), C.metal)
  for i = 0, 2 do
    grabbable(box(V(-0.7 + i * 0.25, 1.05, -0.5), V(0.18, 0.18, 0.18), C.crate, 1.0, { friction = 0.8 }), "crate")
  end
  for i = 0, 1 do
    grabbable(scene.spawn({ mesh = scene.mesh.sphere, position = V(0.3 + i * 0.22, 1.05, -0.7), scale = 0.16, color = C.ball,
      body = { world = world, mass = 0.4, restitution = 0.7, friction = 0.5 } }), "ball")
  end
  -- hammer: one physics box (handle), head is a visual child; mass makes impacts strong
  local hammer = grabbable(box(V(0.75, 1.0, -0.4), V(0.05, 0.05, 0.4), C.handle, 1.6, { friction = 0.9 }), "hammer")
  local head = scene.spawn({ position = V(0, 0, -0.2), scale = V(0.08, 0.14, 0.08), color = C.metal })
  head:attach(hammer)
  hammer.userData.grabOffset = V(0, 0, 0.12)

  -- Challenge 1: heavy crate on the pressure plate opens the gate
  plate = box(V(-4, 0.02, -2), V(0.9, 0.04, 0.9), C.plate)
  plateTrigger = world:addBody({ shape = "box", size = V(0.8, 0.3, 0.8), position = V(-4, 0.2, -2), trigger = true })
  grabbable(box(V(-2.2, 0.25, -1.2), V(0.5, 0.5, 0.5), C.crate, 6.0, { friction = 0.9 }), "heavy")
  gate = box(V(-4, 1.25, -4.5), V(2.5, 2.5, 0.15), C.gate, 0, { kinematic = true })
  box(V(-5.4, 1.5, -4.5), V(0.3, 3, 0.3), C.wall); box(V(-2.6, 1.5, -4.5), V(0.3, 3, 0.3), C.wall)
  gateTargetY = 1.25

  -- Challenge 2: hinged door leads to the glass wall; smash all panes with the hammer
  local door = box(V(3.2, 1.1, -2.2), V(1.0, 2.2, 0.08), C.wall, 8, { angularDamping = 1.5 })
  world:hinge(door, V(2.7, 1.1, -2.2), V(0, 1, 0))
  door.userData = { kind = "door" }
  box(V(2.45, 1.5, -2.2), V(0.1, 3, 0.3), C.metal); box(V(4.95, 1.5, -2.2), V(2.9, 3, 0.3), C.wall)
  for r = 0, 1 do
    for c = 0, 2 do
      local pane = box(V(2.6 + c * 0.7, 0.7 + r * 0.9, -4.2), V(0.65, 0.85, 0.04), C.glass, 0, {})
      pane.transparentPane = true
      pane.body.onCollision = function(self, other, impulse)
        if impulse > 1.0 and other.userData and other.userData.userData and other.userData.userData.kind == "hammer" then
          pane.broken = true
        end
      end
      glassPanes[#glassPanes + 1] = pane
    end
  end

  -- Challenge 3: throw the ball through the hoop
  box(V(5.5, 1.6, 2.5), V(0.1, 3.2, 0.1), C.metal)
  scene.spawn({ mesh = scene.mesh.cylinder, position = V(5.2, 2.6, 2.5), scale = V(0.6, 0.03, 0.6), color = C.hoop })
  hoopTrigger = world:addBody({ shape = "box", size = V(0.45, 0.15, 0.45), position = V(5.2, 2.5, 2.5), trigger = true,
    onTrigger = function(self, other)
      local e = other.userData
      if e and e.userData and e.userData.kind == "ball" then hoopScored = true end
    end })

  -- Lever (hinged) raises a platform with a bonus ball
  lever = box(V(-5.5, 1.0, 2.5), V(0.06, 0.6, 0.06), C.trim, 1.0)
  world:hinge(lever, V(-5.5, 0.75, 2.5), V(1, 0, 0))
  lever.userData = { kind = "lever", grabbable = true }
  grabbables[#grabbables + 1] = lever
  box(V(-5.5, 0.6, 2.5), V(0.3, 0.3, 0.3), C.metal)
  platform = box(V(-4.2, 0.1, 4.5), V(1.2, 0.2, 1.2), C.trim, 0, { kinematic = true })
  grabbable(scene.spawn({ mesh = scene.mesh.sphere, position = V(-4.2, 0.4, 4.5), scale = 0.2, color = C.hoop,
    body = { world = world, mass = 0.5, restitution = 0.6 } }), "ball")
end

-- ------------------------------------------------------------------ challenges + HUD
local function setupChallenges()
  challenges = {
    { name = "Put the heavy crate on the red plate", done = false, check = function() return plateActive end },
    { name = "Smash every glass pane with the hammer", done = false, check = function()
      for _, p in ipairs(glassPanes) do if not p.broken then return false end end return true end },
    { name = "Score the ball through the hoop", done = false, check = function() return hoopScored end },
    { name = "Pull the lever to raise the platform", done = false, check = function()
      return platform.position.y > 1.2 end },
  }
  hudPanel = ui.panel({ width = 1.2, height = 0.75, position = V(0, 2.2, -6.7), draggable = false })
  hudPanel:add(ui.label({ text = "FORGE LAB", size = 64, bold = true, color = C.trim }))
  for i, ch in ipairs(challenges) do
    hudLabels[i] = hudPanel:add(ui.label({ text = "○  " .. ch.name, size = 34 }))
  end
  hudPanel:add(ui.spacer({}))
  hudLabels.footer = hudPanel:add(ui.label({ text = "Grip (fist / ZR) grabs. Release to throw. Stick or gaze-tap moves.", size = 24, color = ui.theme.textDim }))
  hudPanel:add(ui.button({ text = "Reset lab", onClick = function()
    scene.clear(); grabbables = {}; glassPanes = {}; held[0], held[1], gazeHeld = nil, nil, nil
    world = hx.physics.world({ auto = false }); hudPanel:destroy(); hudLabels = {}
    plateActive, hoopScored = false, false
    buildLab(); setupChallenges(); handProxy = {}; bodyProxy = nil
  end }))
end

local function updateChallenges()
  local completed = 0
  for i, ch in ipairs(challenges) do
    if not ch.done and ch.check() then
      ch.done = true
      audio.play(sfx.success)
      hudLabels[i]:set({ text = "●  " .. ch.name, color = C.plateOn })
    end
    if ch.done then completed = completed + 1 end
  end
  if completed == #challenges and not challenges.allDone then
    challenges.allDone = true
    hudLabels.footer:set({ text = "All challenges complete. The lab is yours.", color = C.plateOn })
  end
end

-- ------------------------------------------------------------------ hands and grabbing
local function nearestGrabbable(pos, radius, exclude)
  local best, bd = nil, radius
  for _, e in ipairs(grabbables) do
    if not e.destroyed and e ~= exclude and e.body then
      local d = V.distance(e.position, pos) - math.max(e.scale.x, e.scale.y, e.scale.z) * 0.5
      if d < bd then best, bd = e, d end
    end
  end
  return best
end

local function updateHands(dt)
  local manips = { input.manipulators() }
  for side = 0, 1 do
    local m = manips[side + 1]
    -- physical hand proxy: a kinematic sphere that pushes objects
    if m.active then
      if not handProxy[side] then
        handProxy[side] = scene.spawn({ mesh = scene.mesh.sphere, position = m.position, scale = 0.09, color = C.hand,
          body = { world = world, kinematic = true, shape = "sphere", radius = 0.045 } })
      end
      local hp = handProxy[side]
      local v = (m.position - hp.position) / math.max(dt, 1e-3)
      hp:setPosition(m.position)
      hp.body.vel = v -- kinematic velocity lets the solver transfer momentum to pushed objects
      hp:setVisible(held[side] == nil)
    elseif handProxy[side] then
      handProxy[side]:destroy(); handProxy[side] = nil
    end

    local g = held[side]
    if m.active and m.gripPressed and not g then
      g = nearestGrabbable(m.position, 0.12, held[1 - side])
      if g then
        held[side] = g
        -- remember the object's pose relative to the hand so it does not snap
        local inv = Q.conjugate(m.rotation)
        g.grabLocalPos = Q.rotate(inv, g.position - m.position)
        g.grabLocalRot = inv * g.rotation
        audio.play(sfx.grab, { position = m.position, volume = 0.4 })
      end
    elseif g and (not m.active or m.gripReleased) then
      held[side] = nil
      g.body.vel = m.velocity * 1.2
    end
    if held[side] and m.active then
      local target = m.position + Q.rotate(m.rotation, g.grabLocalPos)
      local rot = m.rotation * g.grabLocalRot
      world:drive(g, target, g.userData.kind ~= "lever" and rot or nil, math.max(dt, 1 / 120), 15)
    end
  end
end

local function updateGaze(dt)
  if input.anyManipulator() then return end
  local head, rot = hx.spatial.head()
  if gazeHeld then
    local target = head + Q.forward(rot) * 0.8
    world:drive(gazeHeld, target, nil, math.max(dt, 1 / 120), 10)
    if input.tap() then
      gazeHeld.body.vel = Q.forward(rot) * 6
      gazeHeld = nil
    end
  elseif input.tap() then
    local hit = world:raycast(head, Q.forward(rot), 4, function(e) return e and e.userData and e.userData.grabbable end)
    if hit and hit.entity and hit.entity.userData.kind ~= "lever" then
      gazeHeld = hit.entity
    elseif hit and hit.entity then
      world:impulse(hit.entity, Q.forward(rot) * 2, hit.point)
    else
      -- tap on the floor: walk there (teleport-free smooth move happens in updateLocomotion)
      local floorHit = world:raycast(head, Q.forward(rot), 10)
      if floorHit and floorHit.normal.y > 0.7 then rigPos = V(floorHit.point.x, 0, floorHit.point.z) end
    end
  end
end

local function updateLocomotion(dt)
  local l = input.controller("left")
  local origin, rot = hx.spatial.getOrigin()
  if l and l.connected and (math.abs(l.stick.x) > 0.15 or math.abs(l.stick.y) > 0.15) then
    local _, headRot = hx.spatial.head()
    local yaw = Q.axisAngle(V(0, 1, 0), Q.yaw(headRot))
    local move = Q.rotate(yaw, V(l.stick.x, 0, -l.stick.y)) * (2.0 * dt)
    rigPos = origin + move
  end
  local r = input.controller("right")
  if r and r.connected and input.pressed("right", "STICK") then rigYaw = rigYaw + math.pi / 6 end
  -- smooth approach to target position, blocked by walls via body proxy collision
  local newPos = origin + (rigPos - origin) * math.min(1, dt * 6)
  local head = hx.spatial.head()
  local headLocal = hx.spatial.headLocal()
  local probe = V(newPos.x + headLocal.x, 1.0, newPos.z + headLocal.z)
  local push = world:overlapSphere(probe, 0.25, function(e) return not (e and e.userData and e.userData.grabbable) end)
  newPos = newPos + V(push.x, 0, push.z)
  rigPos = V(rigPos.x + push.x, 0, rigPos.z + push.z)
  hx.spatial.setOrigin(V(newPos.x, 0, newPos.z), Q.axisAngle(V(0, 1, 0), rigYaw))
  -- virtual body: torso capsule (as box) under the head that objects bump into
  if not bodyProxy then
    bodyProxy = scene.spawn({ position = V(head.x, 1.0, head.z), scale = V(0.36, 0.7, 0.22), color = C.body,
      body = { world = world, kinematic = true, size = V(0.36, 0.7, 0.22) } })
    bodyProxy:setVisible(false)
  end
  local _, hr = hx.spatial.head()
  bodyProxy:setPosition(V(head.x, math.max(0.4, head.y - 0.6), head.z))
  bodyProxy:setRotation(Q.axisAngle(V(0, 1, 0), Q.yaw(hr)))
end

-- ------------------------------------------------------------------ mechanisms
local function updateMechanisms(dt)
  -- pressure plate: heavy object resting inside the trigger volume
  local active = false
  local tp = plateTrigger.pos
  for _, e in ipairs(grabbables) do
    if e.userData.kind == "heavy" and math.abs(e.position.x - tp.x) < 0.45 and math.abs(e.position.z - tp.z) < 0.45 and e.position.y < 0.5 then
      active = true
    end
  end
  if active ~= plateActive then
    plateActive = active
    plate:setColor(active and C.plateOn or C.plate)
    audio.play(sfx.click, { position = plate.position })
  end
  local targetY = plateActive and 3.9 or 1.25
  local gp = gate.position
  local ny = gp.y + (targetY - gp.y) * math.min(1, dt * 1.5)
  gate.body.vel = V(0, (ny - gp.y) / math.max(dt, 1e-3), 0)
  gate:setPosition(V(gp.x, ny, gp.z))

  for i = #glassPanes, 1, -1 do
    local p = glassPanes[i]
    if p.broken and not p.shattered then
      p.shattered = true
      audio.play(sfx.glass, { position = p.position })
      for k = 1, 8 do
        local s = scene.spawn({ position = p.position + V((math.random() - 0.5) * 0.5, (math.random() - 0.5) * 0.7, 0),
          scale = V(0.1 + math.random() * 0.1, 0.1 + math.random() * 0.15, 0.02), color = C.glass,
          body = { world = world, mass = 0.1 } })
        world:impulse(s, V((math.random() - 0.5) * 0.3, 0.2, -0.3))
      end
      p:setVisible(false)
      world:removeBody(p.body)
      p.body = nil
    end
  end

  -- lever angle drives the platform height
  local up = Q.rotate(lever.rotation, V(0, 1, 0))
  local angle = math.atan2(-up.z, up.y)
  local h = math.max(0.1, math.min(1.6, 0.1 + math.max(0, angle) * 1.4))
  local pp = platform.position
  local ny2 = pp.y + (h - pp.y) * math.min(1, dt * 3)
  platform.body.vel = V(0, (ny2 - pp.y) / math.max(dt, 1e-3), 0)
  platform:setPosition(V(pp.x, ny2, pp.z))
end

-- ------------------------------------------------------------------ lifecycle
function onStart()
  world = hx.physics.world({ auto = false })
  sfx.grab = audio.synth({ wave = "sine", freq = 600, freqEnd = 900, duration = 0.06, volume = 0.3 })
  sfx.click = audio.synth({ wave = "square", freq = 1200, duration = 0.05, volume = 0.4 })
  sfx.glass = audio.synth({ wave = "noise", freq = 5000, freqEnd = 1500, duration = 0.6, volume = 0.9, decay = 0.5 })
  sfx.success = audio.synth({ wave = "sine", freq = 523, freqEnd = 1046, duration = 0.5, volume = 0.6 })
  buildLab()
  setupChallenges()
  hx.spatial.setOrigin(rigPos, Q.identity())
end

function onUpdate(dt)
  updateLocomotion(dt)
  updateHands(dt)
  updateGaze(dt)
  updateMechanisms(dt)
  world:step(dt)
  updateChallenges()
  -- objects that fall out of the lab come back to the bench
  for _, e in ipairs(grabbables) do
    if not e.destroyed and e.body and e.position.y < -3 then e:setPosition(V(0, 1.3, -0.6)) end
  end
end
