# Voz, espaços de trabalho e janela do mundo real

## Comandos de voz
Botão **Voz** na barra de tarefas. O reconhecimento é o do Android (`SpeechRecognizer`), e na
primeira vez o app pede a permissão do microfone. Enquanto escuta, o meio da barra mostra
"Ouvindo…". Depois mostra "Entendi: …" ou "Não entendi: …" por alguns segundos.

O texto passa por `core/.../voice/VoiceCommands.kt`, que tem testes em `VoiceCommandsTest`. O
texto é normalizado (minúsculas, sem acentos) e palavras de preenchimento como "o", "por favor"
e "pra" são ignoradas. Apps, ambientes e espaços são achados pelo nome mais próximo, então
"abre o youtubi" abre o YouTube.

| Diga | Faz |
|---|---|
| "abrir YouTube", "abre o Spotify" | abre o app |
| "pesquisar receita de bolo" | abre o navegador com a busca |
| "ambiente pôr do sol", "câmera", "escurecer" | troca o fundo |
| "modo dock", "modo new" | troca o modo do launcher |
| "recentralizar" | recentraliza a visão |
| "capturar", "tirar foto" | captura a tela |
| "fechar janelas", "esconder janelas", "mostrar janelas" | gerencia as janelas |
| "biblioteca" | abre ou fecha a biblioteca |
| "mundo real" | abre a janela do mundo real |
| "salvar espaço cinema", "abrir espaço cinema" | salva ou abre um espaço de trabalho |

## Espaços de trabalho
App **Espaços de trabalho** na biblioteca. Um espaço guarda as janelas abertas: abas do
navegador, apps Android e web apps, com largura e curvatura. Ao abrir um espaço, as janelas
atuais são fechadas e as do espaço são reabertas. Os espaços ficam em
`files/workspaces/<nome>.json`, no mesmo formato da restauração de sessão. O estado interno de
cada app (posição num vídeo, rascunhos) não é guardado.

## Janela do mundo real
App **Mundo real**, ou o comando de voz "mundo real". É um painel móvel com a imagem da câmera
que o hand tracking ou a realidade mista já usam. Serve para ver a mesa, o celular ou quem
entrou na sala sem sair do ambiente 360. Sem a câmera ligada, o painel explica como ligá-la.

# Assistente com IA no celular

App **Assistente** na biblioteca, ou o botão **Voz** da barra de tarefas, que agora fala com o
assistente.

- **Entrada:** a voz vira texto pelo reconhecimento de fala do Android/Google. Também dá para
  digitar no painel.
- **IA:** um modelo de linguagem pequeno roda no próprio celular, com MediaPipe LLM Inference e
  Qwen 2.5 da Alibaba (licença Apache 2.0). O modelo é baixado uma vez do Hugging Face
  (litert-community) pelo painel e depois funciona sem internet. Há duas opções: 1.5B (1,6 GB,
  entende melhor, precisa de ~6 GB de RAM) e 0.5B (550 MB, leve, erra mais). O painel indica a
  melhor para o celular.
- **O que a IA vê:** a cada pergunta ela recebe o estado do runtime:
  - modo XR, rastreamento e mãos;
  - fundo, modo do launcher e bateria;
  - janelas abertas, apps, ambientes e espaços salvos;
  - Joy-Cons conectados;
  - ajustes atuais.

  Também recebe os trechos do conhecimento sobre o HyperXR que combinam com a pergunta
  (`core/.../assistant/AssistantKnowledge.kt`).
- **O que a IA faz:** responde perguntas e executa ações:
  - abrir apps e pesquisar;
  - trocar fundo e modo;
  - recentralizar e capturar;
  - gerenciar janelas e espaços;
  - abrir o mundo real;
  - gravar a tela;
  - mudar ajustes (hand tracking, toque direto, fps, escala de render, IPD, FOV, volume).
- **Segurança contra erros do modelo** (`AssistantBrain`, com testes):
  - perguntas nunca executam ações;
  - fechar janelas, salvar ou abrir espaços, trocar o modo e mudar ajustes só acontecem se o
    pedido falar disso;
  - valores fora da faixa são limitados.
- **Comandos curtos** como "abre o YouTube" vão direto ao parser de palavras-chave, sem esperar o
  modelo. Sem modelo baixado, esses comandos continuam funcionando.
- **Resposta falada:** opção de ler as respostas em voz alta (TextToSpeech pt-BR).

As instruções foram testadas no PC com os mesmos modelos Qwen 2.5 (versão transformers), não no
celular.

