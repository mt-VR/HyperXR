-- Calculadora (HyperXR window app). Expressions are evaluated by a small parser,
-- never by load(), so the input can not execute code.
local ui, win = hx.ui, hx.window
local expr = ""
local display, history

local function tokenize(s)
  local tokens, i = {}, 1
  while i <= #s do
    local c = s:sub(i, i)
    if c:match("%s") then i = i + 1
    elseif c:match("[%d%.]") then
      local num = s:match("^[%d%.]+", i)
      if select(2, num:gsub("%.", "")) > 1 then return nil, "número inválido" end
      tokens[#tokens + 1] = { t = "num", v = tonumber(num) }
      i = i + #num
    elseif c:match("[%+%-%*/%%%^%(%)]") then
      tokens[#tokens + 1] = { t = "op", v = c }
      i = i + 1
    else return nil, "caractere inválido" end
  end
  return tokens
end

-- recursive descent: expr := term (('+'|'-') term)* ; term := unary (('*'|'/'|'%') unary)* ;
-- unary := ('-'|'+') unary | power ; power := primary ('^' unary)? ; primary := num | '(' expr ')'
local function evaluate(s)
  local tokens, err = tokenize(s)
  if not tokens then return nil, err end
  local pos = 1
  local function peek() return tokens[pos] end
  local function take() pos = pos + 1; return tokens[pos - 1] end
  local parseExpr
  local function primary()
    local t = take()
    if not t then error("expressão incompleta", 0) end
    if t.t == "num" then return t.v end
    if t.v == "(" then
      local v = parseExpr()
      local close = take()
      if not close or close.v ~= ")" then error("falta ')'", 0) end
      return v
    end
    error("símbolo inesperado " .. t.v, 0)
  end
  local unary
  local function power()
    local base = primary()
    local t = peek()
    if t and t.v == "^" then take(); return base ^ unary() end -- right-associative
    return base
  end
  function unary() -- binds looser than '^', so -2^2 = -4
    local t = peek()
    if t and t.v == "-" then take(); return -unary() end
    if t and t.v == "+" then take(); return unary() end
    return power()
  end
  local function term()
    local v = unary()
    while peek() and (peek().v == "*" or peek().v == "/" or peek().v == "%") do
      local op = take().v
      local r = unary()
      if op == "*" then v = v * r elseif op == "/" then
        if r == 0 then error("divisão por zero", 0) end
        v = v / r
      else v = v % r end
    end
    return v
  end
  function parseExpr()
    local v = term()
    while peek() and (peek().v == "+" or peek().v == "-") do
      local op = take().v
      local r = term()
      v = op == "+" and v + r or v - r
    end
    return v
  end
  local ok, result = pcall(parseExpr)
  if not ok then return nil, result end
  if pos <= #tokens then return nil, "símbolo inesperado" end
  return result
end
calculator_evaluate = evaluate -- exposed for tests

local function fmt(v)
  if v ~= v or v == math.huge or v == -math.huge then return "erro" end
  if math.floor(v) == v and math.abs(v) < 1e15 then return string.format("%d", v) end
  return (string.format("%.10g", v))
end

local function press(key)
  if key == "C" then expr = ""
  elseif key == "⌫" then expr = expr:sub(1, -2)
  elseif key == "=" then
    local v, err = evaluate(expr)
    if v then
      history:set({ text = expr .. " =" })
      expr = fmt(v)
    else
      history:set({ text = "erro: " .. tostring(err) })
    end
  else
    expr = expr .. key
  end
  display:set({ text = expr == "" and "0" or expr })
end

function onStart()
  history = win:add(ui.label({ text = " ", size = 26, align = "right", color = ui.theme.textDim }))
  display = win:add(ui.label({ text = "0", size = 72, bold = true, align = "right" }))
  local keys = {
    { "C", "(", ")", "/" },
    { "7", "8", "9", "*" },
    { "4", "5", "6", "-" },
    { "1", "2", "3", "+" },
    { "0", ".", "⌫", "=" },
  }
  for _, rowKeys in ipairs(keys) do
    local row = win:add(ui.row({ gap = 12, flex = 1 }))
    for _, k in ipairs(rowKeys) do
      local accent = (k == "=") and ui.theme.accent or (k:match("[%d%.]") and nil or { 0.26, 0.26, 0.38, 1 })
      row:add(ui.button({ text = k, size = 40, flex = 1, color = accent, onClick = function() press(k) end }))
    end
  end
end
