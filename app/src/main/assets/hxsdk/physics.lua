-- HyperXR SDK: rigid-body physics (trusted SDK module, exposed as hx.physics)
-- Shapes: sphere, box (oriented). Sequential-impulse solver with Coulomb friction, restitution,
-- Baumgarte stabilisation, sleeping, triggers, point joints (hinges = two points) and queries.
local vm = ...
local V, Q = vm.V, vm.Q
local vec3, quat = vm.vec3, vm.quat
local abs, sqrt, min, max, huge = math.abs, math.sqrt, math.min, math.max, math.huge

local P = {}
local World = {}
World.__index = World

local SLOP = 0.004
local BAUMGARTE = 0.25
local SLEEP_LIN, SLEEP_ANG, SLEEP_TIME = 0.06, 0.08, 0.6

-- ---------------------------------------------------------------- helpers
local function invInertiaMul(b, v)
  if b.invMass == 0 then return V(0, 0, 0) end
  local l = Q.rotate(Q.conjugate(b.rot), v)
  local ii = b.invInertia
  return Q.rotate(b.rot, V(l.x * ii.x, l.y * ii.y, l.z * ii.z))
end

local function velAt(b, r)
  local w = b.angVel
  return V(b.vel.x + w.y * r.z - w.z * r.y, b.vel.y + w.z * r.x - w.x * r.z, b.vel.z + w.x * r.y - w.y * r.x)
end

local function applyImpulse(b, imp, r)
  if b.invMass == 0 then return end
  b.vel = b.vel + imp * b.invMass
  b.angVel = b.angVel + invInertiaMul(b, V.cross(r, imp))
end

local function wake(b)
  if b.sleeping then b.sleeping = false; b.sleepTimer = 0 end
end

local function boxAxes(b)
  return Q.rotate(b.rot, V(1, 0, 0)), Q.rotate(b.rot, V(0, 1, 0)), Q.rotate(b.rot, V(0, 0, 1))
end

local function computeAabb(b)
  if b.shape == "sphere" then
    local r = b.radius
    b.aMinX, b.aMinY, b.aMinZ = b.pos.x - r, b.pos.y - r, b.pos.z - r
    b.aMaxX, b.aMaxY, b.aMaxZ = b.pos.x + r, b.pos.y + r, b.pos.z + r
  else
    local ax, ay, az = boxAxes(b)
    local h = b.half
    local ex = abs(ax.x) * h.x + abs(ay.x) * h.y + abs(az.x) * h.z
    local ey = abs(ax.y) * h.x + abs(ay.y) * h.y + abs(az.y) * h.z
    local ez = abs(ax.z) * h.x + abs(ay.z) * h.y + abs(az.z) * h.z
    b.aMinX, b.aMinY, b.aMinZ = b.pos.x - ex, b.pos.y - ey, b.pos.z - ez
    b.aMaxX, b.aMaxY, b.aMaxZ = b.pos.x + ex, b.pos.y + ey, b.pos.z + ez
  end
end

-- ---------------------------------------------------------------- narrowphase
-- Each returns a list of {point=, normal= (A->B), depth=}