# Compartilhar tela 16:9

App **Compartilhar tela** na biblioteca. Uma câmera de espectador renderiza a cena numa imagem
única em 16:9, sem a divisão em duas lentes e sem a distorção. A resolução pode ser 720p, 1080p
ou 1440p. Por padrão a câmera é estabilizada, com a rotação suavizada; sobre a câmera do celular
(MR) ela segue a cabeça exatamente, para a imagem continuar alinhada. Essa imagem só é
renderizada enquanto algo a usa, em até 30 fps.

- **Gravar vídeo:** MP4 H.264, com microfone opcional, salvo em `Filmes/HyperXR`. Também por
  voz: "gravar", "parar gravação".
- **Capturas:** a Captura agora sai em 16:9 nessa resolução, não mais a metade esquerda da tela.
- **Navegador:** a opção *Transmitir para o navegador* mostra o endereço
  `http://<ip-do-celular>:8090`. Qualquer navegador no mesmo Wi-Fi assiste em 720p (MJPEG).
- **Telas externas:** HDMI/USB-C, Miracast ou transmissão que ofereça segunda tela recebem a
  imagem 16:9 sozinhas.
- **Gravação externa em 16:9:** no Android 15 ou mais novo o sistema avisa quando outro app grava
  ou espelha a tela. Com a opção ligada (padrão), a tela do celular mostra a imagem 16:9 enquanto
  isso, então o gravador do sistema grava em 16:9. Durante a gravação o visor sai do modo lentes.
  No Android 14 ou mais antigo o app não tem como saber que está sendo gravado: use *Gravar
  vídeo*.
- **Limite:** o jogo Gato vs. Vovó tem um renderizador próprio e ainda não passa por isso.

## Ambientes 360 embutidos
Quatro panoramas vêm no APK e aparecem em Ambientes e no seletor de fundo:
- Rua roxa à noite
- Praça futurista
- Estúdio de criação VR
- Cobertura ao pôr do sol

Ficam em `assets/environments` e não podem ser excluídos.

## Luz que combina com o ambiente
Ao trocar de panorama, a cor média dele vira a luz ambiente e tinge a luz principal dos objetos
3D. Por exemplo: lilás na rua à noite, quente no pôr do sol. O cálculo está em
`AmbientMatch.lightFor`, com testes. Panoramas baixados ou importados também entram, porque a cor
média é medida ao carregar.

## Ambiente pelo horário
Em Ajustes > Launcher > **Ambiente pelo horário**, os panoramas embutidos seguem o relógio:

| Horário | Ambiente |
|---|---|
| 20h30–5h30 | Rua roxa à noite |
| 5h30–7h e 17h–19h | Cobertura ao pôr do sol |
| 7h–9h e 19h–20h30 | Praça futurista |
| 9h–17h | Estúdio de criação VR |

O ambiente não troca enquanto o fundo é a câmera.

## Menu de pinça (palma para cima)
Faça a pinça com a palma virada para cima (ou para o rosto) e o menu circular abre na hora, em
volta da pinça. Ainda segurando a pinça, mova os dedos até a opção e solte para escolher. Soltar
no meio cancela. O gesto antigo continua valendo: segurar a pinça 0,45 s longe de qualquer
janela.

As opções são as do menu antigo mais as novas:
- Voltar
- Início
- Teclado
- Captura
- Voz
- Recentralizar
- Biblioteca
- Janelas
- Fita métrica

Com a palma para baixo ou para frente, a pinça continua sendo o clique normal (`PalmMenuDetector.isPalmUp`, com testes).

## Menu na palma da mão (substituído)
A versão anterior abria um menu com a mão aberta virada para o rosto. Foi trocada pelo menu de
pinça acima. As opções eram:
- Voz
- Recentralizar
- Captura
- Biblioteca
- Janelas
- Fita métrica

Clique com a outra mão. Para o menu fechar, a mão precisa sair da pose por 0,3 s, então um
quadro ruim do rastreamento não o fecha.

A pose é medida em `PalmMenuDetector` (core, com testes):
- a normal da palma (produto vetorial entre os nós do indicador e do mindinho, espelhado na mão
  esquerda) aponta para os olhos a menos de 45°;
- pelo menos 3 pontas de dedo estão além da junta do meio;
- não há pinça.

As costas da mão, o punho fechado e uma mão longe não abrem o menu.

