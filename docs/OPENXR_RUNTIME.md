# HyperXR Runtime (runtime OpenXR próprio)

O HyperXR inclui um runtime OpenXR próprio, `libhyperxr_openxr.so` (código em
`native/openxr-runtime/`). Com ele, apps OpenXR para Android (OpenGL ES) rodam num celular dentro
de um VR Box, com a mesma calibração de IPD, FOV e lente configurada no HyperXR.

## Como funciona

```
App OpenXR ──► loader Khronos ──► broker (Khronos, se instalado; senão o do HyperXR)
                                  └► dlopen libhyperxr_openxr.so  (no processo do app)
                                       ├─ xrNegotiateLoaderRuntimeInterface / xrGetInstanceProcAddr
                                       ├─ rastreamento 3DoF: game rotation vector + predição pelo giroscópio
                                       ├─ swapchains: texturas GLES num contexto compartilhado com o do app
                                       └─ compositor: superfície própria por cima da activity do app,
                                          lado a lado, pré-distorção da lente, timewarp rotacional
```

- **Sem IPC**: o runtime roda dentro do processo do app (como o modo "in-process" do Monado). Por
  isso é escrito sem a libc++ (não depende de `libc++_shared.so`) e exporta só dois símbolos.
- **Descoberta**: o loader da Khronos pergunta primeiro ao broker instalável
  (`org.khronos.openxr.runtime_broker`, app *OpenXR Runtime Broker*) e depois ao broker do sistema
  (`org.khronos.openxr.system_runtime_broker`). Celulares não têm nenhum dos dois, então o HyperXR
  traz um APK complementar e opcional, **HyperXR OpenXR Broker** (módulo `broker/`), que responde
  como broker do sistema apontando para `libhyperxr_openxr.so` do app HyperXR. Ele é separado porque
  o Android recusa instalar um APK cujo provider já existe no aparelho ("conflito com pacote
  existente"): se o broker complementar não instalar, o aparelho já tem um broker — escolha
  "HyperXR Runtime" nele (o HyperXR se anuncia pelo serviço `org.khronos.openxr.OpenXRRuntimeService`).
- **Controles**: no OpenXR os controles pertencem ao runtime. A janela do runtime registra as teclas
  e eixos de gamepad/Joy-Con e o toque na tela (o botão do VR Box) e os entrega às ações do app pelos
  perfis `oculus/touch_controller` (e variantes Meta) ou `khr/simple_controller`, o que o app tiver
  sugerido. Em activities comuns (Unity, GameActivity) o callback da janela é embrulhado e o app
  continua recebendo os eventos; numa NativeActivity a janela do runtime recebe foco e toques.
  As poses de mira/empunhadura acompanham a cabeça (mira pelo olhar), sem rastreamento de controle.
- **Calibração**: o runtime lê IPD, FOV e coeficientes da lente do provider
  `content://com.hyperxr.runtime/viewer` (só esses números). Se o app não enxergar o HyperXR, usa
  valores padrão.
- **Compositor**: no `xrEndFrame`, o runtime espera (na GPU) pelos fences das imagens liberadas e
  desenha os dois olhos. Para cada pixel ele calcula a direção na lente (k1, k2), corrige pela
  rotação da cabeça desde que o app renderizou (timewarp) e reprojeta no frustum do app. A
  superfície fica numa janela própria que não recebe foco nem toques, então o app continua
  recebendo a entrada.

## O que é suportado

| Área | Suporte |
|---|---|
| API | OpenXR 1.0 e 1.1 (`xrLocateSpaces`, LOCAL_FLOOR) |
| Extensões | `XR_KHR_android_create_instance`, `XR_KHR_opengl_es_enable`, `XR_KHR_convert_timespec_time`, `XR_MND_headless`, `XR_EXT_local_floor` |
| Sistema | HMD, estéreo primário, blend OPAQUE, orientação rastreada (3DoF), posição por modelo de pescoço |
| Espaços | VIEW, LOCAL (altura dos olhos), STAGE e LOCAL_FLOOR (chão em y = 0), espaços de ação |
| Swapchains | `GL_SRGB8_ALPHA8`, `GL_RGBA8`, profundidade 16/24/24+8/32F, texturas array, imagem estática |
| Camadas | projeção (com timewarp) e quad; até 16 camadas |
| Ciclo de quadros | wait/begin/end com ritmo da tela, `XR_FRAME_DISCARDED`, estados da sessão completos |
| Ações | perfis Touch (Oculus/Meta) e simple; gatilho, aperto, analógicos, A/B/X/Y, menu; poses de mira/empunhadura; fontes ligadas; háptico (sem efeito) |

## Limitações (reais)

- **Controles sem rastreamento**: botões, gatilhos e analógicos funcionam; a pose do controle segue a
  cabeça. Mãos (hand tracking) não chegam aos apps OpenXR.
- **Extensões Meta**: `XR_FB_*`/`XR_META_*` não existem aqui. Jogos de Quest que as exigem, que usam a
  VrApi, o loader da Meta ou só Vulkan não rodam (o importador em Arquivos avisa antes de instalar).
- **Sem Vulkan**: apps só-Vulkan não rodam.
- **Sem 6DoF**: a posição vem de um modelo de pescoço.
- **Sem cubo e cilindro**: camadas de cubo, cilindro e equirect são recusadas com `XR_ERROR_LAYER_INVALID`.
- **Conformidade**: o runtime não passou pela suíte oficial de conformidade (CTS) da Khronos.

## Testes

- `native/openxr-runtime/tests/run_host_tests.sh`: compila o runtime para Linux (camada de
  plataforma headless) e executa 260 verificações com AddressSanitizer e UBSan. Os testes passam
  pela mesma negociação que o loader usa e cobrem instância, extensões, caminhos, sistema, estados
  da sessão, ordem do ciclo de quadros, espaços, visões (IPD), perda de rastreamento, ações e entrada (gamepad/toque chegando às ações, mudanças entre syncs,
  pose de mira).
- Teste no aparelho: Configurações > Runtime > *Testar o runtime HyperXR no visor*. Ele carrega o
  runtime como o loader faz, abre uma sessão GLES real por 15 s e mostra um relatório com os estados
  da sessão, o número de quadros e o intervalo médio entre eles. Durante o teste aparecem marcadores
  coloridos fixos no espaço e uma grade no chão.
