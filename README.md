# HyperXR

Plataforma XR para celulares Android em VR Box, headsets móveis e realidade mista: launcher espacial
(modos **Dock** e **New**), realidade mista com câmera, VR estereoscópico SBS, um **runtime OpenXR
próprio**, hand tracking com esqueleto (Kalman + One Euro), Joy-Cons, navegador GeckoView, apps
Android em janelas (Shizuku), o **Hand Physics Lab** (física de corpos rígidos numa bancada em
realidade mista) e um SDK próprio em **LuaJIT 5.1** (pacotes `.hyperx`).

> Estado honesto de cada recurso — o que foi testado, o que compila mas ainda não rodou em
> hardware, e o que está pendente — está em **[docs/STATUS.md](docs/STATUS.md)**.

## Estrutura

| Caminho | Conteúdo |
|---|---|
| `core/` | Kotlin puro (JVM), testável sem Android: matemática, configurações, launcher, capacidades, formato `.hyperx`, permissões, glTF, pipeline temporal de mãos, Direct Touch, fusão Joy-Con, Runtime Broker (modelo), ambientes, latência |
| `app/` | App Android: UI 2D (Compose), engine XR (GLES 3), ARCore/Camera2, hand tracking (MediaPipe), Joy-Con, áudio, web apps, launcher espacial, runtime de jogos |
| `app/src/main/cpp/` | JNI: ponte LuaJIT, OpenXR (loader Khronos), enumeração NNAPI |
| `app/src/main/assets/hxsdk/` | SDK Lua confiável: `bootstrap.lua` (sandbox + API `hx`), `physics.lua`, `ui.lua`, `vmath.lua` |
| `games/` | Fontes dos pacotes `.hyperx` embutidos: Relógio, Calculadora, Calendário |
| `native/luajit/` | LuaJIT 2.1 estático (arm64-v8a, x86_64) + cabeçalhos; reproduzível com `tools/build_luajit_android.sh` |
| `sdk/tests/` | Testes do SDK rodando o `bootstrap.lua` real no LuaJIT de desktop |
| `branding/icons-source/` | Folha original dos ícones do autor |
| `docs/` | Arquitetura, SDK, segurança, desempenho, hand tracking, ícones, testes, status |

## Compilar

Requisitos: JDK 17+, Android SDK (platform 36, build-tools 35), NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew :core:test                 # testes JVM do core
./gradlew :app:assembleDebug         # APK (empacota os pacotes .hyperx automaticamente)
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
5. Mãos: uma **bolinha** fica entre o polegar e o indicador (sem feixe) e se preenche conforme você
   pinça; **pinçar é tocar** (segurar e mover arrasta/rola a página). Joy-Con: o **analógico** move
   o cursor, **A** clica, **B** volta/fecha (segurar B fecha a janela), R/ZR + analógico rola.
6. Gestos: pinçar e segurar longe de botões abre o **menu radial** (Voltar, Início, Teclado,
   Captura); deslizar o polegar sobre o indicador **rola**; dois toques do polegar **voltam**;
   pinça longa num link do navegador **arrasta** o link para outra janela.
7. Em VR com ARCore Depth, a câmera aparece aos poucos ao se aproximar de obstáculos (**alerta de
   limite**; Configurações > Gráficos).

### Navegador

