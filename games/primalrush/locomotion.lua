-- Arm locomotion for PRIMAL RUSH (written for HyperXR).
-- The player has no legs: hands that touch geometry stay fixed in the world and the body moves
-- opposite to the hand motion. Releasing a hand keeps the body's recent velocity (a leap).
local V, Q = hx.vec3, hx.quat
local M = {}
M.__index = M

function M.new(world, opts)
  opts = opts or {}
  return setmetatable({
    world = world,
    handRadius = opts.handRadius or 0.07,
    headRadius = opts.headRadius or 0.2,
    gravity = opts.gravity or 9.81,
    maxSpeed = opts.maxSpeed or 7.5,
    jumpMultiplier = opts.jumpMultiplier or 1.15,
    releaseDistance = opts.releaseDistance or 0.025,
    origin = opts.origin or V(0, 0, 0),
    vel = V(0, 0, 0),
    hands = { [0] = { stuck = false }, [1] = { stuck = false } },
    history = {}, historyIndex = 0,
    grounded = false,
    solid = opts.solid,
  }, M)
end

local function avgVelocity(self)
  local sum, n = V(0, 0, 0), 0
  for _, v in pairs(self.history) do sum = sum + v; n = n + 1 end
  return n > 0 and sum / n or V(0, 0, 0)
end

--- handsLocal: {[0]=vec3|nil, [1]=vec3|nil} hand positions in tracking space; headLocal: vec3; rot: rig rotation
function M:update(dt, handsLocal, headLocal, rot)
  if dt <= 0 then return end
  local prevOrigin = self.origin
  local anyStuck = false
  for side = 0, 1 do if self.hands[side].stuck then anyStuck = true end end

  -- free flight when no hand is planted
  if not anyStuck then
    self.vel = self.vel + V(0, -self.gravity * dt, 0)
    local s = V.length(self.vel)
    if s > self.maxSpeed * 1.5 then self.vel = self.vel * (self.maxSpeed * 1.5 / s) end
    self.origin = self.origin + self.vel * dt
  end

  -- planted hands pin the body
  local correction, count = V(0, 0, 0), 0
  for side = 0, 1 do
    local h = self.hands[side]
    local hl = handsLocal[side]
    if h.stuck and hl then
      local target = self.origin + Q.rotate(rot, hl)
      local away = V.dot(target - h.point, h.normal)
      if away > self.releaseDistance then
        h.stuck = false
      else
        correction = correction + (h.point - target)
        count = count + 1
      end
    elseif h.stuck then
      h.stuck = false
    end
  end
  if count > 0 then self.origin = self.origin + correction / count end

  -- new contacts: a hand entering geometry plants itself on the surface
  for side = 0, 1 do
    local h = self.hands[side]
    local hl = handsLocal[side]
    if hl and not h.stuck then
      local target = self.origin + Q.rotate(rot, hl)
      local push, normal = self.world:overlapSphere(target, self.handRadius, self.solid)
      if normal then
        h.stuck = true
        h.point = target + push
        h.normal = normal
        h.surfaceTime = 0
        self.origin = self.origin + push
      end
    end
  end

  -- head must not enter geometry
  local head = self.origin + Q.rotate(rot, headLocal)
  local push, normal = self.world:overlapSphere(head, self.headRadius, self.solid)
  if normal then
    self.origin = self.origin + push
    local vn = V.dot(self.vel, normal)
    if vn < 0 then self.vel = self.vel - normal * vn end
  end

  local nowStuck = false
  for side = 0, 1 do if self.hands[side].stuck then nowStuck = true end end
  local frameVel = (self.origin - prevOrigin) / dt
  self.historyIndex = self.historyIndex % 5 + 1
  self.history[self.historyIndex] = frameVel
  if anyStuck and not nowStuck then
    -- leap: keep the recent body velocity, boosted and clamped
    local v = avgVelocity(self) * self.jumpMultiplier
    local s = V.length(v)
    if s > self.maxSpeed then v = v * (self.maxSpeed / s) end
    self.vel = v
  elseif nowStuck then
    self.vel = V(0, 0, 0)
  end
  self.grounded = nowStuck
end

--- Gaze fallback when there are no hands: leap towards a direction if touching something.
function M:leap(dir, strength)
  local d = V.normalize(V(dir.x, 0, dir.z))
  self.vel = d * (strength or 4) + V(0, 4.2, 0)
  for side = 0, 1 do self.hands[side].stuck = false end
end

function M:speed() return V.length(avgVelocity(self)) end

return M
