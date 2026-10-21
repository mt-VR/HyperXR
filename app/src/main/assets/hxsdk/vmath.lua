-- HyperXR SDK: vector / quaternion math (trusted SDK module, exposed read-only as hx.vec3 / hx.quat)
local sqrt, sin, cos, acos, abs, atan2 = math.sqrt, math.sin, math.cos, math.acos, math.abs, math.atan2

local V = {}
V.__index = V

local function vec3(x, y, z) return setmetatable({ x = x or 0, y = y or 0, z = z or 0 }, V) end
V.new = vec3
function V.__add(a, b) return vec3(a.x + b.x, a.y + b.y, a.z + b.z) end
function V.__sub(a, b) return vec3(a.x - b.x, a.y - b.y, a.z - b.z) end
function V.__unm(a) return vec3(-a.x, -a.y, -a.z) end
function V.__mul(a, b)
  if type(a) == "number" then return vec3(a * b.x, a * b.y, a * b.z) end
  if type(b) == "number" then return vec3(a.x * b, a.y * b, a.z * b) end
  return vec3(a.x * b.x, a.y * b.y, a.z * b.z)
end
function V.__div(a, s) return vec3(a.x / s, a.y / s, a.z / s) end
function V.__eq(a, b) return a.x == b.x and a.y == b.y and a.z == b.z end
function V.__tostring(a) return string.format("(%.3f, %.3f, %.3f)", a.x, a.y, a.z) end
function V.dot(a, b) return a.x * b.x + a.y * b.y + a.z * b.z end
function V.cross(a, b) return vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x) end
function V.length(a) return sqrt(a.x * a.x + a.y * a.y + a.z * a.z) end
function V.lengthSq(a) return a.x * a.x + a.y * a.y + a.z * a.z end
function V.normalize(a)
  local l = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
  if l < 1e-9 then return vec3(0, 0, 0) end
  return vec3(a.x / l, a.y / l, a.z / l)
end
function V.lerp(a, b, t) return vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t) end
function V.distance(a, b) local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z return sqrt(dx * dx + dy * dy + dz * dz) end
function V.copy(a) return vec3(a.x, a.y, a.z) end
function V.set(a, x, y, z) a.x, a.y, a.z = x, y, z return a end
function V.unpack(a) return a.x, a.y, a.z end

local Q = {}
Q.__index = Q
local function quat(x, y, z, w) return setmetatable({ x = x or 0, y = y or 0, z = z or 0, w = w or 1 }, Q) end
Q.new = quat
function Q.identity() return quat(0, 0, 0, 1) end
function Q.axisAngle(axis, rad)
  local n = V.normalize(axis)
  local s = sin(rad / 2)
  return quat(n.x * s, n.y * s, n.z * s, cos(rad / 2))
end
function Q.euler(pitch, yaw, roll) -- radians, applied yaw (Y) * pitch (X) * roll (Z)
  return Q.axisAngle(vec3(0, 1, 0), yaw or 0) * Q.axisAngle(vec3(1, 0, 0), pitch or 0) * Q.axisAngle(vec3(0, 0, 1), roll or 0)
end
function Q.__mul(a, b)
  if getmetatable(b) == V then return Q.rotate(a, b) end
  return quat(
    a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z)
end
function Q.__tostring(q) return string.format("quat(%.3f, %.3f, %.3f, %.3f)", q.x, q.y, q.z, q.w) end
function Q.conjugate(q) return quat(-q.x, -q.y, -q.z, q.w) end
function Q.normalize(q)
  local l = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
  if l < 1e-9 then return quat() end
  return quat(q.x / l, q.y / l, q.z / l, q.w / l)
end
function Q.rotate(q, v)
  local tx = 2 * (q.y * v.z - q.z * v.y)
  local ty = 2 * (q.z * v.x - q.x * v.z)
  local tz = 2 * (q.x * v.y - q.y * v.x)
  return vec3(v.x + q.w * tx + (q.y * tz - q.z * ty), v.y + q.w * ty + (q.z * tx - q.x * tz), v.z + q.w * tz + (q.x * ty - q.y * tx))
end
function Q.forward(q) return Q.rotate(q, vec3(0, 0, -1)) end
function Q.up(q) return Q.rotate(q, vec3(0, 1, 0)) end
function Q.right(q) return Q.rotate(q, vec3(1, 0, 0)) end
function Q.slerp(a, b, t)
  local bx, by, bz, bw = b.x, b.y, b.z, b.w
  local d = a.x * bx + a.y * by + a.z * bz + a.w * bw
  if d < 0 then bx, by, bz, bw, d = -bx, -by, -bz, -bw, -d end
  if d > 0.9995 then
    return Q.normalize(quat(a.x + (bx - a.x) * t, a.y + (by - a.y) * t, a.z + (bz - a.z) * t, a.w + (bw - a.w) * t))
  end
  local h = acos(d)
  local s = sqrt(1 - d * d)
  local ra, rb = sin((1 - t) * h) / s, sin(t * h) / s
  return quat(a.x * ra + bx * rb, a.y * ra + by * rb, a.z * ra + bz * rb, a.w * ra + bw * rb)
end
function Q.lookRotation(forward, up)
  local f = V.normalize(forward)
  up = up or vec3(0, 1, 0)
  local r = V.normalize(V.cross(f, up))
  if V.lengthSq(r) < 1e-9 then r = vec3(1, 0, 0) end
  local u = V.cross(r, f)
  -- rotation matrix columns: r, u, -f
  local m00, m01, m02 = r.x, u.x, -f.x
  local m10, m11, m12 = r.y, u.y, -f.y
  local m20, m21, m22 = r.z, u.z, -f.z
  local tr = m00 + m11 + m22
  if tr > 0 then
    local s = sqrt(tr + 1) * 2
    return quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
  elseif m00 > m11 and m00 > m22 then
    local s = sqrt(1 + m00 - m11 - m22) * 2
    return quat(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
  elseif m11 > m22 then
    local s = sqrt(1 + m11 - m00 - m22) * 2
    return quat((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
  else
    local s = sqrt(1 + m22 - m00 - m11) * 2
    return quat((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
  end
end
function Q.yaw(q)
  local f = Q.forward(q)
  return atan2(-f.x, -f.z)
end
function Q.angleBetween(a, b)
  local d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w)
  if d > 1 then d = 1 end
  return 2 * acos(d)
end
--- Integrates angular velocity w (world space, rad/s) over dt.
function Q.integrate(q, w, dt)
  local dq = quat(w.x * dt * 0.5, w.y * dt * 0.5, w.z * dt * 0.5, 0) * q
  return Q.normalize(quat(q.x + dq.x, q.y + dq.y, q.z + dq.z, q.w + dq.w))
end
function Q.copy(q) return quat(q.x, q.y, q.z, q.w) end

-- Calling the library builds a value: vec3(1, 2, 3), quat(0, 0, 0, 1)
setmetatable(V, { __call = function(_, x, y, z) return vec3(x, y, z) end })
setmetatable(Q, { __call = function(_, x, y, z, w) return quat(x, y, z, w) end })

return { vec3 = V, quat = Q, V = V, Q = Q }
