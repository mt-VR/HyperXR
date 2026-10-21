-- HyperXR SDK: spatial UI toolkit (trusted SDK module, exposed as hx.ui)
-- Panels are flat rectangles in world space rendered by the host into textures. Widgets are laid
-- out in panel pixels (origin top-left). Pointer rays and fingertips are hit-tested here.
local env = ...
local N, vm, text = env.native, env.vmath, env.text
local V, Q = vm.V, vm.Q
local vec3, quat = vm.vec3, vm.quat
local max, min, floor = math.max, math.min, math.floor

local ui = {}
local panels = {}
local nextPanelId = 1
local tweens = {}

ui.theme = {
  panel = { 0.07, 0.07, 0.10, 0.88 },
  text = { 0.96, 0.96, 1.0, 1 },
  textDim = { 0.70, 0.72, 0.80, 1 },
  accent = { 0.42, 0.40, 1.0, 1 },
  accent2 = { 0.95, 0.35, 0.75, 1 },
  button = { 0.20, 0.20, 0.30, 1 },
  buttonHover = { 0.30, 0.30, 0.45, 1 },
  buttonPress = { 0.42, 0.40, 1.0, 1 },
  radius = 18,
  font = 30,
}

local function color(c, fallback) return c or fallback end

-- ------------------------------------------------------------------ widgets
local Widget = {}
Widget.__index = Widget

local function widget(kind, props, mt)
  local w = setmetatable(props or {}, mt or Widget)
  w.kind = kind
  w.x, w.y, w.w, w.h = 0, 0, 0, 0
  w.visible = (w.visible ~= false)
  w.children = w.children or {}
  return w
end

