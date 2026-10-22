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

## Latência da cabeça (giroscópio e 6DoF)

- **Giroscópio na taxa máxima:** o mais rápido que o sensor permitir, sem agrupar amostras, numa
  thread de prioridade alta. Ele é integrado num histórico de meio segundo (`GyroHistory`, com
  testes).
- **6DoF:**
  - A pose do ARCore tem a idade do quadro da câmera. A rotação medida pelo giroscópio desde o
    instante daquele quadro é somada a ela.
  - Para os eixos do giroscópio baterem com os da câmera em qualquer celular, os 4 alinhamentos
    possíveis são comparados com a rotação do próprio ARCore nos primeiros segundos, e fica o que
    acerta.
  - A posição é levada adiante pela velocidade recente da cabeça, suavizada e limitada.
- **3DoF:** rotation vector mais a rotação medida pelo giroscópio desde aquela amostra.
- **Previsão:** até o momento em que a luz sai da tela, cerca de dois períodos da tela. O ajuste
  "previsão" do Head tracking corrige a partir disso (16 ms = sem mudança).
- **Releitura antes de desenhar:** a cabeça é lida de novo logo antes de desenhar os olhos, então
  o trabalho do conteúdo (interface, física, mãos) não entra na latência.
- **Sincronia com a GPU:** um fence faz a CPU esperar a GPU terminar o quadro anterior, e nenhum
  quadro fica na fila atrás de outro.

## Giroscópio computacional (celulares sem giroscópio)

Em Head tracking > Fonte, a opção **Giroscópio computacional (câmera)** liga isso. No modo
Automático ela liga sozinha quando o celular não tem giroscópio. Um celular sem giroscópio
também não tem ARCore, então o rastreamento fica em 3DoF.

`VisualGyro` (core, com testes) faz um SLAM rotacional:

- **Quadro a quadro:**
  - Pega o melhor canto de cada célula de uma grade 8x6 (Shi-Tomasi) numa imagem cinza de 160
    px.
  - Segue cada canto por casamento de patches com média removida, em 2 níveis de pirâmide, com
    subpixel. A busca parte de onde a última velocidade angular diz que o ponto foi parar.
  - Calcula a rotação pelos vetores de direção: RANSAC de 2 pontos e depois o método de Horn nos
    inliers. Mãos e pessoas em movimento ficam de fora como outliers.
- **Mapa:** guarda quadros-chave a cada 20° de vista, cada um com a sua orientação. Ao olhar de
  novo para perto de um deles (a cada 3 quadros), o casamento com ele dá a orientação absoluta e
  puxa o desvio acumulado de volta.
- **Gravidade:** o acelerômetro, só quando |a| está perto de 1 g, corrige pitch e roll aos poucos.
- **Entre quadros da câmera:** a orientação é levada adiante pela velocidade angular medida, até
  o momento em que a imagem chega aos olhos.

Medido em imagens sintéticas de rotação conhecida (`VisualGyroTest`):

| Caso | Erro |
|---|---|
| Virada de 62,7° a 75°/s com pitch | pior erro 0,24° |
| 180°/s | pior erro 0,25° |
| Cabeça parada com uma "mão" atravessando a imagem | 0,08° |
| Olhar 40° para o lado e voltar | volta a menos de 1° |

Limites:

- precisa de luz e de um ambiente com detalhes (parede lisa ou escuro não dão pontos);
- a taxa é a da câmera (30 a 60 Hz), com previsão entre os quadros;
- o desvio de yaw só é corrigido quando o usuário olha de novo para uma direção já vista.
