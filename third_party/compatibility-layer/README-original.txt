Compatibility-Layer — GearVR / Quest / Runtime
==============================================

Projeto enxuto com apenas as funções de GearVR, Quest e runtime de compatibilidade
(ex-OpenXR), pronto para portar para outra plataforma. Nenhuma referência a marcas
próprias: todos os nomes foram generalizados para "Compatibility-Layer".

Estrutura
---------

  compatibility-layer-runtime/   Runtime de VR: Monado com o patch do projeto, broker de
                                 sistema, GameLauncher e o script que monta o APK do runtime.
    broker/                      Broker de runtime embutido (RuntimeBroker, GameLauncher,
                                 VisibilityProvider) — jogos com loader Khronos encontram o
                                 runtime sozinhos, sem patch.
                                 Pacote Java: org.freedesktop.monado.compatibility_layer
    loaders/                     Loaders pré-compilados que são injetados nos jogos:
                                 libcompatibility_layer_loader.so (arm64) e ...32.so (armv7).
    prebuilt/                    compatibility-layer-runtime.apk — runtime pré-compilado e
                                 assinado.
    res/                         Ícones usados ao renomear o APK do Monado.
    signing/                     compatibility-layer-signing.p12 — chave de assinatura
                                 (senha: android).
    monado-compatibility-layer.patch   Patch do Monado. Clone o Monado no commit indicado
                                 em MONADO_COMMIT dentro de compatibility-layer-runtime/monado/
                                 e aplique-o por cima.
    build_runtime_apk.py         Monta o "Compatibility-Layer Runtime" a partir de um APK
                                 do Monado.

  gearvr-shim/                   Adaptador GearVR: libvrapi.so própria com os mesmos 104
                                 exports do VrApi 1.50, desenhando via compatibility-layer.
                                 Jogos Gear VR rodam em qualquer celular com o runtime.
    prebuilt/                    libvrapi.so / libvrapi32.so pré-compiladas.
    src/                         vrapi_system / vrapi_frame / vrapi_swapchain / vrapi_input
                                 (frente VrApi) + xr_backend (frente compatibility-layer).

  vrapi-driver/                  Lado Quest: pacote com.oculus.systemdriver que o carregador
                                 libvrapi.so dos jogos Gear VR / Quest abre sozinho. O driver
                                 carrega o VrApi do projeto e o jogo roda sem patch.
                                 Pacote Java: dev.compatibility_layer.vrapidriver
    build_driver_apk.py          Monta o compatibility-layer-vrapi-driver.apk.

  sdk/                           Kit de portabilidade: compatibility_layer_port.py prepara
                                 APKs de jogos (manifest, loader, assinatura), biblioteca
                                 CompatibilityLayerInput (Kotlin, pacote
                                 com.compatibility_layer.sdk) e o cabeçalho C
                                 compatibility_layer_input.h; docs/ tem protocolo, porting,
                                 engines, manifest e prompts de IA.

Ferramentas
-----------

  python3 compatibility-layer-runtime/build_runtime_apk.py   # monta o runtime
  cmake -S gearvr-shim -B gearvr-shim/build && cmake --build gearvr-shim/build
  python3 vrapi-driver/build_driver_apk.py                   # monta o driver Quest/VrApi
  python3 sdk/tools/compatibility_layer_port.py jogo.apk     # prepara um jogo
  ./gradlew :sdk:build                                       # biblioteca e testes do SDK

Nomes por contexto
------------------

Onde um identificador não aceita hífen (Java, Kotlin, Python, C, CMake), usa-se o
separador correto:

  textos / caminhos / nomes de arquivo   compatibility-layer
  identificadores (funcoes, pacotes)     compatibility_layer
  classes / tipos                        CompatibilityLayer
  constantes / macros                    COMPATIBILITY_LAYER

Atenção ao portar
-----------------

* Alguns identificadores são contratos com o sistema Android/Khronos original e foram
  renomeados junto (permissões org.khronos.compatibility_layer.*, nome do loader
  libcompatibility_layer_loader.so dentro dos APKs, autoridades do broker, categoria
  IMMERSIVE_HMD, alvo compatibility_layer_android do Monado). Na plataforma original
  eles precisam dos valores literais antigos; na sua plataforma nova, troque pelos
  equivalentes dela.
* O pacote com.oculus.systemdriver (vrapi-driver) foi mantido de propósito: é o nome
  que o carregador VrApi dentro dos jogos abre — mudá-lo quebra o lado Quest.
* Os binários pré-compilados (loaders em compatibility-layer-runtime/loaders, libvrapi
  em gearvr-shim/prebuilt, prebuilt/compatibility-layer-runtime.apk) não foram
  recompilados e podem ter strings antigas internas. Recompile a partir das fontes
  (Monado + patch, gearvr-shim) para uma árvore 100% limpa.
* O caminho compatibility-layer-runtime/monado/ é onde o clone do Monado deve ficar
  (commit em MONADO_COMMIT) para o patch e o CMake do gearvr-shim acharem os headers
  (compatibility_layer_includes).
