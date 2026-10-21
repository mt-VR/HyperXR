-- Host-side stand-in for the native bridge, used to run the real SDK bootstrap under a desktop
-- LuaJIT. Mirrors the C functions registered by app/src/main/cpp/lua_bridge.cpp.
local M = {}

local function readFile(path)
  local f = io.open(path, "rb")
  if not f then return nil end
  local s = f:read("*a")
  f:close()
  return s
end
M.readFile = readFile

--- Creates a stub native table. sdkDir: hxsdk sources; pkgDir: unpacked package root.
function M.new(sdkDir, pkgDir)
  local n = { inputs = {}, draws = 0, uiCmds = {}, panels = {}, header = {}, logs = {}, errors = {}, calls = {},
    sounds = 0, textures = 0 }
  for i = 0, 255 do n.inputs[i] = 0 end
  -- default head: standing, looking forward
  n.inputs[5] = 1.6; n.inputs[10] = 1
  n.inputs[176 + 7] = 1; n.inputs[208 + 7] = 1

  local native = {}
  function native.input(i) return n.inputs[i] or 0 end
  function native.draw(...) n.draws = n.draws + 1; n.lastDraw = { ... } end
  function native.ui(panel, kind, tex, x, y, w, h, r, g, b, a, radius)
    n.uiCmds[#n.uiCmds + 1] = { panel = panel, kind = kind, tex = tex, x = x, y = y, w = w, h = h }
  end
  function native.panel(id, ...) n.panels[id] = { ... } end
  function native.header(i, v) n.header[i] = v end
  function native.clock() return os.clock() end
  function native.log(level, msg) n.logs[#n.logs + 1] = msg; if os.getenv("HX_VERBOSE") then print("[lua]", msg) end end
  function native.sdk(name) return readFile(sdkDir .. "/" .. name .. ".lua") end
  function native.call(name, ...)
    n.calls[name] = (n.calls[name] or 0) + 1
    local a = { ... }
    if name == "text" then
      n.textures = n.textures + 1
      return n.textures, math.floor(#a[1] * a[2] * 0.5), math.floor(a[2] * 1.3)
    elseif name == "asset_text" then
      local p = a[1]
      if p:find("%.%.") or p:sub(1, 1) == "/" then return nil, "invalid path" end
      local s = readFile(pkgDir .. "/" .. p)
      if not s then return nil, "not found: " .. p end
      return s
    elseif name == "load_model" or name == "load_texture" then
      n.textures = n.textures + 1
      return 1000 + n.textures
    elseif name == "model_bounds" then return -0.5, 0, -0.5, 0.5, 1, 0.5
    elseif name == "audio_synth" or name == "audio_load" then n.sounds = n.sounds + 1; return n.sounds
    elseif name == "audio_play" then return 1
    elseif name == "game_error" then n.errors[#n.errors + 1] = a[1]; return true
    elseif name == "net_request" then return true
    elseif name == "fs_read" then return n.fs and n.fs[a[1]]
    elseif name == "fs_write" then n.fs = n.fs or {}; n.fs[a[1]] = a[2]; return true
    elseif name == "fs_list" then return ""
    end
    return true
  end
  n.native = native
  return n
end

--- Boots a package: returns runtime table and stub state.
function M.boot(sdkDir, pkgDir, config)
  local n = M.new(sdkDir, pkgDir)
  local src = assert(readFile(sdkDir .. "/bootstrap.lua"))
  local fn = assert(loadstring(src, "@bootstrap.lua"))
  local rt = fn(n.native, config)
  return rt, n
end

return M
