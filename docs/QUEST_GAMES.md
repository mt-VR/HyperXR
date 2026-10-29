# Jogos inspirados nos famosos do Quest

Dez jogos do HyperXR inspirados na jogabilidade dos jogos mais famosos do Quest. Eles ficam na
biblioteca, em Jogos e experiências, cada um com seu ícone, e rodam no espaço do launcher: ele
coloca o jogo na sua frente, com o chão sob você.

Só a ideia de jogo foi aproveitada. Nomes, regras, visual e código são do HyperXR, e nada vem
dos jogos originais. Os modelos 3D são dos kits da Kenney (CC0, domínio público), com a licença
ao lado de cada kit em `app/src/main/assets/games/*/License.txt`.

## Só com as mãos

| Jogo | Inspirado em | Como joga |
|---|---|---|
| Sabre de Ritmo | Beat Saber | Blocos vêm no ritmo de uma música sintetizada (128 BPM). Corte com a mão da cor certa (esquerda vermelha, direita azul), na direção da seta. Tem combo e multiplicador até 8x. |
| Ninja das Frutas | Fruit Ninja VR | Frutas da Kenney sobem na sua frente: passe a mão rápido por elas. Viram metades com suco. Bombas tiram pontos. Dura 1 minuto. |
| Ringue | The Thrill of the Fight | Boxe contra um adversário que dá socos anunciados: esquive movendo a cabeça ou bloqueie com as mãos na frente do rosto. Soque cabeça e corpo; nocaute vence. |
| Prancha no Céu | Richie's Plank Experience | Elevador até 90 m, a porta abre e há uma prancha sobre a rua. Ande até a ponta e dê um passo para fora. Sem posição 6DoF, segurar a pinça faz você avançar. |
| Minigolfe | Walkabout Mini Golf | Três buracos no seu chão. Faça a pinça para segurar o taco e empurre a bola, que tem física de rolamento, bate nas paredes e cai na caçapa. |

## Com controle

Funcionam com Joy-Con, com o celular controle (HyperConnect) ou com um gamepad Bluetooth.

| Jogo | Inspirado em | Como joga |
|---|---|---|
| Tempo Parado | SUPERHOT VR | O tempo só anda quando você se mexe. Inimigos vermelhos chegam e atiram balas lentas. Mire com o controle e atire; desvie das balas (dá para acertar as balas também). |
| Caçador de Drones | Space Pirate Trainer | Drones da Kenney voam em volta e atiram. Gatilho atira; o outro botão segura um escudo. Três vidas, ondas cada vez maiores. |
| Tênis de Mesa | Eleven Table Tennis | O controle é a raquete e o gatilho saca. Contra o computador, até 11 pontos, com regras de quique e rede. |
| Pescaria | Real VR Fishing | Lago com árvores e acampamento. Segure o gatilho, balance e solte para lançar. Quando a boia afunda (o controle vibra), aperte para fisgar. Recolha com o outro botão sem passar da tensão da linha. Quatro peixes. |
| Pequeno Herói | Moss | Um mundo em miniatura sobre a mesa. Ande com o analógico (no celular controle, segure B e aponte), pule com o gatilho, pegue as moedas e chegue à bandeira. |

## Locomoção com as mãos: Gorilla Locomotion oficial