O navegador usa o **GeckoView** (motor do Firefox), o mesmo motor do [Wolvic](https://wolvic.com),
e, como o Wolvic, desenha a página direto na textura da janela. Tem abas, barra de endereço
(digite um endereço ou uma pesquisa), favoritos, histórico, modo desktop, downloads e tela de
cinema para vídeos em tela cheia. O GeckoView vem só para arm64 (cerca de 60 MB no APK); em
outras arquiteturas o HyperXR usa o WebView do sistema. Licença: MPL 2.0.

### Arquivos e Galeria

- **Arquivos**: gerenciador real do armazenamento (peça *Permitir acesso* uma vez: a tela de
  permissão aparece no celular). Copiar, recortar, colar, renomear, nova pasta, apagar (com
  confirmação), ordenar por nome/data/tamanho/tipo. Abre texto, fotos, PDF, vídeo e áudio dentro
  do XR; instala `.hyperx` e APKs.
- **Galeria**: fotos e vídeos do celular e as capturas do HyperXR. Fotos e vídeos **360** são
  detectados pelos metadados (Photo Sphere, Spherical Video) ou pela proporção 2:1 e abrem ao seu
  redor, inclusive 3D cima/baixo ou lado a lado. Uma foto 360 pode ser **salva como ambiente**.

### Jogos de Quest e apps OpenXR

Abra o APK em **Arquivos**. **Corrigir e instalar** prepara o jogo no próprio celular (sem PC):
targetSdk 29, recursos de headset opcionais e o loader OpenXR da Meta trocado pelo da Khronos;
depois assina com uma chave criada no Keystore do celular. Se o original já estiver instalado com
outra assinatura, o painel oferece desinstalá-lo (os dados do jogo são apagados). O HyperXR lê o APK antes de instalar e diz, sem rodeios, se ele pode
rodar no runtime HyperXR: motor (Unity/Unreal/Godot), loader OpenXR (o da Khronos funciona; o da
Meta não), VrApi (não roda), Meta Platform SDK (a checagem de licença pode fechar o jogo), Vulkan
ou OpenGL ES e extensões Meta citadas. Com o Shizuku ativo a instalação é direta e o OBB que estiver
ao lado do APK é copiado para `Android/obb`; sem ele, o instalador do Android pergunta na tela.

Para apps OpenXR acharem o runtime HyperXR instale também o APK pequeno **HyperXR OpenXR Broker**
(`broker/`); se ele não instalar por conflito, o celular já tem um broker OpenXR: escolha "HyperXR
Runtime" nele. Os
Joy-Cons/gamepad e o toque na tela viram os controles do jogo (perfil Touch); a mira segue o olhar.
Jogos feitos só para o Quest geralmente exigem recursos que um celular não tem, então espere
muitos "não vai funcionar" — o veredito existe para não desperdiçar seu tempo.

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
- [HyperXR Runtime (OpenXR próprio)](docs/OPENXR_RUNTIME.md)
- [Hand Physics Lab](docs/HAND_PHYSICS_LAB.md)
- [Ícones](docs/ICONS.md)
- [Testes](docs/TESTING.md)
- [Status das funcionalidades](docs/STATUS.md)
- [SteamVR no HyperXR](docs/STEAMVR.md) e [HyperXR PC](pc/README.md)

## Licenças de terceiros

- LuaJIT — MIT (`native/luajit/COPYRIGHT`)
- Cabeçalhos OpenXR (`native/openxr-runtime/include`) — Apache-2.0 OR MIT, Khronos Group
- Khronos OpenXR loader — Apache-2.0 (dependência Maven `org.khronos.openxr:openxr_loader_for_android`)
- ARCore SDK, MediaPipe Tasks Vision, AndroidX, Jetpack Compose — Apache-2.0
- GeckoView (Mozilla) — MPL-2.0 (dependência Maven `org.mozilla.geckoview:geckoview-arm64-v8a`)
- Shizuku API — Apache-2.0
- `openvr_driver.h` (Valve) — BSD-3-Clause (`pc/third_party/openvr/LICENSE`)
- LiteRT (TensorFlow Lite) — Apache-2.0
- MediaPipe Tasks GenAI — Apache-2.0; modelos Qwen 2.5 (baixados pelo usuário no app) — Apache-2.0
- Modelo MediaPipe Hand Landmarker — Apache-2.0 (`app/src/main/assets/models/README.md`)
- Ambientes Poly Haven — CC0 (licença registrada por ambiente baixado, com `ATTRIBUTION.txt`)
- Ícones dos apps — arte original do autor do HyperXR (não afiliada à Meta)
