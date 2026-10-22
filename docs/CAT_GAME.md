# Gato vs. Vovó

Jogo VR nativo do HyperXR, original e inspirado na ideia de ser um gato na casa da avó (nenhum
asset, nome ou código de outro jogo). Abre pela Biblioteca, categoria Jogos e XR.

## Objetivo
A vovó cumpre cinco tarefas (regar as plantas, cozinhar a sopa, lavar a louça, varrer a sala, tirar
o pó da estante). Você vence se derrubar a energia dela a zero antes disso; perde se ela terminar o
dia ou se o gato ficar sem vida.

- Objetos arremessados ou derrubados que a atingem tiram energia (mais massa e velocidade, mais dano)
  e custam parte da tarefa em andamento.
- Derrubar a panela do fogão faz a sopa recomeçar do zero.
- Ela vê o gato num cone de ~60° até 3,2 m (ou bem perto, ou quando você mia) e persegue: ergue o
  chinelo (aviso "!" por 0,7 s) e golpeia; se o gato estiver longe ou no alto, usa o borrifador.
  A esquiva (B) dá invulnerabilidade durante o arranque.

## Controles (Joy-Cons, Pro Controller ou um Joy-Con)
| Ação | Botão |
|---|---|
| Andar (para onde você olha) | analógico esquerdo |
| Girar | analógico direito |
| Pular (mesas e bancadas) | A |
| Esquivar | B |
| Pegar com a boca / arremessar ao soltar | ZR ou R (toque na tela também) |
| Patada (derruba coisas) | ZL ou L |
| Miar | X ou Y |
| Pausa | + ou − |

## Técnica
Casa de 8 × 7 m com texturas procedurais (assoalho, papel de parede, azulejos, tecidos, tapete),
sombras e iluminação de janela; ~30 objetos com física de corpos rígidos (`RigidWorld`) e sons
sintetizados por material. O gato tem controlador próprio (colisão com móveis, pulo, chão em cima
dos móveis) e a câmera fica na altura de um gato (26 cm); a cabeça continua rastreada. A vovó é
montada com primitivas animadas (caminhada, braços por tarefa, chinelo, borrifador) e tem uma
máquina de estados com tarefas, visão, perseguição, ataque, atordoamento e desvio de obstáculos.

Não testado em aparelho: velocidade, dificuldade e conforto de locomoção precisam de ajuste com uso.
