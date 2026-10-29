# HyperXR PC (SteamVR)

Joga jogos do SteamVR no celular com o HyperXR:

- o celular é o headset, com 6DoF pelo ARCore;
- os Joy-Cons, ou as próprias mãos, viram os controles do Meta Quest 3;
- o PC renderiza o jogo e manda o vídeo pelo Wi-Fi.

## Usar

1. Extraia o zip inteiro numa pasta fixa. O programa `HyperXR PC.exe` e a pasta `hyperxr` precisam
   ficar juntos.
2. No celular, abra **HyperXR > SteamVR**, no mesmo Wi-Fi do PC.
3. No PC, abra **HyperXR PC.exe** e clique em **Conectar e iniciar o SteamVR**. Não precisa
   digitar IP: o PC manda um aviso na rede (UDP 9945), o celular responde e o driver liga para o
   celular. Os IPs digitados continuam valendo, se preferir.
4. Coloque o celular no VR Box.

Na primeira conexão, o SteamVR reinicia uma vez sozinho para usar a tela e o campo de visão do seu
celular.

### Se ficar em "Procurando"

O celular agora diz o motivo:

- **nada ouve na porta 9944**: o SteamVR está fechado ou o driver não carregou. O programa do PC
  avisa quando o SteamVR está aberto sem o driver; o SteamVR desativa drivers depois de um erro, e
  eles voltam em SteamVR > Configurações > Inicialização/Desligamento > Gerenciar complementos.
- **sem resposta / firewall**: clique em **Liberar no firewall** no programa do PC (pede
  administrador uma vez). Redes marcadas como "Pública" no Windows bloqueiam conexões de entrada.
- **não é alcançável**: celular e PC estão em redes diferentes, ou o roteador isola os clientes
  (comum em Wi-Fi de visitante). Um cabo de rede no PC no mesmo roteador também serve.

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

## Por que o SteamVR não funcionava (corrigido na 0.3.2)

- **Ordem dos argumentos entre MSVC e MinGW.** O SteamVR é compilado com MSVC, que chama uma
  função membro que retorna uma struct com o `this` em RCX e o buffer de retorno em RDX; o GCC
  (MinGW) espera o contrário. Em `GetPose()` e `ComputeDistortion()` o driver escrevia o pose por
  cima do próprio objeto: o SteamVR caía ou o headset nunca rastreava. Agora o
  `openvr_driver.h` declara esses dois métodos, no MinGW, com o buffer como argumento comum, o que
  bate com o MSVC (conferido no assembly gerado).
- **Notebook com duas placas de vídeo.** O driver abria o Direct3D na primeira placa e o SteamVR
  renderizava na outra, e a imagem não abria. Agora o driver escolhe a placa de alto desempenho e
  avisa o SteamVR para renderizar nela (`Prop_GraphicsAdapterLuid_Uint64`).
- **Quadros-chave sem SPS/PPS.** Alguns codificadores da GPU só põem os parâmetros do vídeo no tipo
  de mídia; sem eles o celular não decodifica nada. Agora todo quadro-chave sai com eles.
- **COM** inicializado nas threads do Media Foundation.
- **Vídeo preto.** O SteamVR entrega cada quadro numa textura compartilhada protegida por um
  *keyed mutex*; lida sem pegar o mutex, ela vem preta. O driver agora pega o mutex antes de
  copiar, copia no mesmo tipo de formato do SteamVR (copiar entre famílias de formato não faz
  nada) e mostra no programa do PC o formato e o brilho da imagem que recebe.
- **Conexão:** o PC e o celular se acham pela rede (UDP 9945); o programa do PC reinicia o SteamVR
  sozinho quando o driver pede (o pedido do próprio SteamVR espera um clique).
- **Protocolo conferido dos dois lados:** `pc/tests/protocol_test.cpp` lê os bytes que o celular
  gera (`pc/tests/golden`, escritos pelo teste `HxvrGoldenTest` do core) e escreve os que o
  celular lê. Roda no workflow de release, que agora também publica o `HyperMagic-PC-<versão>.zip`.

## Licenças

- `openvr_driver.h`: Valve, BSD-3-Clause (`third_party/openvr/LICENSE`).
