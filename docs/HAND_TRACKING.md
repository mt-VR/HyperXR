# Hand tracking

```
câmera (ARCore CPU image ou Camera2 YUV 640x480; também em VR, só para as mãos)
 → 3 slots em pipeline: enquanto a GPU infere o quadro N, a CPU converte o N+1 (YUV→RGB em
   código nativo -O3 direto nos pixels do Bitmap, `libhyperxr_image.so`; em Kotlin com 2 threads
   se a biblioteca faltar; buffers e observações reutilizados) e a próxima inferência começa
   assim que a anterior termina
 → MediaPipe Hand Landmarker, LIVE_STREAM, delegate GPU (CPU como fallback, informado)
   · detector de palma só quando a mão some; depois rastreamento por ROI interno do MediaPipe
   · só o quadro mais novo é processado; quadros que chegam durante a inferência são descartados
 → DepthFusion: profundidade da palma pelo ARCore Depth ou pelo tamanho da mão
 → HandTracker: plausibilidade anatômica →
     esqueleto (comprimentos de osso do usuário; cada osso mantém a direção observada) →
     Kalman de velocidade constante por junta (peso pela confiança da profundidade) →
     One Euro (tremor residual) → esqueleto de novo → predição pela velocidade do Kalman;
     perda/recuperação sem salto
 → gestos (pinça proporcional à palma com histerese, apontar, punho, mão aberta, microgestos)
 → raio de mão estabilizado + pinça, menu radial, Direct Touch, aura, API hx.hands
```

## Modelo

`app/src/main/assets/models/hand_landmarker.task` — MediaPipe Hand Landmarker float16, licença
Apache-2.0 (origem e model card em `assets/models/README.md`). Armazenado sem compressão no APK
porque o MediaPipe mapeia o arquivo diretamente. Nada precisa ser importado.

O MediaPipe rotula a lateralidade supondo imagem espelhada (câmera frontal); como a câmera traseira
não é espelhada, o HyperXR inverte o rótulo.

## GPU

O padrão é a GPU (`InferenceBackend.GPU`). Se o delegate GPU não puder ser criado, o serviço cai
para CPU e mostra o motivo em Configurações > Hand tracking. O botão de benchmark mede GPU e CPU no
aparelho (quadro sintético sem mão: mede o detector de palma).

## Profundidade e 6DoF

- **ARCore Depth**: mediana da profundidade nos 5 pontos da palma; a imagem de profundidade é
  copiada no envio (a inferência é assíncrona).
- **Sem Depth**: distância pelo comprimento 3D da palma em pixels contra o tamanho da mão **do
  usuário**, marcada `HAND_SIZE_PRIOR` com confiança reduzida. O tamanho vem, nesta ordem:
  1. da calibração automática (`HandScaleEstimator`): com ARCore Depth, 90 amostras de mão aberta
     medem o comprimento real da palma (média aparada) e o valor é salvo;
  2. do comprimento da mão informado em Configurações > Hand tracking (punho → ponta do médio),
     convertido em palma × 0,56.
- **Renderização em MR**: cada olho usa exatamente o campo de visão com que a imagem da câmera é
  mostrada, e por padrão os dois olhos usam o ponto de vista da câmera (IPD 0), então mãos e
  painéis ficam no tamanho e na posição reais sobre a imagem.
- Posição 6DoF da mão no mundo só com pose de câmera conhecida (ARCore); em 3DoF as mãos ficam
  relativas à cabeça.

## Interação

- **Raio de mão + pinça**: raio de um ombro estimado passando pela mão; pinçar clica. É o modo mais
  confiável à distância, porque não depende de profundidade exata.
- **Direct Touch**: tocar o painel com a ponta do indicador (limiares alargados quando a
  profundidade é estimada).
- **Aura preta**: campo de distância de cápsulas unido por blending MAX em meia resolução e
  composto como borda escura + interior translúcido; some com Joy-Cons conectados.

- **Pinça**: medida nos *world landmarks* do MediaPipe (um modelo 3D métrico e consistente da mão),
  como distância polegar–indicador dividida pelo comprimento da palma: fecha abaixo de 38 % e abre
  acima de 55 %. Antes era medida nas juntas reconstruídas em espaço de câmera, cuja profundidade
  por junta é ruidosa: a pinça raramente fechava. O teste de detecção mostra a porcentagem ao vivo.
- **Raio estável**: ombro estimado → articulação da base do indicador (que quase não se move ao
  pinçar), filtrado por One Euro na origem e na direção; se o rastreamento falha por até 350 ms o
  raio fica parado em vez de pular.
