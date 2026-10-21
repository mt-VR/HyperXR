# Arquitetura

O repositório estava vazio no início; a arquitetura abaixo foi criada sobre Kotlin + Gradle + NDK,
com um módulo JVM puro para tudo que pode ser testado sem aparelho.

## Módulos e pacotes

Os módulos sugeridos (`HyperXR.Core`, `HyperXR.Launcher`, …) viraram **pacotes Kotlin** dentro de dois
módulos Gradle, para não criar módulos artificiais:

| Módulo sugerido | Onde está |
|---|---|
| Core | `core/…/settings`, `core/…/math`, `core/…/capabilities` |
| Launcher | `core/…/launcher` (modelo) + `app/…/xr/SpatialLauncher.kt` |
| SpatialUI | `app/…/spatialui` (painéis Kotlin) + `hxsdk/ui.lua` (painéis Lua) |
| XRRuntime / OpenXR / RuntimeBroker | `core/…/runtimebroker`, `app/…/runtimebroker`, `app/…/openxr`, `cpp/openxr_bridge.cpp` |
| Monado | integrado como runtime externo via broker (ver abaixo) |
| MixedReality | `app/…/mixedreality` (ARCore e Camera2) |
| HandTracking | `core/…/handtracking` (filtros, fusão, gestos) + `app/…/handtracking` (inferência) |
| Input / JoyCon | `core/…/input` + `app/…/joycon` |
| Rendering | `app/…/rendering` |
| Physics | `hxsdk/physics.lua` (roda no LuaJIT, dentro do runtime de jogos) |
| Environments | `core/…/environments` + `app/…/environments` |
| WebApps | `app/…/webapps` |
| GameRuntime / LuaJIT / SDK / Permissions | `app/…/gameruntime`, `app/…/luajit`, `cpp/lua_bridge.cpp`, `core/…/sdk`, `core/…/permissions`, `hxsdk/` |
| Diagnostics | `core/…/diagnostics` + `app/…/diagnostics` |

## Separação 2D / XR

- `ui2d/MainActivity` (Compose, paisagem) só gerencia: nunca desenha elementos VR/MR.
- `xr/XrActivity` (launcher) e `gameruntime/GameActivity` (jogos, processo `:game`) estendem
  `ImmersiveActivity`, que hospeda o `XrEngine` com um `XrContent`.
- Estado compartilhado entre processos passa só por disco (configurações JSON atômicas, concessões
  por pacote, relatórios de falha).

## Quadro do XrEngine

```
ARCore.update / Camera2 latch  → pose da cabeça (predição) → mãos (snapshot do thread de inferência)
→ Joy-Cons (IMU + mão) → ações → XrContent.onFrame (cena) → por olho: fundo (câmera/ambiente),
cena, painéis, aura → composição SBS com distorção  (ou xrEndFrame no caminho OpenXR)
```

Cada estágio grava sua latência em `Diagnostics` (ver PERFORMANCE.md).

## Modos imersivos (`XrModeResolver`)

1. `MIXED_REALITY_ARCORE`: ARCore disponível + câmera → passthrough, 6DoF, Depth quando suportado.
2. `MIXED_REALITY_CAMERA`: sem ARCore → Camera2 + 3DoF; a imagem é mono (sem reprojeção estéreo).
3. `VR_SBS`: sem câmera/permissão → ambiente 3D, 3DoF com modelo de pescoço.
4. `OPENXR_RUNTIME`: quando o usuário escolhe e há runtime → sessão OpenXR real (swapchains do runtime).

## Runtime Broker e Monado

- Descoberta: provedores `org.khronos.openxr.runtime_broker` / `system_runtime_broker`
  (`/openxr/1/abi/<abi>/runtimes/active/0`) e serviços `org.khronos.openxr.OpenXRRuntimeService`.
- Sonda real: `xrInitializeLoaderKHR` → extensões → `xrCreateInstance` → `xrGetSystem(HMD)`.
- Sessão única por `SessionArbiter` (estado e histórico visíveis na UI).
- **Monado**: o HyperXR o detecta e prefere quando instalado. Não é embutido no APK: um runtime
  OpenXR precisa ser o runtime ativo do loader (via broker) e o Monado no Android depende de drivers
  e serviços próprios; copiar a `.so` para dentro do app não produz um runtime funcional.

## Runtime de jogos

`LuaVm` (Kotlin) ↔ `lua_bridge.cpp` ↔ `bootstrap.lua`. Os dados quentes trafegam por buffers diretos:

| Buffer | Direção | Conteúdo |
|---|---|---|
| input (256 floats) | Kotlin → Lua | tempo, cabeça, 2 mãos (21 juntas), 2 controles, olhar/toque |
| header (32 floats) | Lua → Kotlin | pose do rig, luz, fundo, pedidos (recentralizar/sair), contadores |
| draws (18 floats cada) | Lua → Kotlin | malha/modelo, textura, flags, cor, posição, rotação, escala |
| ui (12 floats cada) / panels (14 floats cada) | Lua → Kotlin | comandos de painel (só quando "sujo") |

Chamadas de recurso (`text`, `load_model`, `audio_*`, `fs_*`, `net_request`…) usam um buffer de
argumentos pré-alocado: chamadas numéricas não alocam. Física e UI rodam em Lua (LuaJIT), evitando
JNI por entidade.
