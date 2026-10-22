# SteamVR no HyperXR

O celular vira o headset de um PC com Windows rodando o SteamVR. O PC renderiza o jogo, codifica
em H.264 e manda pelo Wi-Fi. O celular mostra a imagem nas lentes e manda de volta a cabeça (6DoF
pelo ARCore), as mãos e os Joy-Cons.

## Usar (só o IP nos dois)

1. **PC:** compile ou baixe `HyperXR-PC.zip` (`pc/build.sh`) e extraia a pasta inteira.
2. **PC:** abra `HyperXR PC.exe`, digite o IP do celular e clique em
   **Conectar e iniciar o SteamVR**. O programa registra o driver no SteamVR (`vrpathreg`) e abre
   o SteamVR.
3. **Celular:** na tela inicial do HyperXR, no card **SteamVR**, digite o IP do PC, toque em
   **Conectar** e coloque o celular no VR Box.
4. Na primeira conexão o SteamVR reinicia sozinho uma vez, para usar a resolução e o campo de
   visão do seu celular.

## Como funciona

- **Conexão:** uma conexão TCP na porta 9944, com o protocolo em
  `core/.../steamvr/HxvrProtocol.kt` espelhado em `pc/common/hxvr_protocol.h`.
  - O PC liga para o celular, então o firewall do Windows não pede regra.
  - O celular também tenta ligar para o PC.
  - Se as duas conexões abrirem, as duas pontas ficam com a que o PC abriu.
- **Headset:** o driver (`pc/driver/driver.cpp`) é um headset com display virtual
  (`IVRVirtualDisplay`).
  - O SteamVR entrega cada quadro pronto, sem distorção, lado a lado.
  - O driver converte a imagem para NV12 na GPU (processador de vídeo D3D11).
  - O codificador H.264 da GPU (Media Foundation: NVENC, AMF ou Quick Sync) comprime a imagem com
    baixa latência. Sem codificador de GPU, usa o software da Microsoft, que é lento.
- **Rastreamento:** a posição vem do ARCore. Recentralizar (Joy-Con Home) põe você na origem do
  SteamVR, de frente, com os olhos a 1,65 m do chão.
- **Controles:** dois Quest 3 Touch emulados.
  - Tipo `oculus_touch`, perfil de entrada `{oculus}/input/touch_profile.json`, nome
    "Meta Quest 3 (… Controller)". Assim os jogos usam as configurações de Touch.
  - Joy-Con: posição e orientação vêm da mão que o segura.
  - Sem Joy-Con: a mão é o controle (pinça = gatilho, fechar a mão = grip, punho com o polegar por
    cima = A/X).
  - A vibração dos jogos vai para o Joy-Con daquela mão (Android 12+).
- **Reprojeção:** o celular mostra o último quadro corrigido pela rotação da cabeça desde que o PC
  o renderizou. Virar a cabeça continua suave mesmo com a latência do Wi-Fi.

## Desempenho em celulares simples

O card SteamVR da tela inicial tem quatro perfis: Automático, Leve, Equilibrado e Qualidade. O
Automático escolhe pelo celular:

- **Leve:** até 4 GB de RAM ou chips como Exynos 7904/850/9611, Helio G/P e Snapdragon 4xx/6xx.
- **Equilibrado:** até 6 GB.
- **Qualidade:** acima disso.

Para manter fluido num celular fraco:

- **Resolução:** cabe no decodificador de hardware do celular. O HyperXR pergunta ao próprio
  decodificador e reduz em passos de 10% até ele aceitar, mantendo o formato da tela. No Leve, o
  limite é 1920x1080 a 60 Hz.
- **HEVC:** se o celular decodifica HEVC, o PC manda HEVC, que tem a mesma qualidade com cerca de
  metade do bitrate. Sem codificador HEVC na placa de vídeo, o PC volta para H.264 e avisa o
  celular.
- **Bitrate adaptativo no PC:** se o envio pelo Wi-Fi começa a travar, o bitrate cai 15% na
  hora. Com a rede folgada, ele sobe 5% a cada 2 s, até o limite do perfil (25 Mb/s no Leve) e do
  programa do PC.
- **Uma passada só na GPU do celular:** reprojeção e correção da lente saem direto do vídeo
  decodificado para a tela, sem as texturas de cada olho. No Leve, a correção cromática fica
  desligada (1 leitura por pixel em vez de 3).
- **Decodificador:**
  - modo de baixa latência do Android e dos fabricantes (Qualcomm, Exynos e MediaTek);
  - só decodificador de hardware;
  - se chegam dois quadros juntos, o mais antigo é descartado.
- **Celular sem trabalho extra:**
  - sem detecção de planos no ARCore;
  - menor imagem de câmera a 30 fps;
  - sem profundidade nem limite de área (boundary);
  - mãos sem desenho e a 12 Hz no Leve (20 no Equilibrado, 30 no Qualidade);
  - modo de desempenho sustentado ligado.

## PC fraco

Veja os perfis de desempenho do PC em `pc/README.md`. O perfil de PC fraco renderiza a 60% da
resolução e a 45 fps, desliga o SteamVR Home e é escolhido sozinho quando a placa de vídeo é
integrada. O celular reprojeta cada quadro para a taxa da própria tela.

## Limitações conhecidas

- **Nada disso foi testado com SteamVR de verdade.** O driver e o programa foram compilados no
  Linux com MinGW. Nenhum dos dois rodou no Windows.
- **Placa de vídeo:** o PC precisa abrir a imagem do SteamVR na mesma placa de vídeo. Em notebook
  com duas GPUs, rode o SteamVR na dedicada.
- **Modelo dos controles:** os jogos mostram o modelo do Quest 2 (o SteamVR não traz o modelo 3D
  do Quest 3), mas os botões e as configurações são os do Touch.
- **Dedos:** ainda não são enviados como esqueleto (`/input/skeleton`). Os jogos recebem os
  botões, o gatilho e o grip, e montam a mão a partir deles.
- **Atraso da posição:** o ARCore atualiza a posição na velocidade da câmera (30 Hz). A rotação
  é reprojetada no celular, mas a posição pode parecer um pouco atrasada.
