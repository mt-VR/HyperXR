# HyperXR SDK (nível 1)

Jogos e apps espaciais em **LuaJIT 5.1**, distribuídos como pacotes `.hyperx`.

## Formato `.hyperx`

Arquivo ZIP com `manifest.json` na raiz e os arquivos do pacote. A inspeção **nunca executa
conteúdo**: lê o manifesto, percorre a tabela de entradas e calcula hashes.

```json
{
  "id": "com.exemplo.meujogo",
  "name": "Meu Jogo",
  "version": "1.0.0",
  "sdk": 1,
  "mode": "scene",
  "entry": "main.lua",
  "permissions": ["ui", "input", "physics", "audio"],
  "author": "Você",
  "license": "MIT",
  "description": "…",
  "window": { "width": 0.9, "height": 0.6, "resizable": true },
  "network": { "allowedHosts": ["api.exemplo.com"] },
  "integrity": { "algorithm": "sha256", "files": { "main.lua": "<sha256>" } }
}
```

Regras verificadas (`HyperxPackage.inspect`):
- `id` reverse-domain minúsculo; `version` semântica; `mode` = `scene` | `window`; `entry` existe.
- Permissões conhecidas; `network` exige `allowedHosts`; chaves desconhecidas no manifesto são erro.
- Todo arquivo (exceto o manifesto) precisa estar em `integrity.files` com SHA-256 correto.
- Caminhos absolutos, `..`, `\`, `:`, caracteres de controle e nomes duplicados são rejeitados.
- Limites: 4096 entradas, 256 MB por arquivo, 768 MB total, razão de compressão ≤ 200 (zip bomb).

A integridade detecta corrupção e adulteração **depois** do empacotamento; ela não prova autoria
(assinatura por chave de publicador é um item futuro, ver STATUS.md).

Empacotar: `./gradlew :core:hyperx --args="pack <pasta> <saida.hyperx>"` (calcula os hashes).
Validar: `./gradlew :core:hyperx --args="inspect arquivo.hyperx"`.

## Ciclo de vida

O arquivo `entry` roda uma vez (com orçamento ampliado). Depois o runtime chama, se existirem:

```lua
function onStart() end        -- uma vez
function onUpdate(dt) end     -- a cada quadro (dt em segundos)
function onPause() end
function onResume() end
function onExit() end
```

`require("pasta.modulo")` carrega `pasta/modulo.lua` do próprio pacote.

## Modos

- **Scene**: entidades 3D, física, iluminação, modelos glTF/GLB, áudio espacial, mãos e controles.
- **Window**: `hx.window` é um painel já criado na frente do usuário (tamanho do manifesto) — basta
  adicionar widgets. As APIs comuns (assets, áudio, entrada, permissões) são as mesmas.

## API

### Matemática
`hx.V3(x,y,z)`, `hx.Q4(x,y,z,w)`; bibliotecas `hx.vec3` (`dot`, `cross`, `normalize`, `length`,
`lerp`, `distance`) e `hx.quat` (`axisAngle`, `euler`, `rotate`, `slerp`, `lookRotation`, `forward`,
`yaw`, `angleBetween`). Operadores `+ - * /` e `quat * vec3`.

### Cena (`hx.scene`)
```lua
local world = hx.physics.world{ auto = true }               -- avança sozinho após onUpdate
local chao = hx.scene.spawn{ position = hx.V3(0,-0.5,0), scale = hx.V3(10,1,10),
                             color = {0.2,0.2,0.25,1}, body = { world = world } }   -- estático (mass 0)
local bola = hx.scene.spawn{ mesh = hx.scene.mesh.sphere, scale = 0.3, position = hx.V3(0,2,-1),
                             body = { world = world, mass = 1, restitution = 0.6 } }
