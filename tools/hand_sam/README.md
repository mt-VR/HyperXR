# Recorte das mãos com EfficientViT-SAM

Modelo: EfficientViT-SAM L0, da MIT Han Lab (https://github.com/mit-han-lab/efficientvit, licença
Apache-2.0). Pesos: `efficientvit_sam_l0.pt` do Hugging Face (mit-han-lab/efficientvit-sam,
Apache-2.0).

## Como os arquivos de `app/src/main/assets/models/` foram feitos

1. `export_hand.py`, rodado de dentro de um clone do repositório oficial, gera:
   - `hand_sam_encoder.onnx`: imagem RGB 0..1, 1x3x512x512 → embeddings 1x256x64x64. A
     normalização do SAM fica dentro do modelo.
   - `hand_sam_decoder.onnx`: embeddings + 8 prompts (cantos da caixa da mão com rótulos 2 e 3, e
     pontos na mão com rótulo 1) → máscara 1x1x256x256 e IoU. Esta versão é usada como está pelo
     ONNX Runtime no celular.
2. `python -m onnxsim hand_sam_encoder.onnx enc_sim.onnx`
3. `fp16_eps.py enc_sim.onnx enc_fp16safe.onnx`: troca o epsilon 1e-15 da atenção linear por
   1e-4. Sem isso, a conta em fp16 dá NaN.
4. `onnx2tf -i enc_fp16safe.onnx -o out -tb tf_converter`. O arquivo `out/enc_fp16safe_float16.tflite`
   (pesos fp16 com desquantização) vira `hand_sam_encoder.tflite`.

`erf_to_tanh.py` (troca o Erf pela forma tanh do GELU) só seria necessário para converter também
o decoder para TFLite. A conversão do decoder pelo onnx2tf saiu errada (a reordenação de eixos
estraga o flatten/transpose do SAM), por isso o decoder roda em ONNX.

## Conferido no computador

- **Encoder TFLite fp16 vs ONNX exato:** a máscara final concorda em 99,06% dos pixels numa foto
  real de mão (Wikimedia Commons, só para teste, não incluída).
- **Encoder fp32:** diferença máxima de 5e-5.
- **Decoder:** a versão original e a simplificada dão resultado idêntico.
- **Tempos medidos na CPU do sandbox** (não do celular): encoder 301 ms, decoder 88 ms. No
  celular, o encoder deve ir para a NPU ou a GPU.