function Widget:add(child) self.children[#self.children + 1] = child; child.parent = self; self:markDirty(); return child end
function Widget:clear() self.children = {}; self:markDirty() end
function Widget:markDirty()
  local p = self
  while p.parent do p = p.parent end
  if p.panel then p.panel.dirty = true end
end
function Widget:set(props) for k, v in pairs(props) do self[k] = v end; self:markDirty(); return self end
function Widget:measure() return self.width or 0, self.height or 0 end
function Widget:layout(x, y, w, h) self.x, self.y, self.w, self.h = x, y, w, h end
function Widget:draw(p) for _, c in ipairs(self.children) do if c.visible then c:draw(p) end end end
function Widget:hit(x, y)
  if not self.visible or x < self.x or y < self.y or x > self.x + self.w or y > self.y + self.h then return nil end
  for i = #self.children, 1, -1 do
    local h = self.children[i]:hit(x, y)
    if h then return h end
  end
  if self.interactive then return self end
  return nil
end

-- Stack layout (Column / Row)
local Stack = setmetatable({}, { __index = Widget })
Stack.__index = Stack
function Stack:measure()
  local pad, gap = self.padding or 0, self.gap or 0
  local mw, mh = 0, 0
  local vis = 0
  for _, c in ipairs(self.children) do
    if c.visible then
      vis = vis + 1
      local cw, ch = c:measure()
      if self.horizontal then mw = mw + cw; mh = max(mh, ch) else mh = mh + ch; mw = max(mw, cw) end
    end
  end
  local gaps = max(0, vis - 1) * gap
  if self.horizontal then mw = mw + gaps else mh = mh + gaps end
  return self.width or (mw + 2 * pad), self.height or (mh + 2 * pad)
end
function Stack:layout(x, y, w, h)
  Widget.layout(self, x, y, w, h)
  local pad, gap = self.padding or 0, self.gap or 0
  local cx, cy = x + pad, y + pad
  local innerW, innerH = w - 2 * pad, h - 2 * pad
  -- flexible children share remaining space
  local fixed, flex = 0, 0
  for _, c in ipairs(self.children) do
    if c.visible then
      if c.flex then flex = flex + c.flex else
        local cw, ch = c:measure()
        fixed = fixed + (self.horizontal and cw or ch)
      end
    end
  end
  local count = 0
  for _, c in ipairs(self.children) do if c.visible then count = count + 1 end end
  local remaining = (self.horizontal and innerW or innerH) - fixed - max(0, count - 1) * gap
  for _, c in ipairs(self.children) do
    if c.visible then
      local cw, ch = c:measure()
      if self.horizontal then
        if c.flex then cw = max(0, remaining * c.flex / flex) end
        local chh = c.stretch ~= false and innerH or ch
        local oy = cy
        if c.stretch == false and self.align == "center" then oy = cy + (innerH - ch) / 2 end
        c:layout(cx, oy, cw, chh)
        cx = cx + cw + gap
      else
        if c.flex then ch = max(0, remaining * c.flex / flex) end
        local cww = c.stretch ~= false and innerW or cw
        local ox = cx
        if c.stretch == false and self.align == "center" then ox = cx + (innerW - cw) / 2 end
        c:layout(ox, cy, cww, ch)
        cy = cy + ch + gap
      end
    end
  end
end
function Stack:draw(p)
  if self.background then
    p:rect(self.x, self.y, self.w, self.h, self.background, self.radius or 0)
  end
  Widget.draw(self, p)
end

function ui.column(props) return widget("column", props, Stack) end
function ui.row(props) local w = widget("row", props, Stack); w.horizontal = true; return w end
function ui.spacer(props)
  props = props or {}
  if props.flex == nil and not props.width and not props.height then props.flex = 1 end
  return widget("spacer", props)
end

-- Label
local Label = setmetatable({}, { __index = Widget })
Label.__index = Label
function Label:texture()
  local key = self.text
  if self._key ~= key or self._size ~= self.size or self._color ~= self.color then
    local c = color(self.color, ui.theme.text)
    self._tex = text.get(tostring(self.text or ""), self.size or ui.theme.font, c, self.bold, self.maxWidth)
    self._key, self._size, self._color = key, self.size, self.color
  end
  return self._tex
end
function Label:measure()
  local t = self:texture()
  return self.width or t.w, self.height or t.h
end
function Label:draw(p)
  local t = self:texture()
  text.touch(t)
  local x = self.x
  if self.align == "center" then x = self.x + (self.w - t.w) / 2 elseif self.align == "right" then x = self.x + self.w - t.w end
  local y = self.y + (self.h - t.h) / 2
  p:image(t.id, floor(x), floor(y), t.w, t.h, { 1, 1, 1, self.opacity or 1 })
end
function ui.label(props)
  if type(props) == "string" then props = { text = props } end
  local l = widget("label", props, Label)
  if l.stretch == nil then l.stretch = true end
  return l
end

-- Image
local Image = setmetatable({}, { __index = Widget })
Image.__index = Image
function Image:measure() return self.width or 64, self.height or 64 end
function Image:draw(p) p:image(self.texture or 0, self.x, self.y, self.w, self.h, self.tint or { 1, 1, 1, 1 }, self.radius or 0) end
function ui.image(props) local w = widget("image", props, Image); if w.stretch == nil then w.stretch = false end; return w end

-- Button
local Button = setmetatable({}, { __index = Widget })
Button.__index = Button
function Button:measure()
  local t = self.text and text.get(self.text, self.size or ui.theme.font, self.textColor or ui.theme.text, true) or { w = 0, h = 0 }
  local iw = self.icon and (self.iconSize or 48) or 0
  local pad = self.padding or 20
  return self.width or (t.w + iw + pad * 2 + (iw > 0 and t.w > 0 and 12 or 0)), self.height or max(t.h, iw) + pad * 1.4
end
function Button:draw(p)
  local th = ui.theme
  local bg = self.pressed and (self.pressColor or th.buttonPress) or (self.hover and (self.hoverColor or th.buttonHover) or (self.color or th.button))
  if self.disabled then bg = { bg[1] * 0.5, bg[2] * 0.5, bg[3] * 0.5, bg[4] } end
  local grow = self._grow or 0
  p:rect(self.x - grow, self.y - grow, self.w + 2 * grow, self.h + 2 * grow, bg, self.radius or th.radius)
  local cx = self.x + self.w / 2
  local t = self.text and text.get(self.text, self.size or th.font, self.textColor or th.text, true)
  local iw = self.icon and (self.iconSize or 48) or 0
  local total = (t and t.w or 0) + iw + ((iw > 0 and t) and 12 or 0)
  local x = cx - total / 2
  if self.icon then
    p:image(self.icon, x, self.y + (self.h - iw) / 2, iw, iw, { 1, 1, 1, 1 }, self.iconRadius or 10)
    x = x + iw + 12
  end
  if t then text.touch(t); p:image(t.id, floor(x), floor(self.y + (self.h - t.h) / 2), t.w, t.h, { 1, 1, 1, self.disabled and 0.5 or 1 }) end
end
function Button:onPointer(phase)
  if self.disabled then return end
  if phase == "enter" then self.hover = true; ui.tween(self, "_grow", 4, 0.12)
  elseif phase == "exit" then self.hover = false; self.pressed = false; ui.tween(self, "_grow", 0, 0.12)
  elseif phase == "down" then self.pressed = true
  elseif phase == "click" then self.pressed = false; if self.onClick then self.onClick(self) end
  elseif phase == "up" or phase == "cancel" then self.pressed = false end
  self:markDirty()
end
function ui.button(props)
  if type(props) == "string" then props = { text = props } end
  local b = widget("button", props, Button)
  b.interactive = true
  b._grow = 0
  return b
end

-- Toggle
function ui.toggle(props)
  local b = ui.button(props)
  local base = b.text
  local function refresh() b.text = base .. (b.value and "  ●" or "  ○"); b.color = b.value and ui.theme.accent or nil end
  refresh()
  b.onClick = function()
    b.value = not b.value
    refresh()
    if props.onChange then props.onChange(b.value) end
  end
  return b
end

-- Progress bar
local Progress = setmetatable({}, { __index = Widget })
Progress.__index = Progress
function Progress:measure() return self.width or 200, self.height or 16 end
function Progress:draw(p)
  p:rect(self.x, self.y, self.w, self.h, self.track or { 1, 1, 1, 0.15 }, self.h / 2)
  local v = max(0, min(1, self.value or 0))
  if v > 0 then p:rect(self.x, self.y, self.w * v, self.h, self.fill or ui.theme.accent, self.h / 2) end
end
function ui.progress(props) return widget("progress", props, Progress) end

-- List (scrollable by drag)
local List = setmetatable({}, { __index = Widget })
List.__index = List
function List:measure() return self.width or 300, self.height or 300 end
function List:layout(x, y, w, h)
  Widget.layout(self, x, y, w, h)
  local ih = self.itemHeight or 64
  self.scroll = max(0, min(self.scroll or 0, max(0, #self.items * ih - h)))
end
function List:draw(p)
  local ih = self.itemHeight or 64
  local first = floor((self.scroll or 0) / ih) + 1
  local last = min(#self.items, first + floor(self.h / ih) + 1)
  p:pushClip(self.x, self.y, self.w, self.h)
  for i = first, last do
    local iy = self.y + (i - 1) * ih - self.scroll
    local selected = (i == self.selected)
    local hovered = (i == self.hoverIndex)
    if selected or hovered then p:rect(self.x, iy + 2, self.w, ih - 4, selected and ui.theme.accent or ui.theme.buttonHover, 12) end
    local item = self.items[i]
    local label = type(item) == "table" and (item.text or item.title) or tostring(item)
    local t = text.get(label, self.size or 28, ui.theme.text, false, self.w - 32)
    text.touch(t)
    p:image(t.id, self.x + 16, floor(iy + (ih - t.h) / 2), t.w, t.h, { 1, 1, 1, 1 })
  end
  p:popClip()
end
function List:indexAt(y) return floor((y - self.y + (self.scroll or 0)) / (self.itemHeight or 64)) + 1 end
function List:onPointer(phase, x, y)
  if phase == "down" then self._dragY, self._startScroll, self._moved = y, self.scroll or 0, false
  elseif phase == "move" and self._dragY then
    if math.abs(y - self._dragY) > 12 then self._moved = true end
    if self._moved then self.scroll = self._startScroll - (y - self._dragY); self:layout(self.x, self.y, self.w, self.h) end
  elseif phase == "hover" then
    local i = self:indexAt(y); self.hoverIndex = (i >= 1 and i <= #self.items) and i or nil
  elseif phase == "exit" then self.hoverIndex = nil
  elseif phase == "click" and not self._moved then
    local i = self:indexAt(y)
    if i >= 1 and i <= #self.items then
      self.selected = i
      if self.onSelect then self.onSelect(i, self.items[i]) end
    end
  end
  if phase == "up" or phase == "cancel" then self._dragY = nil end
  self:markDirty()
end
function ui.list(props)
  local l = widget("list", props, List)
  l.items = l.items or {}
  l.interactive = true
  l.scroll = 0
  return l
end

-- ------------------------------------------------------------------ panels
local Panel = {}
Panel.__index = Panel

--- ui.panel{width=0.8, height=0.5 (meters), ppm=1000 (pixels per meter), position=vec3, rotation=quat,
---          background={r,g,b,a}, radius=, draggable=true}
function ui.panel(props)
  props = props or {}
  local p = setmetatable({
    id = nextPanelId,
    width = props.width or 0.8, height = props.height or 0.5,
    ppm = props.ppm or 1000,
    position = props.position or V(0, 1.4, -1.2),
    rotation = props.rotation or Q.identity(),
    background = props.background or ui.theme.panel,
    radius = props.radius or 32,
    visible = props.visible ~= false,
    draggable = props.draggable ~= false,
    dirty = true,
    cmds = 0,
    clip = {},
  }, Panel)
  nextPanelId = nextPanelId + 1
  p.root = ui.column({ padding = props.padding or 28, gap = props.gap or 16 })
  p.root.panel = p
  panels[p.id] = p
  return p
end

function Panel:pixelSize() return floor(self.width * self.ppm), floor(self.height * self.ppm) end
function Panel:add(w) return self.root:add(w) end
function Panel:clear() self.root:clear() end
function Panel:destroy() panels[self.id] = nil; N.panel(self.id, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0) end
function Panel:setPose(pos, rot) self.position = pos; self.rotation = rot or self.rotation end
function Panel:show(v) self.visible = v ~= false; self.dirty = true end
--- Places the panel at distance d in front of the given head pose, facing it.
function Panel:placeInFront(headPos, headRot, d, yOffset)
  local yaw = Q.yaw(headRot)
  local yr = Q.axisAngle(V(0, 1, 0), yaw)
  self.position = headPos + Q.rotate(yr, V(0, yOffset or -0.05, -(d or 1.2)))
  self.rotation = yr
end
function Panel:resize(w, h) self.width, self.height = w, h; self.dirty = true end

-- drawing primitives used by widgets (panel pixel coordinates)
function Panel:rect(x, y, w, h, c, r)
  local cl = self.clip[#self.clip]
  if cl then
    local x2, y2 = min(x + w, cl[1] + cl[3]), min(y + h, cl[2] + cl[4])
    x, y = max(x, cl[1]), max(y, cl[2]); w, h = x2 - x, y2 - y
    if w <= 0 or h <= 0 then return end
  end
  N.ui(self.id, 1, 0, x, y, w, h, c[1], c[2], c[3], c[4] or 1, r or 0)
end
function Panel:image(tex, x, y, w, h, c, r)
  c = c or { 1, 1, 1, 1 }
  local cl = self.clip[#self.clip]
  if cl and (y < cl[2] or y + h > cl[2] + cl[4]) then
    if y + h < cl[2] or y > cl[2] + cl[4] then return end
  end
  N.ui(self.id, 2, tex, x, y, w, h, c[1], c[2], c[3], c[4] or 1, r or 0)
end
function Panel:pushClip(x, y, w, h) self.clip[#self.clip + 1] = { x, y, w, h } end
function Panel:popClip() self.clip[#self.clip] = nil end

function Panel:localPoint(world)
  local l = Q.rotate(Q.conjugate(self.rotation), world - self.position)
  local pw, ph = self:pixelSize()
  return (l.x / self.width + 0.5) * pw, (0.5 - l.y / self.height) * ph, l.z
end

function Panel:raycast(o, d)
  local n = Q.rotate(self.rotation, V(0, 0, 1))
  local denom = V.dot(n, d)
  if denom > -1e-5 then return nil end
  local t = V.dot(self.position - o, n) / denom
  if t < 0 then return nil end
  local px, py = self:localPoint(o + d * t)
  local pw, ph = self:pixelSize()
  if px < 0 or py < 0 or px > pw or py > ph then return nil end
  return t, px, py
end

-- ------------------------------------------------------------------ pointer routing
local pointerState = {} -- id -> {panel, widget, down, downWidget, dragPanel, ...}

local function dispatch(w, phase, x, y, ptr)
  if w and w.onPointer then w:onPointer(phase, x, y, ptr) end
end

--- pointers: list of {id=, ray={o=,d=} or touch=vec3, down=bool}
function ui.processPointers(pointers)
  local seen = {}
  for _, ptr in ipairs(pointers) do
    seen[ptr.id] = true
    local st = pointerState[ptr.id] or {}
    pointerState[ptr.id] = st
    local hitPanel, hx, hy, dist
    if st.capture then
      local p = st.capture
      if ptr.ray then
        local n = Q.rotate(p.rotation, V(0, 0, 1))
        local denom = V.dot(n, ptr.ray.d)
        if math.abs(denom) > 1e-5 then
          local t = V.dot(p.position - ptr.ray.o, n) / denom
          hx, hy = p:localPoint(ptr.ray.o + ptr.ray.d * t)
        end
      else
        hx, hy = p:localPoint(ptr.touch)
      end
      hitPanel = p
    else
      local best = math.huge
      for _, p in pairs(panels) do
        if p.visible then
          if ptr.ray then
            local t, px, py = p:raycast(ptr.ray.o, ptr.ray.d)
            if t and t < best then best, hitPanel, hx, hy = t, p, px, py end
          elseif ptr.touch then
            local px, py, z = p:localPoint(ptr.touch)
            local pw, ph = p:pixelSize()
            if z > -0.03 and z < 0.06 and px >= 0 and py >= 0 and px <= pw and py <= ph and z < best then
              best, hitPanel, hx, hy = z, p, px, py
              ptr.touchDown = z < (st.touchDown and 0.02 or 0.008)
            end
          end
        end
      end
      dist = best
    end
    local down = ptr.down or ptr.touchDown or false
    ptr.panel, ptr.x, ptr.y = hitPanel, hx, hy

    -- window dragging: grab the panel background (no widget) and move it with the ray
    if st.dragPanel then
      if down and ptr.ray then
        local p = st.dragPanel
        local target = ptr.ray.o + ptr.ray.d * st.dragDist
        p.position = target - st.dragOffset
        p.rotation = Q.lookRotation(V(p.position.x - ptr.ray.o.x, 0, p.position.z - ptr.ray.o.z))
        p.moved = true
      else
        st.dragPanel = nil
      end
    else
      local w = hitPanel and hx and hitPanel.root:hit(hx, hy) or nil
      if not st.capture and w ~= st.hover then
        dispatch(st.hover, "exit", hx, hy, ptr)
        dispatch(w, "enter", hx, hy, ptr)
        st.hover = w
      end
      if down and not st.down then
        if w then
          st.capture, st.downWidget = hitPanel, w
          dispatch(w, "down", hx, hy, ptr)
        elseif hitPanel and hitPanel.draggable and ptr.ray then
          st.dragPanel = hitPanel
          st.dragDist = dist
          st.dragOffset = (ptr.ray.o + ptr.ray.d * dist) - hitPanel.position
        end
      elseif down and st.down and st.downWidget then
        dispatch(st.downWidget, "move", hx, hy, ptr)
      elseif not down and st.down and st.downWidget then
        dispatch(st.downWidget, "up", hx, hy, ptr)
        if w == st.downWidget then dispatch(w, "click", hx, hy, ptr) end
        st.capture, st.downWidget = nil, nil
      elseif w then
        dispatch(w, "hover", hx, hy, ptr)
      end
    end
    st.down = down
    if ptr.ray and hitPanel and hx then
      st.cursorPanel, st.cx, st.cy = hitPanel, hx, hy
    else
      st.cursorPanel = nil
    end
  end
  for id, st in pairs(pointerState) do
    if not seen[id] then
      if st.downWidget then dispatch(st.downWidget, "cancel") end
      if st.hover then dispatch(st.hover, "exit") end
      pointerState[id] = nil
    end
  end
end

-- ------------------------------------------------------------------ tweens / animation
local easings = {
  linear = function(t) return t end,
  out = function(t) return 1 - (1 - t) * (1 - t) end,
  inout = function(t) return t < 0.5 and 2 * t * t or 1 - (-2 * t + 2) ^ 2 / 2 end,
  back = function(t) local c = 1.70158; return 1 + (c + 1) * (t - 1) ^ 3 + c * (t - 1) ^ 2 end,
}
--- Animates numeric field `key` of `target` to `to` over `duration` seconds.
function ui.tween(target, key, to, duration, easing, onDone)
  for i = #tweens, 1, -1 do if tweens[i].target == target and tweens[i].key == key then table.remove(tweens, i) end end
  tweens[#tweens + 1] = { target = target, key = key, from = target[key] or 0, to = to, t = 0, d = max(duration or 0.2, 1e-3),
    ease = easings[easing or "out"] or easings.out, done = onDone }
end

function ui.update(dt)
  for i = #tweens, 1, -1 do
    local tw = tweens[i]
    tw.t = tw.t + dt
    local k = min(1, tw.t / tw.d)
    tw.target[tw.key] = tw.from + (tw.to - tw.from) * tw.ease(k)
    if tw.target.markDirty then tw.target:markDirty() elseif tw.target.dirty ~= nil then tw.target.dirty = true end
    if k >= 1 then table.remove(tweens, i); if tw.done then tw.done(tw.target) end end
  end
end

--- Emits panel poses and (when dirty) their draw commands to the host.
function ui.submit()
  for id, p in pairs(panels) do
    local pw, ph = p:pixelSize()
    local pos, rot = p.position, p.rotation
    if p.dirty and p.visible then
      p.root:layout(0, 0, pw, ph)
      p:rect(0, 0, pw, ph, p.background, p.radius)
      p.root:draw(p)
    end
    N.panel(id, pos.x, pos.y, pos.z, rot.x, rot.y, rot.z, rot.w, p.width, p.height, pw, ph, p.visible and 1 or 0, p.dirty and 1 or 0)
    p.dirty = false
  end
end

function ui.panels() return panels end
function ui.cursors()
  local out = {}
  for id, st in pairs(pointerState) do
    if st.cursorPanel then out[#out + 1] = { panel = st.cursorPanel, x = st.cx, y = st.cy, id = id } end
  end
  return out
end
function ui._reset() panels = {}; tweens = {}; pointerState = {}; nextPanelId = 1 end

return ui