- **Sem feixe**: a bolinha de pinça marca a mão; o alvo é indicado só pelo cursor na superfície.
- **Menu radial**: pinçar e segurar (~0,45 s) longe de botões e janelas abre um anel em volta da mão
  com Voltar, Início, Teclado e Captura; mova a mão na direção do item e solte para confirmar
  (soltar no centro cancela).
- **Microgestos** (sem apontar o braço; a mão pode ficar apoiada):
  - deslizar a ponta do polegar ao longo do indicador rola a janela olhada (ou a apontada, ou a mais
    recente): em direção à ponta do dedo desce a página;
  - dois toques rápidos do polegar na lateral do indicador = Voltar.
  O contato é medido como distância do polegar ao segmento base→ponta do indicador relativa à
  palma; a ponta do dedo (zona da pinça) é ignorada para não confundir com clique.
  Precisão real depende do MediaPipe enxergar o polegar; não foi medida em aparelho.

## Estabilidade (correções de "a mão não é detectada / some")

- **Quadros em resolução cheia** (640x480): antes eram reduzidos para 320x240, e uma mão a um braço
  de distância ficava pequena demais para os landmarks.
- **Limiares do MediaPipe** de presença/rastreamento em 0,35: o MediaPipe continua seguindo a mão
  (barato) em vez de descartá-la a cada borrão e rodar o detector de palma de novo.
- **Falhas curtas não escondem a mão**: perder a mão por alguns quadros mantém posição e gesto
  (fase de "coasting", velocidade decaindo em ~60 ms); só depois de 450 ms ela é dada como perdida.
- **Mão atribuída por posição, não pelo rótulo**: o rótulo esquerda/direita do MediaPipe troca
  de um quadro para outro e às vezes rotula as duas mãos iguais; isso fazia a mão pular de
  rastreador e sumir. Agora cada detecção vai para a mão rastreada mais próxima (`HandAssignment`).
- **Profundidade do ARCore conferida**: amostras que atingem a parede atrás da mão (incompatíveis
  com o tamanho da mão na imagem) são descartadas, em vez de jogar a mão a metros e rejeitá-la.
- **Watchdog**: se um resultado de inferência nunca chega, a fila é liberada após 600 ms.
- **Teste fora do visor**: Configurações > Hand tracking > *Testar detecção* mostra o quadro
  analisado, o esqueleto, o estado de cada mão e a taxa de quadros com mão.

## Motor NPU (HyperXR) e verificador de mão

Em **Hand tracking > Motor** há três opções:

- **NPU (HyperXR):** rastreador próprio.
- **GPU (MediaPipe):** o padrão.
- **CPU (MediaPipe).**

### Motor NPU (`NpuHandEngine`)

- **Modelos:** os mesmos modelos abertos de palma e de landmarks do MediaPipe (Apache-2.0, já
  dentro do APK). Rodam com o LiteRT, que entrega o grafo inteiro à NPU pelo NNAPI.
- **Escolha do acelerador:** pelo nome, entre os que o NNAPI do celular lista (Samsung Eden,
  MediaTek APU/MDLA, Qualcomm HTA/HTP/DSP, EdgeTPU...).
  - Com fp16, agendamento de velocidade sustentada e sem a CPU de referência do NNAPI.
  - O modelo compilado fica guardado em cache, então a segunda abertura não recompila.
  - Se falhar, tenta o NNAPI escolhendo o dispositivo sozinho e depois a CPU (XNNPACK). O status
    diz qual está rodando.
- **Pipeline próprio** (`core/.../handtracking/npu/PalmDecoder.kt`):
  - decodifica as 2016 âncoras do detector e faz NMS ponderado;
  - recorta para cada mão um quadrado girado alinhado ao punho, para o modelo ver a mão em pé;
  - rastreia cada mão pelos landmarks do quadro anterior, sem detector;
  - roda o detector de palma só quando falta uma mão: em todo quadro sem nenhuma, e a cada 6
    quadros quando há uma.
- **Modelos em fp16:** NPUs que só aceitam int8 acabam rodando parte do grafo fora da NPU.

### Verificador (`HandVerifier`, vale para os dois motores)

Medido em fotos reais, forçando o modelo de landmarks sobre recortes de cada imagem:

| Imagem | Presença de mão (modelo) | Palma métrica |
|---|---|---|
| Mãos reais | 0,96 a 0,99 | 4 a 10 cm |
| Cortinas | no máximo 0,06 | — |
| Sola do pé | até 0,73 | 17 a 19 cm |

O limiar antigo de rastreamento do MediaPipe (0,35) deixava passar o pé. Agora:

1. **Presença com histerese:** uma mão nova precisa de 0,8 em 2 quadros seguidos. Uma vez
   confirmada, fica com 0,5, para não piscar em borrões.