## Fita métrica com as mãos
Ligue a Fita métrica no menu da palma e faça a pinça com as duas mãos. Uma linha amarela liga as
duas pinças e mostra a distância ("34,5 cm", "1,27 m"). Depois de soltar, a medida fica na tela
por 4 s. A precisão depende do rastreamento das mãos (profundidade estimada pelo tamanho da mão),
então serve para medidas aproximadas.

## Emuladores
Veja [EMULATORS.md](EMULATORS.md). Inclui o controle para as mãos, que deixa segurar dois botões
ao mesmo tempo, um com cada mão, e também voltar no tempo, acelerar e o modo cinema.


## Navegador
- **Barra:** ganhou os ícones originais do Wolvic (MPL-2.0, veja `native/WOLVIC_ICONS.md`):
  - botões redondos de voltar, avançar, recarregar/parar e início;
  - endereço em pílula, com o ícone de busca ou de site inseguro;
  - favoritar e favoritos;
  - abas em chips com o botão de nova aba;
  - à direita, modo desktop, curvar, menor/maior, fechar aba e fechar janela.
- **Motor:** é o GeckoView, o mesmo do Wolvic. A página inicial agora é do próprio HyperXR
  (`assets/browser/home.html`), aberta pelo Gecko, e a busca usa o Google, o padrão do Wolvic.
  O DuckDuckGo saiu.

## Jogos VR open source
App **Jogos open source** (categoria jogos HyperXR):
- **Selecionados:** licença conferida no repositório de cada um.

  | Jogo | Licença | Observação |
  |---|---|---|
  | Quest DOOM | GPL-2.0 | traz o shareware |
  | QuestZDoom | GPL-3.0 | precisa de um WAD; o Freedoom é livre |
  | Quake3Quest | GPL-2.0 | |
  | Quake2Quest | GPL-2.0 | |
  | OpenMW VR | GPL-3.0 | |
  | Godot XR Tools | MIT | |
  | Hello Cardboard (SDK oficial) | Apache-2.0 | |

- **GitHub:** busca ao vivo no celular por tópicos OpenXR/Android e Cardboard. Só aparecem
  projetos com licença open source (GPL, MIT, Apache, BSD, MPL...); repositórios sem licença
  ficam de fora.
- **itch.io:** abre as listas de jogos Cardboard para Android; a licença está na página de cada
  jogo.
- Quando a última versão do projeto tem APK, ele é baixado e abre direto no **Corrigir APK**.
  Quando não tem, abre o código-fonte.
- Os jogos feitos para o Quest não foram testados no HyperXR.

## Corrigir APK: Daydream e 32 bits
- **Verificações de Daydream no código do app:** são trocadas por "sim" direto no bytecode
  (`DexPatcher`). Viram "sim" as chamadas a `DaydreamApi.isDaydreamReadyPlatform` e
  `DaydreamUtils.isDaydreamPhone` feitas pelo próprio app. O SDK de VR do Google continua vendo a
  verdade, então roda em modo Cardboard, que não precisa do Google VR Services.
  - A instrução substituta tem o mesmo tamanho, então desvios e blocos try não mudam. O checksum
    e a assinatura do DEX são recalculados; o `dexdump` do Android confere ("Checksum verified").
  - A tela "celular incompatível" do SDK já sumia com a troca da categoria DAYDREAM por
    CARDBOARD no manifesto, porque o SDK calcula "precisa de Daydream" a partir dela.
- **32 bits:** apps 32 bits (armeabi-v7a) de Daydream ou Cardboard rodam como estão nos celulares
  que executam código 32 bits. Num celular só 64 bits, o patcher explica que não dá e pede a
  versão arm64. Converter código nativo ARM de 32 para 64 bits não é possível com segurança.
- **Limite:** jogos Unity checam o Daydream pelo C#, com nomes montados em tempo de execução, e
  isso o patcher não alcança.


## Aura lilás nas mãos
As mãos agora aparecem só como um contorno lilás: uma linha fina e clara seguindo a silhueta da
mão, com um brilho lilás curto dos dois lados, nada por dentro, e aberta embaixo: a linha desce
um pouco além do punho e some.
O esqueleto e a aura preta antiga foram removidos; quem tinha um deles salvo passa para a aura
lilás automaticamente.

O desenho (`HandAura`) é uma passada por mão, sem textura intermediária:
- as juntas são projetadas num plano na profundidade da mão;
- um retângulo nesse plano é desenhado e o shader calcula a forma da mão como um campo de
  distância: a palma é um polígono (punho, base do polegar e os quatro nós dos dedos), cada dedo
  são segmentos arredondados e o antebraço é uma cápsula abaixo do punho;
