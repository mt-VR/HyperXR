# Status das funcionalidades

Legenda: **✅ testado** (testes automatizados passam) · **🟡 implementado, não executado em hardware**
(compila, APK gerado, mas sem aparelho/emulador neste ambiente) · **⏳ pendente** · **🚫 limitação real**.

Nenhuma afirmação abaixo diz que algo "funciona no aparelho": isso só será verdade após teste em hardware.

## Fundação
| Item | Status |
|---|---|
| UI 2D em paisagem com 15 seções ligadas a funcionalidades reais | 🟡 |
| Configurações persistentes (gravação atômica, recuperação de arquivo corrompido) | ✅ |
| Alternância Dock/New no 2D e no XR, persistida | ✅ modelo · 🟡 UI |
| Detecção de capacidades (sensores, câmeras, ARCore, NNAPI, Bluetooth, áudio, OpenXR) | 🟡 |
| Ícones do autor numa biblioteca central | ✅ extraídos · ⏳ trocar pelos PNGs originais em alta resolução |

## XR
| Item | Status |
|---|---|
| Decisão MR/VR honesta com motivos | ✅ |
| MR com ARCore (passthrough, 6DoF, Depth quando suportado) | 🟡 |
| MR com Camera2 + 3DoF (imagem mono) | 🟡 |
| VR SBS com distorção de lente, IPD, FOV configuráveis | 🟡 |
| Câmera preenchendo todo o SBS (recorte central por olho; opção de escala angular real) | 🟡 |
| 3DoF por vetor de rotação + predição por giroscópio + modelo de pescoço | 🟡 |
| OpenXR: sonda real do runtime (loader Khronos) | 🟡 |
| OpenXR: sessão GLES, swapchains, laço de quadros | 🟡 (código completo; precisa de runtime instalado) |
| Runtime Broker (descoberta, seleção, árbitro de sessão) | ✅ modelo · 🟡 Android |
| Monado | 🚫 não embutido (ver ARCHITECTURE.md); detectado e preferido quando instalado |
| **HyperXR Runtime** (runtime OpenXR próprio, GLES, 3DoF, lente + timewarp, quad layers) | ✅ host (196 verificações, ASan) · 🟡 aparelho · ver OPENXR_RUNTIME.md |
| Entrada de controles para apps OpenXR no HyperXR Runtime | 🚫 o runtime roda no processo do app |
| Mãos/controles vindos do runtime OpenXR (XR_EXT_hand_tracking, ações) | ⏳ |
| Oclusão por profundidade na renderização | ⏳ (Depth é usado para as mãos e o alerta de limite) |
| Alerta de limite em VR: câmera aparece aos poucos perto de obstáculos (ARCore Depth) | ✅ cálculo · 🟡 aparelho · 🚫 sem Depth |
| Renderização em MR com o FOV da câmera (mãos no tamanho real), mono por padrão | 🟡 |

## Launcher
| Item | Status |
|---|---|
| Dock: fixar, remover, reordenar, recentes, painel inicial, barra de controles | ✅ modelo · 🟡 XR |
| New: ícones 3D, foco, animações, segurar para mover (persistido) | ✅ modelo · 🟡 XR |
| Janelas espaciais móveis (biblioteca, ajustes, arquivos, galeria, mensagens, ajuda, TV, apps XR, ambientes) | 🟡 |
| Captura da visão para a Galeria | 🟡 |
| Navegador GeckoView (motor do Firefox, o mesmo do Wolvic) renderizando direto na textura da janela | 🟡 (arm64; WebView como fallback) |
| Navegador: abas, barra de endereço com pesquisa, progresso, favoritos, histórico, modo desktop/celular | 🟡 |
| Navegador: `<select>`, alert/confirm/prompt como painéis XR; teclado abre sozinho ao focar um campo | 🟡 |
| Navegador: vídeo em tela cheia vira tela de cinema curva (3,2 m a 2,4 m); downloads para Downloads | 🟡 |
| Janelas acima da dock (Dock) e à frente dos ícones (New); botão Início minimiza/restaura | 🟡 |
| Reabrir janelas (abas e apps Android) ao entrar no XR; redimensionar (Menor/Maior) | 🟡 |
| Teclado XR menor e próximo, acentos (áç), ditado por voz (SpeechRecognizer) | 🟡 |
| Bolinha de pinça na mão, sem feixe; pinçar = toque real (down/move/up) | 🟡 |
| Apps Android em janela plana ou curva via Shizuku (display virtual + injeção de toque/tecla/texto) | 🟡 · 🚫 sem Shizuku |
| Teclado XR (QWERTY, símbolos) para navegador e apps | 🟡 |
| Arrastar link entre janelas (pinça longa no link, soltar em outra janela ou no vazio) | ✅ lógica · 🟡 aparelho |
| Área de trabalho remota | ⏳ (exibida como indisponível) |
| Temas visuais selecionáveis | ⏳ (há um tema; o campo existe nas configurações) |

