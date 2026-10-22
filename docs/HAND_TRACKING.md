# Hand tracking

```
câmera (ARCore CPU image ou Camera2 YUV 640x480; também em VR, só para as mãos)
 → 3 slots em pipeline: enquanto a GPU infere o quadro N, a CPU converte o N+1 (YUV→RGB em
   2 threads, buffers reutilizados) e a próxima inferência começa assim que a anterior termina
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
