# Projetos open source de VR: o que o HyperMagic usa e o que vem a seguir

Triagem da lista de 90 repositórios (runtimes, engines, WebXR, visão, física, GPU, áudio,
ferramentas). Todas as licenças da lista podem conviver com a GPLv3 do HyperMagic; a exceção é
o OpenMVS (AGPL-3.0), que traria obrigações de serviço em rede e fica de fora. Cada projeto que
entra é compilado do código-fonte, com a licença ao lado e crédito no app (Início > Créditos e
licenças) e no README.

## Já em uso

| # | Projeto | Onde |
|---|---|---|
| 81 | Resonance Audio | som 3D (HRTF) de todos os sons posicionados, `app/src/main/cpp/spatial_audio.cpp` |
| 86 | libyuv | câmera -> hand tracking com NEON e filtro box (0,21 ms contra 0,94 ms por quadro no teste), `yuv_convert.cpp` |
| 06/07 | OpenXR-SDK / SDK-Source | loader e headers do runtime OpenXR |
| 15 | Godot | o Open Saber da Loja é compilado com ele |
| 39/40/41 | MediaPipe, TensorFlow Lite | mãos, IA no celular |
| 44 | ONNX Runtime | detector de mãos (CPU/NPU) e HybridMagic no PC |
| 38 | OpenCV | HybridMagic no PC |
| 89/90 | AndroidX, Kotlin | o app inteiro |

## Próximos lotes (ganho real num celular)

| # | Projeto | Para quê |
|---|---|---|
| 46 | AprilTag | Joy-Con e controles com marcadores impressos: posição exata, sem depender de cor |
| 30/31 | WebXR Polyfill e Input Profiles | jogos WebXR no navegador em estéreo, com controles certos |
| 09 | OpenXR-CTS | testes oficiais de conformidade do runtime OpenXR do HyperMagic |
| 11 | Cardboard SDK | perfis de lente de qualquer óculos pelo QR code, distorção oficial |
| 65 | Jolt Physics | física de verdade para os jogos e pacotes .hyperx |
| 80 | miniaudio | decodificar MP3/OGG/FLAC para o som 3D (hoje só sons sintetizados vão ao HRTF) |
| 01 | Monado | comparar e trazer partes do compositor (distorção, timewarp) para o runtime |

## Fora, e por quê

- **Engines completas** (12–14, 16–23): o HyperMagic já tem renderizador próprio (GLES 3); trocar
  por OGRE, Urho3D, Filament etc. seria reescrever o app sem ganho para o usuário.
- **Bibliotecas JavaScript** (24–29, 33–37): rodam dentro das páginas web; o navegador já as
  executa quando um site as usa, não precisam entrar no app.
- **SLAM de pesquisa** (47–52): ORB-SLAM, VINS, DSO etc. são pesados para celular em tempo real e o
  ARCore já faz esse trabalho; 53–63 (fotogrametria, nuvens de pontos) são ferramentas de PC.
- **Runtimes de PC/headset** (02–05, 10): OpenHMD, libsurvive, WiVRn, ALVR e OpenXR-Toolkit
  dirigem headsets e estações-base ou rodam no Windows; não se aplicam a um celular num VR Box.
- **Infra de GPU de PC** (70–78): SDL, GLFW, ANGLE, Vulkan validation, shaderc — ferramentas de
  desenvolvimento, não funções para o usuário.
- **Mídia** (79, 82–85, 87): OpenAL Soft, Steam Audio, Opus, Ogg/Vorbis e FFmpeg duplicam o que o
  Android já faz (MediaCodec) ou o Resonance Audio já cobre.
- **Física alternativa** (64, 66–69): uma engine de física basta; a escolhida é a Jolt.
