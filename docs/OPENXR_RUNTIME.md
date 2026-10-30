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
  como broker instalável (o primeiro que o loader consulta) apontando para `libhyperxr_openxr.so`
  do app HyperXR. Ele é separado porque o Android recusa instalar um APK cujo provider já existe
  no aparelho ("conflito com pacote existente"); alguns celulares já declaram o broker do sistema,
  por isso o complementar usa o instalável. Ele só conflita com o app *OpenXR Runtime Broker* da
  Khronos: com esse instalado, escolha "HyperXR Runtime" nele. Configurações > Runtime mostra qual
  pacote ocupa cada broker (o HyperXR se anuncia pelo serviço `org.khronos.openxr.OpenXRRuntimeService`).
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
| Extensões | `XR_KHR_android_create_instance`, `XR_KHR_opengl_es_enable`, `XR_KHR_convert_timespec_time`, `XR_MND_headless`, `XR_EXT_local_floor`, `XR_KHR_android_thread_settings`, `XR_EXT_performance_settings` |
| Meta (Quest) | `XR_FB_display_refresh_rate` (a taxa da tela), `XR_FB_color_space` (Rec.709), `XR_FB_swapchain_update_state` (+ `_opengl_es`), `XR_FB_foveation` e `_configuration` (aceitas; a imagem inteira é sempre usada), `XR_FB_composition_layer_settings` e `_image_layout`, `XR_FB_touch_controller_pro`, `XR_META_touch_controller_plus` |
| Pico | `XR_BD_controller_interaction`: perfis Pico 4, Neo 3 e G3, atendidos como os Touch; `XR_BD_spatial_sensing`, `_anchor`, `_scene`, `_mesh` e `_plane` (provedores de âncoras, sala, planos e malha sobre a mesma sala do HyperGuardian; âncoras gravadas como as da Meta) com `XR_EXT_future` e `XR_BD_future_progress` |
| Âncoras e sala | `XR_FB_spatial_entity` (+ `_query`, `_storage`, `_container`), `XR_FB_scene` e `XR_FB_scene_capture`: âncoras com pose fixa, gravadas nos arquivos do próprio jogo; a sala (chão, teto, paredes, layout) a partir da área do HyperGuardian |
| Modelos e camadas Meta | `XR_FB_render_model` (modelo de controle do próprio HyperMagic, glTF 2.0 gerado pelo runtime), `XR_FB_composition_layer_alpha_blend` (fatores de mistura por camada), `XR_META_local_dimming` (aceito, sem efeito num celular) e `XR_META_headset_id` (zero: não é um óculos da Meta) |
| Mãos como controle | `XR_EXT_hand_interaction` (pinça, ativar mira, agarrar, e as poses mira/empunhadura/pinça/toque das mãos rastreadas) junto com o perfil de controle (`XR_META_simultaneous_hands_and_controllers`) |
| Entrada e vibração Meta | `XR_FB_touch_controller_proximity` (dedo perto do gatilho / dos botões), `XR_FB_haptic_amplitude_envelope` e `XR_FB_haptic_pcm`: as vibrações do jogo vão ao HyperMagic, que vibra o Joy-Con / gamepad daquela mão |
| Extras Meta | `XR_META_performance_metrics` (tempo de CPU do app, do compositor, quadros atrasados), `XR_META_recommended_layer_resolution`, `XR_FB_composition_layer_secure_content`, `XR_META_automatic_layer_filter`, `XR_FB_keyboard_tracking` (nenhum teclado rastreado), `XR_META_passthrough_preferences`, `XR_META_passthrough_layer_resumed_event`, `XR_FB_spatial_entity_storage_batch`, `XR_OCULUS_android_session_state_enable` |
| Corpo | `XR_FB_body_tracking` (70 juntas, com esqueleto de referência), `XR_META_body_tracking_full_body` (84, com pernas) e `XR_BD_body_tracking` (Pico, 16 ou 24): cabeça e mãos vistas, o resto inferido (tronco abaixo da cabeça, braços por IK até as mãos, pernas em pé ou sentado) |
| Passthrough | `XR_FB_passthrough` (camada de reconstrução, iniciar/pausar, opacidade) e o modo `ALPHA_BLEND`: a câmera do celular atrás do jogo, com a projeção misturada pelo alfa |
| Mãos | `XR_EXT_hand_tracking` (26 juntas, com raio) `XR_FB_hand_tracking_aim` (mira e pinça da Meta), `XR_FB_hand_tracking_mesh` (malha da mão para o SDK da Meta desenhar), `XR_FB_hand_tracking_capsules` (19 cápsulas para física) e a escala da mão (`XrHandTrackingScaleFB`), com as mãos que o HyperMagic rastreia pela câmera (veja abaixo) |
| Sistema | HMD, estéreo primário, blend OPAQUE e ALPHA_BLEND, orientação rastreada (3DoF), posição por modelo de pescoço |
| Espaços | VIEW, LOCAL (altura dos olhos), STAGE e LOCAL_FLOOR (chão em y = 0), espaços de ação |
| Swapchains | `GL_SRGB8_ALPHA8`, `GL_RGBA8`, profundidade 16/24/24+8/32F, texturas array, imagem estática |
| Camadas | projeção (com timewarp), quad, cilindro (`XR_KHR_composition_layer_cylinder`) e equirect (`XR_KHR_composition_layer_equirect2`, 180/360, raio finito ou infinito); `eyeVisibility` por olho; até 16 camadas |
| Ciclo de quadros | wait/begin/end com ritmo da tela, `XR_FRAME_DISCARDED`, estados da sessão completos |
| Ações | perfis Touch (Oculus/Meta), Pico 4/Neo 3/G3, simple e mãos (`hand_interaction_ext`); gatilho, aperto, analógicos, A/B/X/Y, menu; poses de mira/empunhadura; fontes ligadas; vibração no controle daquela mão |

