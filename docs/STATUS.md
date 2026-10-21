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
| 3DoF por vetor de rotação + predição por giroscópio + modelo de pescoço | 🟡 |
| OpenXR: sonda real do runtime (loader Khronos) | 🟡 |
| OpenXR: sessão GLES, swapchains, laço de quadros | 🟡 (código completo; precisa de runtime instalado) |
| Runtime Broker (descoberta, seleção, árbitro de sessão) | ✅ modelo · 🟡 Android |
| Monado | 🚫 não embutido (ver ARCHITECTURE.md); detectado e preferido quando instalado |
| Mãos/controles vindos do runtime OpenXR (XR_EXT_hand_tracking, ações) | ⏳ |
| Oclusão por profundidade na renderização | ⏳ (Depth é usado só para as mãos) |

## Launcher
| Item | Status |
|---|---|
| Dock: fixar, remover, reordenar, recentes, painel inicial, barra de controles | ✅ modelo · 🟡 XR |
| New: ícones 3D, foco, animações, segurar para mover (persistido) | ✅ modelo · 🟡 XR |
| Janelas espaciais móveis (biblioteca, ajustes, arquivos, galeria, mensagens, ajuda, TV, apps XR, ambientes) | 🟡 |
| Captura da visão para a Galeria | 🟡 |
| Área de trabalho remota | ⏳ (exibida como indisponível) |
| Temas visuais selecionáveis | ⏳ (há um tema; o campo existe nas configurações) |

## Entrada
| Item | Status |
|---|---|
| Filtros, perda/recuperação, gestos, fusão de profundidade | ✅ |
| Direct Touch (proximidade, contato, histerese, anti-duplo-clique, arrastar) | ✅ lógica · 🟡 integração |
| Inferência de mãos (LiteRT CPU/GPU/NNAPI, ROI, benchmark) | 🟡 · modelos não embarcados (usuário importa) |
| Aura preta (campo SDF unido + composição) | 🟡 |
| Joy-Cons: botões, R/L configuráveis, reconexão, IMU quando o driver expõe | ✅ lógica · 🟡 Android |
| Fusão mão + orientação do controle com calibração | ✅ |

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
| STILLSHOT (tempo reage ao movimento, IA, armas, 3 níveis, reinício rápido) | ✅ roda 1200 quadros no host · 🟡 jogabilidade real |
| FORGE LAB (agarrar, física, ferramentas, porta, alavanca, placa, vidro, 4 desafios) | ✅ idem · 🟡 |
| PRIMAL RUSH (locomoção pelos braços, Tag contra bots, contra-relógio) | ✅ idem · 🟡 |
| Multiplayer do PRIMAL RUSH | ⏳ módulo futuro (requer servidor de sincronização) |
| `hx.camera` com pixels para scripts | ⏳ (retorna erro explicativo) |
| Assinatura de pacotes por publicador | ⏳ |

## Desempenho
| Item | Status |
|---|---|
| Instrumentação por estágio (média, p50/p95/p99, desvio) | ✅ |
| Medições em aparelhos reais | ⏳ nenhuma ainda |
| Resolução dinâmica, timer de GPU, ETC2/ASTC, LOD, glTF assíncrono, reação térmica | ⏳ |

## Bloqueios deste ambiente
- Sem aparelho Android nem emulador (sem KVM): nada foi executado em hardware.
- Push para o GitHub recusado (403 do GitHub App) durante o desenvolvimento.
