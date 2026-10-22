# Compatibility-Layer (referência)

Fontes e documentação do projeto Compatibility-Layer enviadas pelo autor do HyperXR (zip
"questandgearandopenxr"), guardadas aqui como referência. O que o HyperXR usa delas:

- `sdk/tools/compatibility_layer_port.py` → portado para Kotlin em
  `core/src/main/kotlin/com/hyperxr/xrapps/ApkPatcher.kt` (patch feito no próprio celular).
- `gearvr-shim/src` (adaptador VrApi → OpenXR) e `vrapi-driver-java` (driver
  `com.oculus.systemdriver`): **não são compilados nem embutidos**. Compilar o adaptador exige os
  headers do VrApi (Oculus Mobile SDK, licença da Oculus, não incluídos) e os binários
  pré-compilados do zip não foram embutidos no APK. Sem o adaptador, jogos VrApi (Gear VR e Quest
  antigos) aparecem no importador como "precisa do adaptador VrApi".
- O loader OpenXR que o patcher coloca nos jogos é o loader oficial da Khronos
  (`org.khronos.openxr:openxr_loader_for_android`, Maven), não o binário do zip.

Também ficaram de fora: o APK pré-compilado do runtime Monado e a chave de assinatura `.p12`.