## Limitações (reais)

- **Controles sem rastreamento**: botões, gatilhos e analógicos funcionam; a pose do controle segue a
  cabeça.
- **Mãos**: chegam pelo `XR_EXT_hand_tracking`. Ao abrir um jogo OpenXR, o HyperMagic inicia um serviço
  em primeiro plano (`HandBridgeService`, notificação "mãos no jogo") que liga a câmera traseira só
  enquanto o jogo tem um rastreador de mão e publica as mãos ~90 vezes por segundo num arquivo
  compartilhado (`hx_hands.h`), que o runtime mapeia pelo provider (`content://com.hypermagic.runtime/hands`).
  Os 21 pontos viram as 26 juntas do OpenXR (palma e metacarpos estimados ao longo da palma; cada
  junta orientada pelo osso e pelo dorso da mão); a câmera é tratada como 8 cm à frente dos olhos.
  A malha é gerada pelo runtime (uma cápsula por osso, presa à junta onde o osso começa), não é
  a malha de mão da Meta: parece uma mão de bonecos de cápsulas, com emendas visíveis nas juntas.
  Sem velocidades das juntas; a orientação do polegar é aproximada. Desligue em Ajustes > Mãos ("Mãos nos jogos OpenXR").
- **Corpo**: só a cabeça e as mãos (quando a câmera as vê) são medidas; tronco, braços sem mão vista e
  pernas são inferidos, como as pernas do próprio corpo da Meta. Agachar, inclinar o tronco ou cruzar as
  pernas não aparece. A orientação das juntas dos membros segue o osso (-Z ao longo dele).
- **Passthrough**: o serviço de mãos do HyperMagic também manda quadros RGBA de até 640x480 a ~30 fps
  (`hx_camera.h`, `content://com.hypermagic.runtime/camera`) enquanto o jogo mostra a câmera; só o jogo
  que o HyperMagic abriu (e o próprio HyperMagic) pode abrir esses arquivos. A imagem é tratada como
  infinitamente longe (só rotação, sem profundidade: objetos perto ficam deslocados), é a mesma nos
  dois olhos, fica sempre atrás de todas as camadas e tem bordas pretas onde a câmera não alcança o
  campo de visão. Camadas projetadas em malhas (`XR_PASSTHROUGH_LAYER_PURPOSE_PROJECTED_FB`,
  geometry instances), mapas de cor e bordas não existem.
