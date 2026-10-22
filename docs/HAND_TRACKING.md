# Hand tracking

```
câmera (ARCore CPU image ou Camera2 YUV 640x480; também em VR, só para as mãos)
 → YUV→RGB em buffers reutilizados, ≤ 480 px de largura (sem alocação por quadro)
 → MediaPipe Hand Landmarker, LIVE_STREAM, delegate GPU (CPU como fallback, informado)
   · detector de palma só quando a mão some; depois rastreamento por ROI interno do MediaPipe
   · só o quadro mais novo é processado; quadros que chegam durante a inferência são descartados
 → DepthFusion: profundidade da palma pelo ARCore Depth ou pelo tamanho da mão
 → HandTracker: plausibilidade anatômica, One Euro, predição, perda/recuperação sem salto
 → gestos (pinça com histerese, apontar, punho, mão aberta)
 → raio de mão + pinça, Direct Touch, aura, API hx.hands
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
- **Sem Depth**: distância pelo comprimento 3D da palma em pixels contra uma palma adulta média
  (9 cm), marcada `HAND_SIZE_PRIOR` com confiança reduzida.
- Posição 6DoF da mão no mundo só com pose de câmera conhecida (ARCore); em 3DoF as mãos ficam
  relativas à cabeça.

## Interação

- **Raio de mão + pinça**: raio de um ombro estimado passando pela mão; pinçar clica. É o modo mais
  confiável à distância, porque não depende de profundidade exata.
- **Direct Touch**: tocar o painel com a ponta do indicador (limiares alargados quando a
  profundidade é estimada).
- **Aura preta**: campo de distância de cápsulas unido por blending MAX em meia resolução e
  composto como borda escura + interior translúcido; some com Joy-Cons conectados.