## Entrada
| Item | Status |
|---|---|
| Filtros, perda/recuperação, gestos, fusão de profundidade | ✅ |
| Direct Touch (proximidade, contato, histerese, anti-duplo-clique, arrastar) | ✅ lógica · 🟡 integração |
| Inferência de mãos (MediaPipe Hand Landmarker na GPU, fallback CPU, modelo embutido, benchmark) | 🟡 |
| Hand tracking também no modo VR (câmera só para as mãos) | 🟡 |
| Clique por olhar (dwell com anel de progresso), toque travado (não perde toques curtos), raio de mão + pinça | ✅ lógica (core e Lua) · 🟡 aparelho |
| Aura preta (campo SDF unido + composição) | 🟡 |
| Joy-Cons: analógico move o cursor, A clica/arrasta, B volta/fecha (segurar fecha a janela), R/ZR + analógico rola; sem sensores de movimento | ✅ lógica · 🟡 Android |
| Pinça medida nos landmarks métricos do MediaPipe (fecha < 38 % da palma, abre > 55 %) | ✅ lógica · 🟡 aparelho |
| Tamanho real da mão (calibração por ARCore Depth ou comprimento informado), pinça proporcional | ✅ lógica · 🟡 aparelho |
| Menu radial por pinça segurada (Voltar, Início, Teclado, Captura) | ✅ lógica · 🟡 aparelho |
| Microgestos: polegar no indicador rola, toque duplo volta | ✅ lógica · 🟡 aparelho |

## Conteúdo
| Item | Status |
|---|---|
| Launcher Cardboard/OpenXR (categorias de intent, sem QUERY_ALL_PACKAGES) | 🟡 |
| HyperXR Environments (Poly Haven CC0, MD5, Wi-Fi, atribuição, cena 3D vs 360°) | ✅ parser · 🟡 download/render |
| Ambiente procedural embutido | 🟡 |
| Apps web (TikTok, Twitch, YouTube, WhatsApp Web, Facebook, Instagram): 2D e painel espacial | 🟡 |

## SDK e jogos
| Item | Status |
|---|---|
| Formato `.hyperx`, validação, integridade, ferramenta `hyperx` | ✅ |
| Sandbox, permissões, orçamento, memória, watchdog | ✅ (watchdog 🟡) |
| Ponte JNI LuaJIT | ✅ no host · 🟡 no Android |
| Scene/Window, UI, física, áudio, rede, arquivos | ✅ em Lua · 🟡 host Android |
| Jogos antigos (STILLSHOT, FORGE LAB, PRIMAL RUSH) | removidos a pedido; desinstalados automaticamente |
| `hx.camera` com pixels para scripts | ⏳ (retorna erro explicativo) |
| Assinatura de pacotes por publicador | ⏳ |

## Desempenho
| Item | Status |
|---|---|
| Instrumentação por estágio (média, p50/p95/p99, desvio) | ✅ |
| Medições em aparelhos reais | ⏳ nenhuma ainda |
| Resolução dinâmica, timer de GPU, ETC2/ASTC, LOD, glTF assíncrono, reação térmica | ⏳ |

## Correções após o primeiro teste em aparelho
- Botões de painéis do XR não clicavam: as regiões clicáveis eram recriadas a cada redesenho e
  comparadas por identidade; agora são comparadas por id.
- Toques mais curtos que um quadro se perdiam: agora ficam enfileirados (`TapLatch`).
- Hand tracking não rodava porque nenhum modelo vinha no APK: agora o modelo MediaPipe é embutido.
- Ícones abaixo da linha dos olhos inclinavam para longe do usuário (sinal da rotação invertido).
- Mão "minúscula" em MR: o conteúdo era renderizado com o FOV do visor e a câmera com outro; agora
  usam o mesmo FOV e o mesmo ponto de vista, e a escala da mão vem do tamanho medido/informado.
- Controles instáveis: o 6DoF agora vem da mão (não do IMU sozinho) e o raio é filtrado.
- Navegador em branco: a captura por Canvas foi trocada por um display virtual com WebView real.

## Bloqueios deste ambiente
- Sem aparelho Android nem emulador (sem KVM): nada foi executado em hardware.
- Push para o GitHub recusado (403 do GitHub App) no início; resolvido após reconectar.