- **Âncoras e sala**: o celular não rastreia posição, então uma âncora fica onde foi criada no espaço do
  jogo (não "gruda" num objeto real se você andar). A sala é a área desenhada no HyperGuardian
  (centralizada onde você está ao abrir o jogo; o giro dela em relação ao jogo não é conhecido) ou, sem
  área, uma sala de 4 x 4 m e 2,6 m de altura; o mesmo contorno dá sempre os mesmos UUIDs. Não há móveis
  (caixas 3D), malha da sala, compartilhamento nem nuvem. "Escanear a sala" (`xrRequestSceneCaptureFB`)
  só relê a área do HyperMagic.
- **Extensões Meta e Pico**: as listadas acima existem; as outras (teclado rastreado, teclado virtual, métricas de desempenho, `XR_PICO_*` e mais) não. Jogos que as exigem, que usam a VrApi ou só Vulkan não rodam; o loader da
  Meta ou da Pico é trocado pelo da Khronos ao corrigir o APK, e o importador em Arquivos diz o que o
  jogo usa antes de instalar. O SDK nativo da Pico (`libpxr_api.so`) e as lojas Meta/Pico (verificação
  de compra) dependem de serviços do óculos que não existem num celular.
- **Sem Vulkan**: apps só-Vulkan não rodam.
- **Sem 6DoF**: a posição vem de um modelo de pescoço.
- **Sem cubo**: camadas de cubo e o equirect antigo (v1) são recusados com `XR_ERROR_LAYER_INVALID`.
  Cilindro e equirect2 são desenhados por raio a partir do olho (sem mipmaps: texturas grandes vistas
  de longe podem serrilhar). A camada de projeção é sempre desenhada primeiro, antes das outras.
- **Conformidade**: o runtime não passou pela suíte oficial de conformidade (CTS) da Khronos.

## Testes

- `native/openxr-runtime/tests/run_host_tests.sh`: compila o runtime para Linux (camada de
  plataforma headless) e executa 941 verificações com AddressSanitizer e UBSan. Os testes passam
  pela mesma negociação que o loader usa e cobrem instância, extensões, caminhos, sistema, estados
  da sessão, ordem do ciclo de quadros, espaços, visões (IPD), perda de rastreamento, ações e entrada (gamepad/toque chegando às ações, mudanças entre syncs,
  pose de mira), extensões Meta/Pico e mãos (juntas no espaço certo, eixos do OpenXR, mira e
  pinça da Meta, mãos velhas descartadas, mão esquerda espelhada) camadas (geometria do cilindro e do equirect, validação) passthrough (objetos, estados, ALPHA_BLEND) e malha da mão (faces para fora, índices, pele, cápsulas, escala) e âncoras/sala (criar, localizar, ligar e desligar componentes, gravar e apagar entre sessões, consultas por UUID e por componente, chão/paredes/teto e suas orientações) modelo do controle (arquivo .glb válido, faces para fora) mãos como controle, vibração, proximidade, métricas e o sensoriamento espacial da Pico (futures, provedores, filtros, componentes, âncoras entre sessões) e corpo (proporções, braço alcançando a mão, sentado, esqueleto sem ciclos, Meta 70/84 e Pico 16/24).
- Teste no aparelho: Configurações > Runtime > *Testar o runtime HyperXR no visor*. Ele carrega o
  runtime como o loader faz, abre uma sessão GLES real por 15 s e mostra um relatório com os estados
  da sessão, o número de quadros e o intervalo médio entre eles. Durante o teste aparecem marcadores
  coloridos fixos no espaço e uma grade no chão.
