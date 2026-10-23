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