Macacada, Babuíno da Meia-Noite, Gravidade Zero e Escalada de Cano usam o `GorillaRig`
(`core/.../questgames/GorillaRig.kt`), um porte para Kotlin do `Player.cs` do
[GorillaLocomotion](https://github.com/Another-Axiom/GorillaLocomotion) da Another Axiom, o
movimento do Gorilla Tag (licença MIT, Copyright (c) 2021 Another-Axiom). O algoritmo é o
deles, linha por linha. O que o HyperMagic acrescenta está em
[third_party/GorillaLocomotion/README.md](../third_party/GorillaLocomotion/README.md): os
sphere casts no lugar do motor de física do Unity, o corpo rígido e as superfícies sem pegada.
A licença vai no APK em `assets/games/GorillaLocomotion-LICENSE.txt`.

Para a câmera de um celular, as mãos passam pelo `HandFeed` antes de chegar ao gorila:
- filtro One Euro: a mão parada fica parada, sem atrasar o movimento rápido;
- um salto impossível da detecção é ignorado;
- uma mão que a câmera perde fica onde estava por um instante, em vez de soltar.

Nos jogos de gorila, uma mão baixa na imagem é levada mais para baixo e para longe. A câmera só
vê as mãos até uns 25 graus abaixo dos olhos, e assim o chão fica ao alcance com as mãos ainda
à vista.

### Escalada de Cano

Você só tem um cano na mão e não consegue andar. As mãos não aparecem: uma bola em cada mão
segura o cano. A ponta e o meio do cano são as "mãos" do Gorilla Locomotion: onde encostam,
ficam, e quem se move é você. Dá para:
- engatar o cano numa barra e puxar;
- apoiar a ponta num degrau e empurrar;
- saltar um vão apoiando em pinos.

Nas paredes de vidro dos lados o cano escorrega e não segura. Com as duas mãos, o cano vai da
mão mais perto de você na direção da outra, como uma vara. Com uma mão, segue a direção dela;
com um controle, a mira do controle.

## Testes

A física e as regras ficam no módulo core e têm testes no JVM (`QuestGamesTest`, 11 testes):
- cortes no ritmo;
- frutas;
- socos e esquivas;
- bola de golfe;
- tênis de mesa;
- pesca;
- o herói pulando e caindo;
- drones e mira.

`GameAssetsTest` carrega todos os modelos com o carregador glTF do HyperXR.

**Ainda não testado num celular:** a sensação de jogo (velocidades, tamanhos, dificuldade) pode
precisar de ajuste depois de jogar de verdade.

## Por que não Sketchfab

A API do Sketchfab só deixa baixar modelos com o token de uma conta ("Authentication credentials
were not provided"). Os kits da Kenney são CC0 e baixam direto.

Para trocar por modelos do Sketchfab:
- é preciso o token da sua conta, que fica em Configurações > Senha e API no site;
- a licença de cada modelo precisa ser seguida; CC-BY exige o crédito do autor na tela de
  créditos.

## Visual (cenários, brilho, sombras)

Cada jogo tem o próprio mundo, pintado por `GameSky` (um único passe atrás de tudo): céu em
gradiente, sol, estrelas, nebulosa ou nuvens, e um chão infinito (grade neon, planície ou água
que reflete o céu), com neblina no horizonte. A luz da cena vem do sol de cada cenário.

- Motor: itens aditivos (`DrawItem.additive`, bordas suaves) para lasers, sabres, faíscas e
  lâmpadas; neblina por distância (`FrameScene.fog`); textura repetida (`uvScale`); modelos glTF
  também projetam sombra.
- `GameArt`: caixa de cantos arredondados, toro, cone, seta; texturas geradas no aparelho
  (madeira, grama, feltro, lona do ringue, papel de shoji, fachada de prédio, casca, metal).
- `Particles`: faíscas, estilhaços e gotas compartilhados por todos os jogos (o Tempo Parado os
  desacelera junto com o tempo).

| Jogo | Cenário |
|---|---|
| Sabre de Ritmo | plataforma de vidro escuro, pista neon, túnel de luz e lasers no ritmo, sabres com rastro, blocos que se partem com faíscas |
| Ninja das Frutas | dojo ao pôr do sol (piso, papel, lanternas), katanas, suco que respinga na parede, bombas com pavio |
| Ringue | arena escura, ringue elevado com cordas e postes, holofotes, torcida com flashes, luvas de verdade |
| Prancha no Céu | céu com nuvens, cidade com ruas, prédio de vidro, elevador de aço com painel, pássaros |
| Minigolfe | dia de sol, feltro, trilhos de madeira, árvores, pedras, montanhas na névoa |
| Tempo Parado | mundo branco com sombras duras, inimigos de vidro vermelho que estilhaçam, balas com rastro |
| Caçador de Drones | plataforma neon sobre cidade à noite, nebulosa, tiros de plasma, explosões, escudo de energia |
| Tênis de Mesa | sala de clube, mesa com linhas e rede, raquete de borracha e cabo, adversário |
| Pescaria | píer num lago ao pôr do sol (água que reflete), fogueira com brasas, vaga-lumes, vara que enverga |
| Pequeno Herói | toco musgoso numa floresta de árvores gigantes, névoa, raios de luz, vaga-lumes |

Nada disso foi testado num aparelho ainda: compila, os shaders foram validados num compilador
GLSL ES 3.00, mas desempenho e aparência reais precisam ser vistos no celular.