- as partes se juntam com um "mínimo suave", então nós dos dedos e a pele entre polegar e
  indicador fluem sem emendas.

Em Ajustes > Mãos, "Intensidade da aura" regula a força.

## Timewarp e mãos no último instante
- **Timewarp rotacional:** depois que os dois olhos são desenhados, o giroscópio é lido de novo
  e previsto para o mesmo instante em que a luz sai da tela; a composição final (com a distorção
  da lente) re-aponta a imagem pela rotação desde o desenho. A latência de rotação que os olhos
  sentem passa a ser só a composição mais a varredura da tela, não o quadro inteiro. Giros
  grandes demais (recentralizar, salto de rastreamento) não são corrigidos. Desligável em
  Gráficos.
- **Mãos no último instante:** as mãos desenhadas são extrapoladas de novo logo antes do desenho,
  até o mesmo instante de fóton da cabeça (a interação continua usando a cópia do começo do
  quadro).
- **Câmera:** redução de ruído e nitidez no modo rápido do ISP (sem quadro extra de atraso).

Limites físicos: a latência nunca chega a 1 ms. A tela mostra um quadro novo a cada 8–16 ms
(120–60 Hz) e leva esse tempo para varrer de cima a baixo; a câmera expõe e lê cada imagem em
10–30 ms antes do hand tracking começar. A previsão esconde parte disso (o movimento parece em
dia enquanto é suave), mas não remove.

## Clique apontando e empurrando
Opção nas configurações ("Clicar apontando e empurrando"; o padrão é a pinça):
- aponte com o indicador (os outros dedos dobrados, o polegar livre, como uma "arminha");
- a ponta do dedo mira no que está embaixo dela, vista dos olhos (a bolinha aparece na ponta);
- um empurrão curto do dedo para a frente (~2 cm, ~3 cm sem Depth API) clica; a bolinha enche
  conforme o empurrão;
- segurando empurrado e movendo, arrasta (rolar páginas, mover janelas pela barra, selecionar);
- puxando o dedo de volta, solta.
O empurrão é medido como a distância da ponta do dedo aos olhos contra uma referência que segue
a mão devagar: mover o braço devagar ou andar não clica. Com a Depth API do ARCore a distância
é medida; sem ela é estimada pelo tamanho da mão e o empurrão precisa ser maior.

## Teclado na mesa
No teclado flutuante, o botão **Mesa** deita o teclado sobre uma mesa de verdade:
- **Com a Depth API:** os pontos de profundidade à frente, ao alcance das mãos, são procurados
  por uma superfície nivelada abaixo dos olhos (histograma de alturas, robusto a chão, parede e
  objetos em cima da mesa). Duas leituras seguidas que concordam colocam o teclado na mesa, à
  frente do usuário. Depois disso a altura continua acompanhando a mesa.
- **Sem Depth API (ou sem mesa encontrada):** toque a mesa com a ponta do indicador e segure
  parado por 1 segundo; o teclado é centrado onde o dedo tocou. "Mais alto"/"Mais baixo" ajustam
  a altura em 5 mm e "Recalibrar" procura de novo.
- **Detecção de toque (própria do HyperXR):** uma tecla só é digitada quando o dedo bate na
  mesa de verdade, não quando chega perto. A cada resultado da câmera são exigidos dois sinais
  físicos ao mesmo tempo: **contato** (com a Depth API, a profundidade medida na própria ponta do
  dedo está na mesa; sem ela, a ponta do dedo do modelo da mão, com margem menor e confirmação no
  quadro seguinte) e **impacto** (o dedo vinha descendo rápido e parou de repente, como acontece
  ao bater numa superfície dura; abaixar devagar ou pairar nunca faz isso). Além disso o dedo
  precisa ter descido pelo menos 1,2 cm e levantar de novo antes da próxima tecla, a mesma tecla
  não vale duas vezes em 140 ms e, se dois dedos da mesma mão encostam juntos, só o mais baixo
  conta. Em simulação com ruído: 0 teclas falsas em 10 s pairando a 2-3 cm, 20/20 batidas com
  Depth API e 18/20 sem.
- **Dedos:** escolha quais digitam no painel do teclado ou nas configurações (padrão: indicador e
  médio). O polegar, se ligado, digita só o espaço.
