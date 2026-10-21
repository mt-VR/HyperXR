-- Calendário (HyperXR window app): month grid, navigation and day markers.
-- Markers persist in the package's private storage when the filesystem permission is granted.
local ui, win = hx.ui, hx.window
local MONTHS = { "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro" }
local WEEK = { "D", "S", "T", "Q", "Q", "S", "S" }

local year, month
local marks = {}
local title, grid, status

local function daysIn(y, m)
  return os.date("*t", os.time({ year = m == 12 and y + 1 or y, month = m == 12 and 1 or m + 1, day = 1, hour = 12 }) - 86400).day
end

local function key(y, m, d) return string.format("%04d-%02d-%02d", y, m, d) end

local function save()
  if not hx.fs then return end
  local list = {}
  for k in pairs(marks) do list[#list + 1] = k end
  table.sort(list)
  hx.fs.write("marks.txt", table.concat(list, "\n"))
end

local function load_()
  if not hx.fs then return end
  local s = hx.fs.read("marks.txt")
  for line in (s or ""):gmatch("[^\n]+") do marks[line] = true end
end

local render

local function shift(delta)
  month = month + delta
  if month < 1 then month, year = 12, year - 1 elseif month > 12 then month, year = 1, year + 1 end
  render()
end

function render()
  title:set({ text = MONTHS[month] .. " " .. year })
  grid:clear()
  local header = grid:add(ui.row({ gap = 8, height = 40 }))
  for _, w in ipairs(WEEK) do header:add(ui.label({ text = w, size = 26, align = "center", flex = 1, color = ui.theme.textDim })) end
  local first = os.date("*t", os.time({ year = year, month = month, day = 1, hour = 12 })).wday
  local today = os.date("*t")
  local day = 1
  local total = daysIn(year, month)
  for _ = 1, 6 do
    if day > total then break end
    local row = grid:add(ui.row({ gap = 8, flex = 1 }))
    for col = 1, 7 do
      local cellDay = day
      if (cellDay == 1 and col < first) or cellDay > total then
        row:add(ui.spacer({ flex = 1 }))
      else
        local k = key(year, month, cellDay)
        local isToday = today.year == year and today.month == month and today.day == cellDay
        row:add(ui.button({
          text = tostring(cellDay) .. (marks[k] and " •" or ""), size = 30, flex = 1,
          color = marks[k] and ui.theme.accent2 or (isToday and ui.theme.accent or nil),
          onClick = function()
            marks[k] = not marks[k] or nil
            save()
            status:set({ text = (marks[k] and "Marcado: " or "Desmarcado: ") .. k .. (hx.fs and "" or " (sem permissão de arquivos: não será salvo)") })
            render()
          end,
        }))
        day = day + 1
      end
    end
  end
end

function onStart()
  local now = os.date("*t")
  year, month = now.year, now.month
  load_()
  local top = win:add(ui.row({ gap = 12, height = 80 }))
  top:add(ui.button({ text = "‹", size = 44, width = 90, onClick = function() shift(-1) end }))
  title = top:add(ui.label({ text = "", size = 46, bold = true, align = "center", flex = 1 }))
  top:add(ui.button({ text = "›", size = 44, width = 90, onClick = function() shift(1) end }))
  grid = win:add(ui.column({ gap = 8, flex = 1 }))
  status = win:add(ui.label({ text = "Toque em um dia para marcá-lo.", size = 24, color = ui.theme.textDim }))
  render()
end
