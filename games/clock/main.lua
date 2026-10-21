-- Relógio: clock, date and stopwatch (HyperXR window app)
local ui, win = hx.ui, hx.window
local DAYS = { "domingo", "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado" }
local MONTHS = { "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro" }

local timeLabel, dateLabel, swLabel, startBtn
local running, elapsed, lastSecond = false, 0, -1

local function fmtStopwatch(t)
  local m = math.floor(t / 60)
  local s = t - m * 60
  return string.format("%02d:%05.2f", m, s)
end

function onStart()
  timeLabel = win:add(ui.label({ text = "--:--", size = 140, bold = true, align = "center" }))
  dateLabel = win:add(ui.label({ text = "", size = 32, align = "center", color = ui.theme.textDim }))
  win:add(ui.spacer({}))
  swLabel = win:add(ui.label({ text = fmtStopwatch(0), size = 56, align = "center", color = ui.theme.accent }))
  local row = win:add(ui.row({ gap = 16, height = 84 }))
  startBtn = row:add(ui.button({ text = "Iniciar", flex = 1, onClick = function()
    running = not running
    startBtn:set({ text = running and "Pausar" or "Continuar" })
  end }))
  row:add(ui.button({ text = "Zerar", flex = 1, onClick = function()
    running, elapsed = false, 0
    startBtn:set({ text = "Iniciar" })
    swLabel:set({ text = fmtStopwatch(0) })
  end }))
end

function onUpdate(dt)
  local now = os.date("*t")
  if now.sec ~= lastSecond then
    lastSecond = now.sec
    timeLabel:set({ text = string.format("%02d:%02d:%02d", now.hour, now.min, now.sec) })
    dateLabel:set({ text = string.format("%s, %d de %s de %d", DAYS[now.wday], now.day, MONTHS[now.month], now.year) })
  end
  if running then
    elapsed = elapsed + dt
    swLabel:set({ text = fmtStopwatch(elapsed) })
  end
end
