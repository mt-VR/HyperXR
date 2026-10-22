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