2. **Tamanho e proporção:** palma métrica entre 3,5 e 13,5 cm, dedos e largura dos nós dentro da
   proporção humana. Barra os pés e os ajustes absurdos sobre tecido.
3. **Alcance:** a mão precisa estar a até 1 m da câmera. Barra os pés vistos de pé e outras
   pessoas.

No motor MediaPipe, os limiares subiram para 0,55 (detecção) e 0,5 (presença e rastreamento).

A última rejeição aparece no status do teste de mãos, por exemplo "grande demais para uma mão
(pé?)". Testes: `HandVerifierTest`, com landmarks reais de uma mão e do pé como fixtures.

## Latência, lado da mão e visual

- **Entrada rápida:**
  - Com presença de 0,9 ou mais, a mão aparece já no primeiro quadro. Entre 0,8 e 0,9, espera
    um segundo quadro.
  - No motor NPU, a segunda mão é procurada a cada 3 quadros (antes eram 6).
  - O ARCore usa a câmera a 60 fps quando o celular oferece. Com o Camera2 isso já acontecia.
- **Entre quadros da câmera:** cada junta continua andando na velocidade medida entre os dois
  últimos resultados, por até 50 ms. O esqueleto se move a cada quadro da tela (8 a 16 ms), não
  só a cada quadro da câmera (16 a 33 ms).
- **Esquerda ou direita (`HandednessResolver`):** o HyperXR decide sozinho, igual nos dois
  motores.
  - O sinal principal é a quiralidade 3D: de que lado do plano dos nós do indicador e do mínimo
    fica o polegar. Girar a mão não muda esse lado; só um espelho muda. Em fotos e nas versões
    espelhadas, ele concordou com a mão verdadeira em 17 de 18 casos.
  - Junto entram o rótulo do modelo (motor NPU), um leve peso do lado da imagem e a continuidade
    entre quadros.
  - Duas mãos no mesmo quadro sempre recebem lados diferentes.
