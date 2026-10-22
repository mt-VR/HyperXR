# HyperXR PC (SteamVR)

Joga jogos do SteamVR no celular com o HyperXR:

- o celular é o headset, com 6DoF pelo ARCore;
- os Joy-Cons, ou as próprias mãos, viram os controles do Meta Quest 3;
- o PC renderiza o jogo e manda o vídeo pelo Wi-Fi.

## Usar

1. Extraia o zip inteiro numa pasta fixa. O programa `HyperXR PC.exe` e a pasta `hyperxr` precisam
   ficar juntos.
2. No celular, abra **HyperXR > SteamVR**. A tela mostra o IP do celular.
3. No PC, abra **HyperXR PC.exe**, digite o IP do celular e clique em
   **Conectar e iniciar o SteamVR**.
4. No celular, digite o IP do PC (aparece no programa do PC), toque em **Conectar** e coloque o
   celular no VR Box.

Na primeira conexão, o SteamVR reinicia uma vez sozinho para usar a tela e o campo de visão do seu
celular.

## Requisitos

- Windows 10 ou 11, com Steam e SteamVR instalados. Abra o SteamVR uma vez antes de usar.
- Uma placa de vídeo com codificador H.264: NVIDIA (NVENC), AMD (AMF/VCE) ou Intel (Quick Sync).
  Sem ela, o codificador por software funciona, mas fica lento.
- PC e celular na mesma rede, de preferência com o PC no cabo e o celular em Wi-Fi 5 GHz.
- Quem abre a conexão é o PC, então o firewall do Windows não precisa de regra nova. Se o PC não
  alcançar o celular, o celular também tenta se conectar ao PC (porta 9944).

## PC fraco (vídeo integrado Intel ou AMD)

O campo **Desempenho do PC** define quanto o PC renderiza. No **Automático**, o programa
reconhece vídeo integrado (por exemplo, Intel UHD 620 de notebook) e usa o perfil de PC fraco.

| Perfil | Resolução renderizada | Quadros do PC |
|---|---|---|
| PC fraco | 60% da do celular | 45 por segundo |
| PC muito fraco | 50% | 30 por segundo |
| PC médio | 80% | 60 por segundo |
| PC forte | 100% | os do celular |

A tela do celular continua na taxa dela (60 Hz ou mais). A cada atualização da tela, o celular
gira o último quadro do PC conforme a cabeça. Por isso virar a cabeça fica suave mesmo com o PC
mandando 30 ou 45 quadros por segundo; o que anda a 30 ou 45 são a animação do jogo e a posição.

Nos perfis fracos, o driver também:

- desliga o SteamVR Home, cujo ambiente sozinho já ocupa uma GPU integrada (o painel do SteamVR
  continua funcionando);
- desliga o Motion Smoothing do SteamVR;
- fixa o supersampling em 100%, para o SteamVR não aumentar a resolução por conta própria.

O codificador é o Quick Sync da própria Intel (H.264 ou HEVC). A espera pela GPU e o relógio de
vsync dormem em vez de girar em loop, o que libera um núcleo para o jogo num notebook de 4
núcleos.

Jogos pesados continuam pesados: uma Intel UHD 620 roda jogos VR simples, não os grandes.

## Controles (emulação do Quest 3 Touch)

| Joy-Con | Quest 3 |
|---|---|
| ZR / ZL | gatilho |
| R / L | grip |
| B ou A (botões de baixo e da direita) | A / X |
| X ou Y (botões de cima e da esquerda) | B / Y |
| analógico e clique | thumbstick |
| + / − | menu |
| Home / Captura | botão do sistema |

A posição e a orientação de cada controle vêm da mão que segura o Joy-Con, pelo hand tracking da
câmera do celular.

Sem Joy-Con, a própria mão vira o controle:

- **gatilho:** pinça;
- **grip:** fechar os outros dedos;
- **A/X:** punho fechado com o polegar por cima.

A curvatura dos dedos também é enviada ao PC.

## Compilar

```sh
./build.sh   # MinGW-w64 no Linux ou MSYS2; gera dist/HyperXR-PC.zip
```

## Licenças

- `openvr_driver.h`: Valve, BSD-3-Clause (`third_party/openvr/LICENSE`).
