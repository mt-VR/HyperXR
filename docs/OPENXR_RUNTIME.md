# HyperXR Runtime (runtime OpenXR próprio)

O HyperXR inclui um runtime OpenXR próprio, `libhyperxr_openxr.so` (código em
`native/openxr-runtime/`). Com ele, apps OpenXR para Android (OpenGL ES) rodam num celular dentro
de um VR Box, com a mesma calibração de IPD, FOV e lente configurada no HyperXR.

## Como funciona

```
App OpenXR ──► loader Khronos ──► (broker escolhe "HyperXR Runtime")
                                  └► dlopen libhyperxr_openxr.so  (no processo do app)
                                       ├─ xrNegotiateLoaderRuntimeInterface / xrGetInstanceProcAddr
                                       ├─ rastreamento 3DoF: game rotation vector + predição pelo giroscópio
                                       ├─ swapchains: texturas GLES num contexto compartilhado com o do app
                                       └─ compositor: superfície própria por cima da activity do app,
                                          lado a lado, pré-distorção da lente, timewarp rotacional
```

- **Sem IPC**: o runtime roda dentro do processo do app (como o modo "in-process" do Monado). Por
  isso é escrito sem a libc++ (não depende de `libc++_shared.so`) e exporta só dois símbolos.
- **Descoberta**: o APK do HyperXR declara o serviço `org.khronos.openxr.OpenXRRuntimeService` com
  `SoFilename = libhyperxr_openxr.so`. Instale o *OpenXR Runtime Broker* da Khronos e selecione
  "HyperXR Runtime" nele.
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
| Ações | criação, validação, bindings sugeridos, attach, sync, estados, háptico (sem efeito) |

## Limitações (reais)

- **Sem controles para os apps**: as ações são aceitas, mas nenhuma fonte de entrada fica ativa
  (`isActive = false`). O runtime roda dentro do app e não recebe os Joy-Cons nem as mãos.
- **Sem Vulkan**: apps só-Vulkan não rodam.
- **Sem 6DoF**: a posição vem de um modelo de pescoço.
- **Sem cubo e cilindro**: camadas de cubo, cilindro e equirect são recusadas com `XR_ERROR_LAYER_INVALID`.
- **Conformidade**: o runtime não passou pela suíte oficial de conformidade (CTS) da Khronos.

## Testes

- `native/openxr-runtime/tests/run_host_tests.sh`: compila o runtime para Linux (camada de
  plataforma headless) e executa 196 verificações com AddressSanitizer e UBSan. Os testes passam
  pela mesma negociação que o loader usa e cobrem instância, extensões, caminhos, sistema, estados
  da sessão, ordem do ciclo de quadros, espaços, visões (IPD), perda de rastreamento e ações.
- Teste no aparelho: Configurações > Runtime > *Testar o runtime HyperXR no visor*. Ele carrega o
  runtime como o loader faz, abre uma sessão GLES real por 15 s e mostra um relatório com os estados
  da sessão, o número de quadros e o intervalo médio entre eles. Durante o teste aparecem marcadores
  coloridos fixos no espaço e uma grade no chão.
