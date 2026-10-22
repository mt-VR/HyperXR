# HyperXR

Plataforma XR para celulares Android em VR Box, headsets móveis e realidade mista: launcher espacial
(modos **Dock** e **New**), realidade mista com câmera, VR estereoscópico SBS, integração OpenXR com
Runtime Broker, hand tracking próprio com aura preta e Direct Touch, Joy-Cons, ambientes 3D
licenciados, apps web e um SDK próprio de jogos em **LuaJIT 5.1** (pacotes `.hyperx`) com três jogos
originais.

> Estado honesto de cada recurso — o que foi testado, o que compila mas ainda não rodou em
> hardware, e o que está pendente — está em **[docs/STATUS.md](docs/STATUS.md)**.

## Estrutura

| Caminho | Conteúdo |
|---|---|
| `core/` | Kotlin puro (JVM), testável sem Android: matemática, configurações, launcher, capacidades, formato `.hyperx`, permissões, glTF, pipeline temporal de mãos, Direct Touch, fusão Joy-Con, Runtime Broker (modelo), ambientes, latência |
| `app/` | App Android: UI 2D (Compose), engine XR (GLES 3), ARCore/Camera2, hand tracking (LiteRT), Joy-Con, áudio, web apps, launcher espacial, runtime de jogos |
| `app/src/main/cpp/` | JNI: ponte LuaJIT, OpenXR (loader Khronos), enumeração NNAPI |
| `app/src/main/assets/hxsdk/` | SDK Lua confiável: `bootstrap.lua` (sandbox + API `hx`), `physics.lua`, `ui.lua`, `vmath.lua` |
| `games/` | Fontes dos pacotes: STILLSHOT, FORGE LAB, PRIMAL RUSH, Relógio, Calculadora, Calendário |
| `native/luajit/` | LuaJIT 2.1 estático (arm64-v8a, x86_64) + cabeçalhos; reproduzível com `tools/build_luajit_android.sh` |
| `sdk/tests/` | Testes do SDK rodando o `bootstrap.lua` real no LuaJIT de desktop |
| `branding/icons-source/` | Folha original dos ícones do autor |
| `docs/` | Arquitetura, SDK, segurança, desempenho, hand tracking, ícones, testes, status |

## Compilar

Requisitos: JDK 17+, Android SDK (platform 36, build-tools 35), NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew :core:test                 # testes JVM do core
./gradlew :app:assembleDebug         # APK (empacota os jogos .hyperx automaticamente)
./gradlew :app:lintDebug
luajit sdk/tests/run_tests.lua       # testes do SDK Lua (LuaJIT 2.1 de desktop)
```

Testes da ponte JNI real no host: veja [docs/TESTING.md](docs/TESTING.md).

## Fluxo de uso

1. Abra o HyperXR: aparece a **interface 2D em paisagem** (somente configurações e gerenciamento).
2. Conceda a câmera, ajuste o que quiser e toque em **Iniciar experiência XR**.
3. O HyperXR verifica as capacidades: com ARCore inicia **realidade mista 6DoF**; sem ARCore mas com
   câmera, MR com imagem mono e 3DoF; caso contrário **VR SBS** com ambiente 3D. O motivo aparece na tela inicial.
4. Coloque o celular no VR Box. Para clicar: olhe para o botão por ~1 s (anel de progresso no
   retículo), toque na tela/botão do visor, pinçe com a mão apontada para o botão, toque o botão com
   o dedo (Direct Touch) ou use o R do Joy-Con.
5. Gestos: pinçar e segurar longe de botões abre o **menu radial** (Voltar, Início, Teclado,
   Captura); deslizar o polegar sobre o indicador **rola**; dois toques do polegar **voltam**;
   pinça longa num link do navegador **arrasta** o link para outra janela.
6. Em VR com ARCore Depth, a câmera aparece aos poucos ao se aproximar de obstáculos (**alerta de
   limite**; Configurações > Gráficos).

### Apps Android em janelas (Shizuku)

1. Instale e inicie o [Shizuku](https://shizuku.rikka.app/) (depuração sem fio ou root).
2. No XR, abra **Apps Android** e toque em *Conceder permissão*; escolha um app e se a janela é
   plana ou curva.
3. O app roda num display virtual criado pelo processo shell do Shizuku; toques, teclas e texto
   (somente ASCII) são injetados nesse display. Alguns apps bloqueiam displays secundários ou
   conteúdo protegido (DRM) e aparecem pretos — limitação do Android, não do HyperXR.

## Documentação

- [Arquitetura](docs/ARCHITECTURE.md)
- [SDK HyperXR e formato .hyperx](docs/SDK.md)
- [Modelo de segurança do SDK](docs/SECURITY.md)
- [Desempenho e metodologia de latência](docs/PERFORMANCE.md)
- [Hand tracking](docs/HAND_TRACKING.md)
- [Ícones](docs/ICONS.md)
- [Testes](docs/TESTING.md)
- [Status das funcionalidades](docs/STATUS.md)

## Licenças de terceiros

- LuaJIT — MIT (`native/luajit/COPYRIGHT`)
- Khronos OpenXR loader — Apache-2.0 (dependência Maven `org.khronos.openxr:openxr_loader_for_android`)
- ARCore SDK, MediaPipe Tasks Vision, AndroidX, Jetpack Compose — Apache-2.0
- Modelo MediaPipe Hand Landmarker — Apache-2.0 (`app/src/main/assets/models/README.md`)
- Ambientes Poly Haven — CC0 (licença registrada por ambiente baixado, com `ATTRIBUTION.txt`)
- Ícones dos apps — arte original do autor do HyperXR (não afiliada à Meta)