local function sphereSphere(a, b, out)
  local d = b.pos - a.pos
  local dist = V.length(d)
  local rs = a.radius + b.radius
  if dist >= rs then return end
  local n = dist > 1e-6 and d / dist or V(0, 1, 0)
  out[#out + 1] = { point = a.pos + n * (a.radius - (rs - dist) * 0.5), normal = n, depth = rs - dist }
end

-- sphere s vs box bx; normal points from sphere to box when flip == false
local function sphereBoxRaw(center, radius, bx)
  local local_ = Q.rotate(Q.conjugate(bx.rot), center - bx.pos)
  local h = bx.half
  local cx = max(-h.x, min(h.x, local_.x))
  local cy = max(-h.y, min(h.y, local_.y))
  local cz = max(-h.z, min(h.z, local_.z))
  local dx, dy, dz = local_.x - cx, local_.y - cy, local_.z - cz
  local d2 = dx * dx + dy * dy + dz * dz
  if d2 > radius * radius then return nil end
  local nLocal, depth, pLocal
  if d2 > 1e-12 then
    local d = sqrt(d2)
    nLocal = V(dx / d, dy / d, dz / d) -- box -> sphere
    depth = radius - d
    pLocal = V(cx, cy, cz)
  else
    -- centre inside the box: push out through the nearest face
    local px, py, pz = h.x - abs(local_.x), h.y - abs(local_.y), h.z - abs(local_.z)
    if px <= py and px <= pz then nLocal = V(local_.x >= 0 and 1 or -1, 0, 0); depth = px + radius
    elseif py <= pz then nLocal = V(0, local_.y >= 0 and 1 or -1, 0); depth = py + radius
    else nLocal = V(0, 0, local_.z >= 0 and 1 or -1); depth = pz + radius end
    pLocal = local_
  end
  local nWorld = Q.rotate(bx.rot, nLocal)
  return nWorld, depth, bx.pos + Q.rotate(bx.rot, pLocal)
end

local function sphereBox(s, bx, out, sphereIsA)
  local nBoxToSphere, depth, point = sphereBoxRaw(s.pos, s.radius, bx)
  if not nBoxToSphere then return end
  local n = sphereIsA and -nBoxToSphere or nBoxToSphere
  out[#out + 1] = { point = point, normal = n, depth = depth }
end

local function pointInBox(p, b, tol)
  local l = Q.rotate(Q.conjugate(b.rot), p - b.pos)
  local h = b.half
  return abs(l.x) <= h.x + tol and abs(l.y) <= h.y + tol and abs(l.z) <= h.z + tol
end

local function boxVertices(b)
  local ax, ay, az = boxAxes(b)
  local h = b.half
  local vs = {}
  for i = 0, 7 do
    local sx = (i % 2 == 0) and -1 or 1
    local sy = (math.floor(i / 2) % 2 == 0) and -1 or 1
    local sz = (i < 4) and -1 or 1
    vs[#vs + 1] = b.pos + ax * (sx * h.x) + ay * (sy * h.y) + az * (sz * h.z)
  end
  return vs
end

local function boxBox(a, b, out)
  local A = { boxAxes(a) }
  local B = { boxAxes(b) }
  local ha = { a.half.x, a.half.y, a.half.z }
  local hb = { b.half.x, b.half.y, b.half.z }
  local d = b.pos - a.pos
  local bestOverlap, bestAxis, bestRa, bestRb = huge, nil, 0, 0
  local function test(L)
    local len = V.length(L)
    if len < 1e-6 then return true end
    L = L / len
    local ra = ha[1] * abs(V.dot(A[1], L)) + ha[2] * abs(V.dot(A[2], L)) + ha[3] * abs(V.dot(A[3], L))
    local rb = hb[1] * abs(V.dot(B[1], L)) + hb[2] * abs(V.dot(B[2], L)) + hb[3] * abs(V.dot(B[3], L))
    local dist = V.dot(d, L)
    local overlap = ra + rb - abs(dist)
    if overlap < 0 then return false end
    -- prefer face axes slightly to avoid unstable edge normals
    if overlap < bestOverlap - 1e-4 then
      bestOverlap, bestRa, bestRb = overlap, ra, rb
      bestAxis = dist < 0 and -L or L
    end
    return true
  end
  for i = 1, 3 do if not test(A[i]) then return end end
  for i = 1, 3 do if not test(B[i]) then return end end
  for i = 1, 3 do for j = 1, 3 do if not test(V.cross(A[i], B[j])) then return end end end
  local n = bestAxis
  local pts = {}
  for _, v in ipairs(boxVertices(b)) do
    if pointInBox(v, a, 0.002) then
      pts[#pts + 1] = { point = v, depth = bestRa - V.dot(v - a.pos, n) }
    end
  end
  for _, v in ipairs(boxVertices(a)) do
    if pointInBox(v, b, 0.002) then
      pts[#pts + 1] = { point = v, depth = bestRb - V.dot(v - b.pos, -n) }
    end
  end
  if #pts == 0 then
    pts[1] = { point = a.pos + n * (bestRa - bestOverlap * 0.5), depth = bestOverlap }
  end
  table.sort(pts, function(p1, p2) return p1.depth > p2.depth end)
  for i = 1, min(4, #pts) do
    local p = pts[i]
    out[#out + 1] = { point = p.point, normal = n, depth = max(0, min(p.depth, bestOverlap)) }
  end
end

local function collide(a, b, out)
  if a.shape == "sphere" then
    if b.shape == "sphere" then sphereSphere(a, b, out) else sphereBox(a, b, out, true) end
  else
    if b.shape == "sphere" then sphereBox(b, a, out, false) else boxBox(a, b, out) end
  end
end

-- ---------------------------------------------------------------- world
function P.newWorld(opts)
  opts = opts or {}
  local w = setmetatable({
    gravity = opts.gravity or V(0, -9.81, 0),
    bodies = {}, joints = {}, nextId = 1,
    iterations = opts.iterations or 10,
    maxSubstep = opts.maxSubstep or (1 / 90),
    timeScale = 1,
    contactsThisStep = 0,
  }, World)
  return w
end

--- Adds a body. def: {shape="box"|"sphere", size=vec3 (box full size), radius=, mass=0 (static),
--- position, rotation, restitution, friction, kinematic, trigger, layer, mask, gravityScale, userData}
function World:add(def)
  local b = {
    id = self.nextId,
    shape = def.shape or "box",
    radius = def.radius or 0.5,
    half = def.size and V(def.size.x / 2, def.size.y / 2, def.size.z / 2) or V(0.5, 0.5, 0.5),
    pos = def.position and V.copy(def.position) or V(0, 0, 0),
    rot = def.rotation and Q.copy(def.rotation) or Q.identity(),
    vel = def.velocity and V.copy(def.velocity) or V(0, 0, 0),
    angVel = V(0, 0, 0),
    restitution = def.restitution or 0.2,
    friction = def.friction or 0.6,
    kinematic = def.kinematic or false,
    trigger = def.trigger or false,
    layer = def.layer or 1,
    mask = def.mask or 0xFFFF,
    gravityScale = def.gravityScale or 1,
    linearDamping = def.linearDamping or 0.02,
    angularDamping = def.angularDamping or 0.05,
    sleeping = false, sleepTimer = 0,
    userData = def.userData,
    onCollision = def.onCollision,
    onTrigger = def.onTrigger,
  }
  self.nextId = self.nextId + 1
  local mass = def.mass or 0
  if mass > 0 and not b.kinematic then
    b.invMass = 1 / mass
    if b.shape == "sphere" then
      local i = 0.4 * mass * b.radius * b.radius
      b.invInertia = V(1 / i, 1 / i, 1 / i)
    else
      local x, y, z = 2 * b.half.x, 2 * b.half.y, 2 * b.half.z
      b.invInertia = V(12 / (mass * (y * y + z * z)), 12 / (mass * (x * x + z * z)), 12 / (mass * (x * x + y * y)))
    end
  else
    b.invMass = 0
    b.invInertia = V(0, 0, 0)
  end
  b.static = (b.invMass == 0 and not b.kinematic)
  b.mass = mass
  computeAabb(b)
  self.bodies[#self.bodies + 1] = b
  return b
end

function World:remove(b)
  for i, x in ipairs(self.bodies) do
    if x == b then table.remove(self.bodies, i) break end
  end
  for i = #self.joints, 1, -1 do
    local j = self.joints[i]
    if j.a == b or j.b == b then table.remove(self.joints, i) end
  end
  b.removed = true
end

--- Point joint: keeps localA on body a coincident with localB on body b (or a world point when b is nil).
--- Two point joints along an axis form a hinge (doors, levers).
function World:addPointJoint(a, localA, b, localB)
  local j = { a = a, la = V.copy(localA), b = b, lb = localB and V.copy(localB) or V(0, 0, 0) }
  self.joints[#self.joints + 1] = j
  return j
end

function World:removeJoint(j)
  for i, x in ipairs(self.joints) do if x == j then table.remove(self.joints, i) return end end
end

function World:applyImpulse(b, impulse, point)
  wake(b)
  applyImpulse(b, impulse, point and (point - b.pos) or V(0, 0, 0))
end

function World:setVelocity(b, v) wake(b); b.vel = V.copy(v) end

--- Drives a dynamic body towards a target pose through velocities, so it still collides
--- (used for grabbed objects and physical hands).
function World:drive(b, targetPos, targetRot, dt, maxSpeed)
  wake(b)
  maxSpeed = maxSpeed or 12
  local v = (targetPos - b.pos) / max(dt, 1e-3)
  local s = V.length(v)
  if s > maxSpeed then v = v * (maxSpeed / s) end
  b.vel = v
  if targetRot then
    local dq = targetRot * Q.conjugate(b.rot)
    if dq.w < 0 then dq = Q.new(-dq.x, -dq.y, -dq.z, -dq.w) end
    local sinHalf = sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z)
    if sinHalf > 1e-6 then
      local angle = 2 * math.atan2(sinHalf, dq.w)
      local w = V(dq.x / sinHalf, dq.y / sinHalf, dq.z / sinHalf) * (angle / max(dt, 1e-3))
      local ws = V.length(w)
      if ws > 40 then w = w * (40 / ws) end
      b.angVel = w
    else
      b.angVel = V(0, 0, 0)
    end
  end
end

local function shouldCollide(a, b)
  if a.static and b.static then return false end
  if a.invMass == 0 and b.invMass == 0 and not (a.trigger or b.trigger) then return false end
  if a.sleeping and (b.sleeping or b.static) then return false end
  if bit.band(a.layer, b.mask) == 0 or bit.band(b.layer, a.mask) == 0 then return false end
  return a.aMaxX >= b.aMinX and a.aMinX <= b.aMaxX and a.aMaxY >= b.aMinY and a.aMinY <= b.aMaxY
     and a.aMaxZ >= b.aMinZ and a.aMinZ <= b.aMaxZ
end

local function solveJoint(j, dt)
  local a, b = j.a, j.b
  local ra = Q.rotate(a.rot, j.la)
  local pa = a.pos + ra
  local rb, pb
  if b then rb = Q.rotate(b.rot, j.lb); pb = b.pos + rb else rb = V(0, 0, 0); pb = j.lb end
  local err = pa - pb
  local va = velAt(a, ra)
  local vb = b and velAt(b, rb) or V(0, 0, 0)
  local target = -(va - vb) - err * (BAUMGARTE / dt)
  -- per-axis approximate solve (diagonal of K), stable with several iterations
  local axes = { V(1, 0, 0), V(0, 1, 0), V(0, 0, 1) }
  for _, n in ipairs(axes) do
    local k = a.invMass + V.dot(n, V.cross(invInertiaMul(a, V.cross(ra, n)), ra))
    if b then k = k + b.invMass + V.dot(n, V.cross(invInertiaMul(b, V.cross(rb, n)), rb)) end
    if k > 1e-9 then
      local lambda = V.dot(target, n) / k
      applyImpulse(a, n * lambda, ra)
      if b then applyImpulse(b, n * -lambda, rb) end
    end
  end
end

function World:substep(dt)
  local bodies = self.bodies
  -- integrate velocities
  for _, b in ipairs(bodies) do
    if b.invMass > 0 and not b.sleeping then
      b.vel = b.vel + self.gravity * (b.gravityScale * dt)
      b.vel = b.vel * (1 / (1 + dt * b.linearDamping))
      b.angVel = b.angVel * (1 / (1 + dt * b.angularDamping))
    end
    computeAabb(b)
  end
  -- broad + narrow phase
  local contacts = {}
  local n = #bodies
  for i = 1, n do
    local a = bodies[i]
    for k = i + 1, n do
      local b = bodies[k]
      if shouldCollide(a, b) then
        local list = {}
        collide(a, b, list)
        if #list > 0 then
          if a.trigger or b.trigger then
            if a.onTrigger then a.onTrigger(a, b) end
            if b.onTrigger then b.onTrigger(b, a) end
          else
            if (a.sleeping and b.invMass > 0 and V.lengthSq(b.vel) > 0.01) or (a.sleeping and b.kinematic) then wake(a) end
            if (b.sleeping and a.invMass > 0 and V.lengthSq(a.vel) > 0.01) or (b.sleeping and a.kinematic) then wake(b) end
            for _, c in ipairs(list) do
              c.a, c.b = a, b
              contacts[#contacts + 1] = c
            end
          end
        end
      end
    end
  end
  self.contactsThisStep = #contacts
  -- prepare contacts
  for _, c in ipairs(contacts) do
    local a, b = c.a, c.b
    c.ra = c.point - a.pos
    c.rb = c.point - b.pos
    local nrm = c.normal
    local kn = a.invMass + b.invMass
      + V.dot(nrm, V.cross(invInertiaMul(a, V.cross(c.ra, nrm)), c.ra))
      + V.dot(nrm, V.cross(invInertiaMul(b, V.cross(c.rb, nrm)), c.rb))
    c.massN = kn > 0 and 1 / kn or 0
    local vn = V.dot(velAt(b, c.rb) - velAt(a, c.ra), nrm)
    local e = max(a.restitution, b.restitution)
    c.bias = BAUMGARTE / dt * max(c.depth - SLOP, 0)
    if vn < -1 then c.bias = c.bias - e * vn end
    c.accN, c.accT = 0, 0
    c.mu = sqrt(a.friction * b.friction)
  end
  -- iterate
  for _ = 1, self.iterations do
    for _, j in ipairs(self.joints) do solveJoint(j, dt) end
    for _, c in ipairs(contacts) do
      local a, b, nrm = c.a, c.b, c.normal
      local vrel = velAt(b, c.rb) - velAt(a, c.ra)
      local vn = V.dot(vrel, nrm)
      local lambda = (c.bias - vn) * c.massN
      local newAcc = max(c.accN + lambda, 0)
      lambda = newAcc - c.accN
      c.accN = newAcc
      local imp = nrm * lambda
      applyImpulse(a, -imp, c.ra)
      applyImpulse(b, imp, c.rb)
      -- friction
      vrel = velAt(b, c.rb) - velAt(a, c.ra)
      local vt = vrel - nrm * V.dot(vrel, nrm)
      local vtl = V.length(vt)
      if vtl > 1e-6 then
        local t = vt / vtl
        local kt = a.invMass + b.invMass
          + V.dot(t, V.cross(invInertiaMul(a, V.cross(c.ra, t)), c.ra))
          + V.dot(t, V.cross(invInertiaMul(b, V.cross(c.rb, t)), c.rb))
        if kt > 0 then
          local lt = -vtl / kt
          local maxF = c.mu * c.accN
          local nt = max(-maxF, min(c.accT + lt, maxF))
          lt = nt - c.accT
          c.accT = nt
          local timp = t * lt
          applyImpulse(a, -timp, c.ra)
          applyImpulse(b, timp, c.rb)
        end
      end
    end
  end
  -- integrate positions + sleeping
  for _, b in ipairs(bodies) do
    if (b.invMass > 0 and not b.sleeping) or b.kinematic then
      b.pos = b.pos + b.vel * dt
      if V.lengthSq(b.angVel) > 1e-12 then b.rot = Q.integrate(b.rot, b.angVel, dt) end
      if b.invMass > 0 then
        if V.length(b.vel) < SLEEP_LIN and V.length(b.angVel) < SLEEP_ANG then
          b.sleepTimer = b.sleepTimer + dt
          if b.sleepTimer > SLEEP_TIME then b.sleeping = true; b.vel = V(0, 0, 0); b.angVel = V(0, 0, 0) end
        else
          b.sleepTimer = 0
        end
      end
    end
  end
  -- collision callbacks
  for _, c in ipairs(contacts) do
    if c.accN > 0 then
      if c.a.onCollision then c.a.onCollision(c.a, c.b, c.accN, c.point, -c.normal) end
      if c.b.onCollision then c.b.onCollision(c.b, c.a, c.accN, c.point, c.normal) end
    end
  end
end

--- Advances the simulation by dt * timeScale using fixed substeps (max 6 per call).
function World:step(dt)
  dt = dt * self.timeScale
  if dt <= 0 then return end
  local steps = math.min(6, math.max(1, math.ceil(dt / self.maxSubstep)))
  local h = dt / steps
  for _ = 1, steps do self:substep(h) end
end

-- ---------------------------------------------------------------- queries
local function raySphere(o, d, c, r)
  local m = o - c
  local b = V.dot(m, d)
  local cc = V.dot(m, m) - r * r
  if cc > 0 and b > 0 then return nil end
  local disc = b * b - cc
  if disc < 0 then return nil end
  local t = -b - sqrt(disc)
  if t < 0 then t = 0 end
  return t
end

local function rayBox(o, d, b)
  local inv = Q.conjugate(b.rot)
  local lo = Q.rotate(inv, o - b.pos)
  local ld = Q.rotate(inv, d)
  local tmin, tmax = -huge, huge
  local nAxis, nSign = 1, 1
  local h = { b.half.x, b.half.y, b.half.z }
  local os_ = { lo.x, lo.y, lo.z }
  local ds = { ld.x, ld.y, ld.z }
  for i = 1, 3 do
    if abs(ds[i]) < 1e-9 then
      if os_[i] < -h[i] or os_[i] > h[i] then return nil end
    else
      local t1 = (-h[i] - os_[i]) / ds[i]
      local t2 = (h[i] - os_[i]) / ds[i]
      local s = -1
      if t1 > t2 then t1, t2 = t2, t1; s = 1 end
      if t1 > tmin then tmin = t1; nAxis = i; nSign = s end
      if t2 < tmax then tmax = t2 end
      if tmin > tmax then return nil end
    end
  end
  if tmax < 0 then return nil end
  local nl = V(0, 0, 0)
  if nAxis == 1 then nl.x = nSign elseif nAxis == 2 then nl.y = nSign else nl.z = nSign end
  return max(tmin, 0), Q.rotate(b.rot, nl)
end

--- Returns {body, point, normal, distance} for the closest hit, or nil.
function World:raycast(origin, dir, maxDist, filter)
  dir = V.normalize(dir)
  maxDist = maxDist or 100
  local best, bestT, bestN = nil, maxDist, nil
  for _, b in ipairs(self.bodies) do
    if not b.trigger and (not filter or filter(b)) then
      local t, n
      if b.shape == "sphere" then
        t = raySphere(origin, dir, b.pos, b.radius)
        if t then n = V.normalize(origin + dir * t - b.pos) end
      else
        t, n = rayBox(origin, dir, b)
      end
      if t and t < bestT then best, bestT, bestN = b, t, n end
    end
  end
  if not best then return nil end
  return { body = best, point = origin + dir * bestT, normal = bestN, distance = bestT }
end

--- Sphere overlap against solid bodies. Returns push-out vector (to separate the sphere),
--- the deepest contact normal and the list of touched bodies. Used for character/hand controllers.
function World:overlapSphere(center, radius, filter)
  local push = V(0, 0, 0)
  local touched = {}
  local bestDepth, bestNormal = 0, nil
  for _, b in ipairs(self.bodies) do
    if not b.trigger and (not filter or filter(b)) then
      local n, depth
      if b.shape == "sphere" then
        local d = center - b.pos
        local dist = V.length(d)
        if dist < radius + b.radius then
          n = dist > 1e-6 and d / dist or V(0, 1, 0)
          depth = radius + b.radius - dist
        end
      else
        n, depth = sphereBoxRaw(center, radius, b)
      end
      if n then
        push = push + n * depth
        touched[#touched + 1] = b
        if depth > bestDepth then bestDepth, bestNormal = depth, n end
      end
    end
  end
  return push, bestNormal, touched
end

P.World = World
return P