local navio = hx.scene.spawn{ model = hx.assets.model("modelos/navio.glb"), position = hx.V3(2,0,-3) }
bola:setColor(1, 0.3, 0.3); bola:setVisible(true); bola:destroy()
filho:attach(pai)                       -- deslocamento em metros no referencial do pai
hx.scene.setLight(hx.V3(-0.3,-1,-0.4), {1,1,1}, {0.3,0.3,0.4})
hx.scene.setBackground("passthrough")   -- "color" | "environment" | "passthrough" (câmera em MR)
```
Malhas: `cube`, `sphere`, `plane`, `cylinder`, `quad`. Flags: `unlit`, `transparent`, `doubleSided`.

### Física (`hx.physics`, permissão `physics`)
Corpos esfera e caixa orientada; contatos por SAT, impulsos sequenciais com atrito, restituição,
sono, triggers, juntas de ponto. `world:raycast(o, d, max, filtro)`, `world:overlapSphere(c, r)`,
`world:hinge(entidade, pivô, eixo)`, `world:impulse(e, impulso, ponto)`,
`world:drive(e, posAlvo, rotAlvo, dt)` (objetos agarrados que continuam colidindo),
`world:setTimeScale(s)`.

### UI (`hx.ui`, permissão `ui`)
```lua
local p = hx.ui.panel{ width = 0.8, height = 0.5, position = hx.V3(0,1.4,-1.2) }
p:add(hx.ui.label{ text = "Olá", size = 48, bold = true })
local linha = p:add(hx.ui.row{ gap = 12, height = 80 })
linha:add(hx.ui.button{ text = "OK", flex = 1, onClick = function() hx.log("ok") end })
p:add(hx.ui.list{ items = {"a","b"}, flex = 1, onSelect = function(i, item) end })
p:add(hx.ui.progress{ value = 0.4 }); p:add(hx.ui.image{ texture = hx.assets.texture("logo.png") })
hx.ui.tween(objeto, "campo", 1.0, 0.3, "out", aoTerminar)
p:placeInFront(cabecaPos, cabecaRot, 1.2)
```
Widgets: `panel`, `column`, `row`, `spacer`, `label`, `button`, `toggle`, `image`, `list`,
`progress`. Entradas: olhar + toque, raio de Joy-Con (R/ZR/A), Direct Touch com o indicador.
Arrastar o fundo de um painel o move.

### Entrada (`hx.input`, `input`) e mãos (`hx.hands`, `hand_tracking`)
`hx.input.manipulators()` → mão esquerda/direita unificadas (mão rastreada ou Joy-Con):
`active, source, position, rotation, direction, velocity, grip, gripPressed, gripReleased, trigger, triggerPressed`.
`hx.input.pressed("right","R")`, `down`, `released`, `tap()`, `gazeRay()`, `controller(lado)`.
`hx.hands.get("left")` → `tracked, confidence, pinch, gesture, depthEstimated, joints[0..20]`.

### Espaço (`hx.spatial`)
`head()`, `headLocal()`, `setOrigin(pos, rot)` (locomoção: move o "rig"), `getOrigin()`,
`recenter()`, `mixedReality()`.

### Áudio (`hx.audio`, `audio`)
`load("som.wav")`, `synth{ wave="sine|square|saw|noise", freq, freqEnd, duration, volume, attack, decay }`,
`play(id, { volume, pitch, loop, position = hx.V3(...) })`, `stop(voz)`.

### Arquivos (`hx.fs`, `filesystem`) e rede (`hx.net`, `network`)
`hx.fs.read/write/list/delete` só dentro da pasta privada do pacote (cota de 16 MB, 1 MB por arquivo).
`hx.net.get(url, function(status, corpo, erro) end)` — só HTTPS e só hosts de `allowedHosts`; redirecionamentos não são seguidos.

### Diversos
`hx.log`, `hx.warn`, `hx.time.now()/dt/frame`, `hx.app.exit()`, `hx.app.id/name/version/mode`,
`hx.assets.text(path)`, `hx.permissions` (concedidas, só leitura).

## Permissões

| Chave | Concessão | Significado |
|---|---|---|
| `ui`, `audio`, `input`, `spatial`, `physics` | na instalação (listadas ao usuário) | APIs correspondentes |
| `hand_tracking` | perguntada | juntas e gestos das mãos (sem pixels da câmera) |
| `filesystem` | perguntada | pasta privada do pacote |
| `network` | perguntada | HTTPS para hosts declarados |
| `camera` | perguntada | reservada; no nível 1 scripts não recebem pixels (use fundo `passthrough`) |
| `unsafe` | confirmação explícita digitada, só no app 2D | desliga o sandbox (FFI, debug, bytecode, sem orçamento) |

APIs sem permissão simplesmente não existem no ambiente do script, e o host verifica de novo cada chamada.

## Layout dos buffers

Documentado em `LuaVm.kt` (constantes `IN_*`, `H_*`) e espelhado em `bootstrap.lua`.
Botões (bitmask): R=1, L=2, ZR=4, ZL=8, +=16, −=32, HOME=64, A=128, B=256, X=512, Y=1024, SL=2048, SR=4096, analógico=8192.
