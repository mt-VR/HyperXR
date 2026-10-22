# Hand Physics Lab

Experiência de realidade mista do HyperXR: uma bancada de oficina aparece à sua frente, abaixo da
dock, no mesmo espaço do launcher (em MR ela fica no seu cômodo, sobre a imagem da câmera). Abra
pelo ícone **Hand Physics Lab** na dock ou na Biblioteca; feche pelo botão *Fechar* do painel da
bancada.

## Objetos

| Objeto | Forma física | Material | Massa |
|---|---|---|---|
| Torre de 18 blocos (7,5 × 1,5 × 2,5 cm, 6 andares cruzados) | caixa | madeira (650 kg/m³, atrito 0,55) | ~18 g cada |
| 10 dominós | caixa | plástico | ~10 g |
| 3 cubos de brinquedo (5 cm) | caixa | madeira | ~81 g |
| Bola de borracha (6,8 cm) | esfera | borracha (quica 0,78) | ~180 g |
| Esfera de aço (4 cm) | esfera | aço (7850 kg/m³) | ~260 g |
| Bola de pingue-pongue (4 cm) | esfera | celuloide | 2,7 g |
| 2 latas (6,6 × 12 cm) | cápsula | alumínio | 350 g (cheia) |

## Interação

- **As mãos são corpos físicos**: cada osso do esqueleto rastreado é um colisor cinemático que
  empurra, derruba e dá petelecos (a velocidade do dedo passa para o objeto).
- **Pinça** pega objetos pequenos com força limitada (25 N); **fechar a mão** agarra firme (60 N).
  Objetos pesados ficam para trás e balançam; soltar no meio de um movimento arremessa.
- **Joy-Con**: mire com o cursor e segure **A** para carregar um objeto.
- Painel: *Reiniciar*, *Gravidade* (Terra, Lua, zero-g), *Câmera lenta*, *Soltar bola*, *Fechar*.
- Ao segurar algo, aparecem o nome, a massa e o material.

## Motor

`core/.../physics/RigidWorld.kt`, escrito para o HyperXR (sem bibliotecas externas):

- esferas, caixas e cápsulas com massa e tensor de inércia reais;
- contato caixa–caixa por eixos separadores + recorte de face (até 8 pontos: pilhas apoiam por
  inteiro), esfera/cápsula contra tudo;
- impulsos sequenciais com *warm starting*, correção de penetração por *split impulse* (sem
  injetar energia), atrito de Coulomb em duas direções e restituição só em impactos reais;
- 16 subpassos por quadro (cai para 10 se a física passar de 6 ms no aparelho), sono por ilha
  (objetos encostados dormem e acordam juntos) e pegada por PD com força máxima.

Renderização: sombras por *shadow map* 2048² com PCF sobre a bancada, brilho especular
(Blinn-Phong + Fresnel) e metais com reflexo tingido; texturas de madeira, tapete de corte,
dominós, latas e cubos geradas proceduralmente no aparelho. Sons de impacto sintetizados por
material, com volume pela velocidade do impacto e posição 3D.

## Testes

`core/src/test/kotlin/com/hyperxr/PhysicsTest.kt` (11 testes): queda e repouso, caixa assentada,
pilha de 5 blocos, **torre cruzada de 8 andares estável por 5 s**, borracha quica mais que aço,
distância de frenagem por atrito, dedo cinemático empurra, objeto pesado atrasa mais que leve
na mão, arremesso mantém a velocidade, lata deitada não rola, mão acorda objeto em repouso.
Medição no JVM: ~1,7 ms por quadro numa cena como a do laboratório com as mãos em movimento.

## Limitações

- Em 3DoF (sem ARCore) a bancada fica fixa à sua frente; não dá para andar até ela.
- A posição dos dedos tem a precisão do rastreamento por câmera do celular: empurrões finos
  (como puxar um bloco do meio da torre) podem exigir algumas tentativas.
- A física não foi medida num aparelho real ainda.
