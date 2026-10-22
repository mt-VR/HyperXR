# Compatibility-Layer (referência)

Partes do projeto Compatibility-Layer enviadas pelo autor do HyperXR, guardadas como referência:

- `sdk/tools/compatibility_layer_port.py` → a parte OpenXR foi portada para Kotlin em
  `core/src/main/kotlin/com/hyperxr/xrapps/ApkPatcher.kt` (patch feito no próprio celular).
- `sdk/docs`, `sdk/runtime`: documentação e cabeçalho do protocolo de entrada.

O HyperXR roda só OpenXR: o adaptador VrApi (Gear VR / Quest antigo), o driver
`com.oculus.systemdriver`, o runtime Monado pré-compilado e a chave de assinatura do zip não fazem
parte do projeto. O loader OpenXR que o patcher coloca nos jogos é o oficial da Khronos
(`org.khronos.openxr:openxr_loader_for_android`, Maven).
