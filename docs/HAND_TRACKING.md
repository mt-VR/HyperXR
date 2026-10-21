# Hand tracking

Pipeline próprio (sem MediaPipe):

```
câmera (ARCore CPU image ou Camera2 YUV 640x480)
 → detector (só quando falta mão, a cada 3 quadros)       [LiteRT: CPU/XNNPACK, GPU ou NNAPI]
 → landmarks 21 pontos na ROI prevista do quadro anterior   [LiteRT]
 → DepthFusion: profundidade da palma (ARCore Depth) ou prior de tamanho da mão
 → coordenadas 3D da câmera → espaço de rastreamento (pose da câmera)
 → HandTracker: plausibilidade anatômica, One Euro por coordenada, predição, perda/recuperação sem salto
 → GestureRecognizer: pinça (histerese), apontar, punho, mão aberta
 → Direct Touch, aura, API hx.hands
```

## Profundidade e 6DoF

- **ARCore Depth**: mediana da profundidade nos 5 pontos da palma (robusta a bordas).
- **Sem Depth**: distância estimada pelo comprimento 3D da palma do esqueleto em pixels contra uma
  palma adulta média (9 cm). Marcada como `HAND_SIZE_PRIOR`, com confiança reduzida; o Direct Touch
  alarga seus limiares nesse caso. Mãos muito pequenas/grandes têm erro sistemático.
- A posição 6DoF da mão no mundo só existe quando a pose da câmera é conhecida (ARCore). Em 3DoF as
  mãos ficam relativas à cabeça.
- Sem profundidade nem prior confiável, `DepthFusion.fuse` retorna falso — nenhuma posição é inventada.

## Modelos (não embarcados)

Importe em Configurações > Hand tracking:

- `hand_detector.tflite`: entrada `[1,H,W,3]` (float 0..1 ou uint8); saída no formato
  `TFLite_Detection_PostProcess`: caixas `[1,N,4]` (ymin,xmin,ymax,xmax normalizados), classes,
  scores `[1,N]`, contagem `[1]`.
- `hand_landmark.tflite`: entrada `[1,S,S,3]`; saída 0 `[1,63]` (x,y em pixels da entrada, z relativo),
  saída 1 presença `[1,1]`, saída 2 lateralidade `[1,1]` (> 0,5 = direita).

Use somente modelos cuja licença permita o seu uso. O botão de benchmark mede o modelo de landmarks
em CPU, GPU e NNAPI no aparelho e mostra média/percentis; se um backend não carrega, o motivo aparece.

## NNAPI / NPU

A lista de dispositivos NNAPI vem do driver (`ANeuralNetworks_getDevice*`). Ter NNAPI não significa
ter NPU, nem que todas as operações do modelo serão aceleradas: o delegate pode cair para CPU por
operação. No Android 15+ a NNAPI está obsoleta; GPU/CPU continuam disponíveis.

## Aura preta

Cada osso vira um billboard cujo shader avalia a distância a uma cápsula; mistura MAX num alvo R8 de
meia resolução gera a união de todas as cápsulas (sem costuras nas juntas). Um segundo passe converte
esse campo em borda preta suave + interior preto translúcido, sem esconder o que está atrás.
Com Joy-Cons conectados a aura some, mas o rastreamento continua alimentando a origem do raio.