- **Visual:** aura lilás em volta da mão, com as pontas dos dedos quadradas (veja "Aura lilás nas
  mãos" em NEW_FEATURES.md). O esqueleto e a aura preta antiga foram removidos.

## Otimizações de latência (rodada 3)

- **Recorte nativo (motor NPU):** o quadrado girado da mão (224x224) e o letterbox da palma
  (192x192) saem direto em floats normalizados, numa passada em C++ com amostragem bilinear
  (`YuvNative.warpToTensor`). Antes eram Canvas, getPixels e um laço em Kotlin. A transformação é
  a mesma de `HandRoi.toImage` (`HandRoi.affine`, com teste).
- **Recorte que antecipa o movimento:** o recorte de cada mão anda junto com a velocidade dela
  (suavizada, limitada a 30% do tamanho por quadro). Mãos rápidas não saem do recorte.
- **Segunda chance antes de perder:** se uma mão rastreada some do recorte, o modelo tenta mais
  uma vez com o recorte 40% maior antes de voltar ao detector de palma, que é mais caro e
  demorado.
- **Taxa automática até 60 Hz:** com a configuração em 30 Hz ou mais, os quadros são pegos assim
  que o motor termina o anterior, até 60 por segundo, ou seja, na taxa da câmera de 60 fps. Os
  perfis de bateria do SteamVR, abaixo de 30 Hz, ficam como estão.
- **Previsão pela idade real da imagem:** a idade de cada quadro é medida desde a exposição do
  sensor, pelo horário da câmera, até o resultado. O filtro de Kalman prevê 75% dessa idade, e o
  ajuste "previsão" corrige a partir disso (20 ms = neutro). Na tela, a mão ainda é levada até o
  momento do desenho mais um quadro.
- **Prioridade de thread e ADPF:** a thread de inferência roda com prioridade de display. No
  Android 12 ou mais novo, a inferência e a renderização informam o tempo de cada quadro ao
  PerformanceHintManager, e o sistema sobe o clock da CPU para cumprir o prazo em vez de deixá-la
  ociosa entre quadros.

## Tremor corrigido (filtro novo)

O tremor vinha de dois lugares:
1. **Previsão em dobro sobre velocidade ruidosa.**
   - O `HandTracker` adiantava cada junta até 60 ms com a velocidade do Kalman, que não é
     filtrada.
   - Depois o `snapshot()` adiantava mais até 60 ms, com uma velocidade tirada da diferença
     entre resultados já adiantados.
   - Numa mão parada, o ruído de 3 mm virava centímetros na tela.
2. **Cada junta filtrada sozinha.** O ruído de profundidade (a distância vem do tamanho da mão)
   mexe a mão inteira de uma vez.

Filtro novo (`HandTracker`):
- **Centro e forma separados.**
  - O centro da palma (média do pulso e dos 4 nós dos dedos) tem filtros One Euro que abrem com
    a velocidade.
  - A distância ao longo do raio da câmera é suavizada à parte e com mais força (0,45 Hz em
    repouso; 1,2 Hz com mapa de profundidade).
  - A forma (cada junta relativa à palma) tem filtros mais leves. A pinça é decidida nos pontos
    brutos do modelo, então nunca atrasa.
- **Uma só velocidade.**
  - Vem do centro medido, suavizada por um filtro que também abre com a velocidade (3 Hz + 12 Hz
    por m/s).
  - É aplicada à mão inteira, nunca por junta.
  - Some com a mão parada: a previsão entra gradualmente entre 0,06 e 0,30 m/s.
- **Compensação do atraso do filtro:** a previsão inclui o tempo que o filtro do centro fica
  atrás de um movimento constante, que é 1/(2π·corte).
- **Correção suave:** entre quadros da câmera a mão segue com essa velocidade (`HandMotion`), e
  cada resultado novo entra na tela em cerca de 20 ms, sem salto.

Medido em `HandJitterTest`: 30 Hz, 3 mm de ruído por junta, 8 mm de ruído comum na
profundidade, imagem com 50 ms de atraso, tela a 90 Hz.

| | Antes | Agora |
|---|---|---|
| Tremor com a mão parada (segunda diferença, ponta do indicador) | 9,61 mm | **0,49 mm** |
| Erro com a mão parada | 9,5 mm | 4,8 mm |
| Erro com a mão em movimento (pico de 0,94 m/s) | 66,6 mm | **29,5 mm** |

## Mão que some e mão do tamanho certo (revisão)

Três motivos faziam a mão sumir com frequência:

1. **"Longe demais":** o teste de alcance (1,0 m) eliminava a mão quando a profundidade estimada
   pelo tamanho da palma errava 15–20%. O limite agora é 1,3 m.
2. **Confirmação perdida num quadro ruim:** com movimento rápido, a nota de presença cai por
   causa do borrão. Antes, a mão só voltava com nota ≥ 0,85 (ou ≥ 0,75 duas vezes). Agora, uma mão
   confirmada há menos de 0,6 s volta com a nota de manutenção (0,3).
3. **Troca esquerda/direita:** se a única mão na imagem trocava de lado por um quadro, ela
   recomeçava do zero no outro lado e deixava uma mão fantasma deslizando no lado antigo. Agora a
   confirmação passa para o novo lado e o lado antigo é encerrado na hora (`forceLost`).

**Tamanho exato:** na realidade mista, o contorno da mão é desenhado em pixels a partir dos pontos
da mão *na imagem da câmera*. Eles passam pelo inverso do mesmo mapeamento usado para mostrar a
câmera (corte de preenchimento + `cpuMatrix`) e são levados adiante com a velocidade da mão na
imagem até o instante do quadro exibido. Assim o contorno tem o tamanho e o lugar da mão real,
qualquer que seja a profundidade estimada. No ambiente virtual continua o contorno 3D.

**Otimização:** a máscara da mão reaproveita o mesmo bitmap 256x256. Antes era criado um novo a
cada ~30 ms, cerca de 8 MB/s de lixo e pausas do coletor.

## Mão real na frente da interface com EfficientViT-SAM

O recorte da mão real (a câmera mostrada por cima dos painéis) não usa mais o segmentador de pele
do MediaPipe. Agora é feito pelo **EfficientViT-SAM L0** (MIT Han Lab, Apache-2.0), uma versão
eficiente do Segment Anything.

**Prompts:** cada mão é recortada a partir de "prompts" tirados dos pontos da mão: a caixa da mão
(com a largura de um dedo de folga) mais 6 pontos dentro dela (centro da palma, o meio de cada
dedo e o polegar). O resultado é a mão inteira com a borda certa, sem pegar parede ou objeto da
cor da pele.

**Onde roda:**
- O encoder (512x512, pesos fp16) vai para a NPU; se ela não aceitar, para a GPU; senão, para a
  CPU.
- O decoder roda no ONNX Runtime (CPU), uma vez por mão. Uma única passada do encoder serve as
  duas mãos.

**Ritmo:** a rede roda até ~9 vezes por segundo. Em cada quadro da câmera entre duas passadas, a
última máscara de cada mão é movida e escalada para onde a mão está agora (`SamHands.compose`).
Assim o recorte acompanha a câmera com a qualidade da rede.

**Plano B:** se o SAM não iniciar no aparelho, o segmentador antigo volta como reserva. O
segmentador antigo continua sendo usado para "pessoas atravessam o ambiente".

**Mais:** a conversão e as conferências estão em `tools/hand_sam/README.md`. A linha de status das
mãos mostra onde cada parte rodou e quanto tempo levou. Ainda não foi medido num celular.
