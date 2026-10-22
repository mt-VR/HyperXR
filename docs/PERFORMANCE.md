# Desempenho e latência

## Meta

A meta aspiracional é ~1 ms **por componente** do pipeline quando tecnicamente possível. O HyperXR
**não** anuncia latência total de ponta a ponta sem medição no aparelho — e até agora nenhuma medição
em hardware foi feita (ver STATUS.md). Os números abaixo serão preenchidos a partir da tela
Diagnóstico de aparelhos reais.

## Estágios medidos separadamente (`Diagnostics.Stage`)

| Estágio | Onde é medido | Observação |
|---|---|---|
| camera capture->app | `SurfaceTexture.timestamp` / `Frame.timestamp` até o latch no GL | só quando a câmera usa o relógio REALTIME (indicado em Dispositivo) |
| hand inference | detector + landmarks no thread `hx-hands` | backend real em uso aparece em Hand tracking |
| hand pose estimation | fusão de profundidade + filtro + gestos | |
| sensor fusion | callback do vetor de rotação / atualização ARCore | |
| simulation update | `XrContent.onFrame` | inclui o quadro Lua em jogos |
| lua frame | `LuaVm.frame` | |
| render cpu | submissão GL dos dois olhos + composição | tempo de CPU, não de GPU |
| frame submit | reservado para `eglSwapBuffers` / `xrEndFrame` | |
| frame interval | intervalo entre quadros | mostra jitter de frame pacing |
| input->frame submit | início do quadro até o fim da submissão | não inclui o scan-out da tela |

Cada estágio guarda 512 amostras e reporta média, p50, p95, p99, mínimo, máximo e desvio padrão.

## Como medir

1. Aparelho em temperatura ambiente, brilho fixo, modo avião opcional, carregador desligado.
2. Iniciar XR, aguardar 30 s, usar normalmente por 2 min, voltar à tela Diagnóstico (processo
   principal) — para jogos, os números ficam no processo `:game` (exportação é item pendente).
3. Registrar aparelho, SoC, Android, modo (MR/VR), backend de mãos e taxa de inferência.

## Otimizações implementadas

- Buffers diretos compartilhados com o LuaJIT; chamadas de host numéricas não alocam.
- Física e UI em LuaJIT (sem JNI por entidade); painéis só re-renderizam quando mudam.
- Pools de `DrawItem`/`PanelItem`; culling por esfera envolvente por olho.
- Hand tracking: MediaPipe na GPU, só o quadro mais novo (descarta em vez de enfileirar), ROI interna
  do MediaPipe, taxa configurável, YUV→RGB em buffers reutilizados a ≤ 480 px.
- Câmera: faixa de FPS que alcança 60 com o menor limite inferior possível, estabilização desligada;
  passthrough em tela cheia desenhado como um único triângulo por olho, sem teste de profundidade.
- Tela na maior taxa de atualização disponível na resolução atual.
- `glInvalidateFramebuffer` do depth de cada olho (GPUs por tiles não gravam o depth na memória).
- Sem alocações por quadro no renderer de ambiente, câmera e launcher (listas e matrizes reutilizadas).
- Predição de rotação por giroscópio; predição de mãos pela velocidade do filtro One Euro.
- Modo de desempenho sustentado; escala de resolução por olho; texturas glTF limitadas a 2048 px.

Pendentes: resolução dinâmica guiada pelo tempo de GPU, timer queries de GPU, compressão ETC2/ASTC,
LOD, carregamento assíncrono de glTF fora do thread GL, reação ao `PowerManager` thermal status.