- **Retorno:** a tecla sob a ponta do dedo acende em lilás, a tecla digitada pisca, um clique
  curto toca, e o painel atrás do teclado mostra o texto digitado.
- **Integração:** digita no mesmo alvo do teclado flutuante (campos da web, apps Android, barra
  de endereço). "Flutuante" volta ao teclado no ar; X ou Voltar fecha.

Limites: precisão de ~1 cm do hand tracking de celular (por isso as teclas grandes); dedos
escondidos atrás da mão (vistos de cima pela câmera) podem não ser detectados; sem Depth API a
altura das pontas dos dedos é estimada e a calibração manual é necessária.

## Profundidade por IA (Depth Anything V2)
Para o teclado de mesa, além (ou no lugar) da Depth API do ARCore, o HyperXR usa uma rede de
profundidade monocular: **Depth Anything V2** (versão Small exportada para celular pela Qualcomm
AI Hub, licença MIT), rodando na NPU pelo NNAPI (CPU como último recurso).
- O modelo (~99 MB) não vai dentro do APK: é baixado uma vez, na primeira vez que o teclado de
  mesa é usado com a opção ligada, para o armazenamento privado do app.
- A rede devolve profundidade **relativa** (sem escala). Ela vira métrica com a própria mesa como
  régua: a cada quadro, os pixels da mesa em volta da mão (cuja distância real é conhecida pelo
  plano da mesa) ajustam `1/z = a·d + b`, com rejeição de pontos fora (objetos em cima da mesa).
  A ponta do dedo é lida no mapa (o ponto mais próximo de um 3x3, ou seja, o próprio dedo) e
  vira altura acima da mesa em centímetros.
- Uso: o detector de toque continua exigindo o "baque" do dedo; quando a IA está ativa, a decisão
  final é dela (topo da ponta do dedo a menos de 2,2 cm da mesa no quadro do toque ou seguinte).
  Sem resposta em 350 ms, o toque é descartado.
- Roda só enquanto o teclado está deitado numa mesa, um quadro por vez, no máximo ~16 por segundo.
- Comparada à do ARCore: nítida nas bordas de mãos e dedos, por quadro (sem a suavização no
  tempo do ARCore) e funciona em qualquer celular; em troca é mais pesada (34 ms num Snapdragon
  8 Gen 3, 72 ms num 8 Gen 1 pelos números da Qualcomm) e divide a NPU com o hand tracking.

## YouTube XR (nativo, sem página web)
App próprio do HyperXR para o YouTube, sem WebView, sem o site e sem o app do YouTube:
- **Dados:** NewPipe Extractor (GPL-3.0), compilado do código-fonte (`third_party/NewPipeExtractor`,
  módulo `:newpipe`), com um downloader próprio (HttpURLConnection) e a camada `XrYouTube`:
  categorias do próprio YouTube (Música, Jogos, Ao vivo, Filmes e trailers, Podcasts), busca com
  paginação, detalhes, vídeos relacionados e todas as qualidades (144p até 4K/8K, 60 fps).
- **Player:** Media3 ExoPlayer. Vídeo e áudio separados (como o YouTube entrega as qualidades
  altas) tocam juntos (MergingMediaSource); ao vivo por HLS. O vídeo vai direto do decodificador
  para uma textura (sem passar pela CPU) e aparece numa tela de cinema; "Ver em 360" coloca o vídeo
  ao seu redor (mono, 3D cima/baixo ou lado a lado).
- **Interface XR:** abas por categoria, busca pelo teclado XR, grade de miniaturas, player com
  barra de progresso, -10/+10 s, qualidade +/-, título, canal, visualizações, curtidas e uma coluna
  "A seguir" com os relacionados.
- **Sem conta:** sem login, então sem inscrições, histórico, comentários ou recomendações pessoais.

Licença: o NewPipe Extractor é GPL-3.0-or-later. O código do HyperXR continua MIT, mas um APK que
inclui o extrator é uma obra combinada e deve ser distribuído sob a GPL-3.0 (com o código-fonte).
O uso de clientes de terceiros pode contrariar os termos de serviço do YouTube.

## Punho para mover janelas
Com as mãos, só o **punho fechado** move janelas e painéis: aponte para a janela (o conteúdo ou a
barra), feche a mão e mova; estender o braço afasta a janela, recolher aproxima. A pinça só clica:
pinçar no espaço vazio ou na barra não arrasta mais nada. O punho nunca clica no conteúdo
(páginas, apps). Com o Joy-Con e o olhar, a barra continua arrastando como antes.
