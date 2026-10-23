# HyperConnect

O HyperConnect faz um segundo celular virar o controle do HyperXR, por Bluetooth.

- **Orientação:** sensor de rotação "game" do Android (giroscópio + acelerômetro, sem
  magnetômetro, então não sofre com metal por perto).
- **Sensores brutos:** o giroscópio e o acelerômetro também são enviados.
- **Tela:**
  - touchpad: arrastar rola, tocar clica;
  - botões Voltar, Home, Menu, Grip e Gatilho.
- **Teclas de volume:** volume - é o gatilho, volume + é o grip.

O celular controle manda cerca de 100 pacotes por segundo (Bluetooth RFCOMM). O HyperXR responde
com vibrações: o controle vibra quando o ponteiro chega num botão e a cada toque.

## Como usar

1. No HyperXR: Ajustes > Mãos e controles > **Enviar o app HyperConnect**. Escolha como enviar
   (Nearby Share, Bluetooth, mensageiro) e instale no outro celular.
2. Pareie os dois celulares nas configurações de Bluetooth do Android (uma vez só).
3. Deixe "HyperConnect" ligado no HyperXR (já vem ligado). Abra o app HyperConnect no outro
   celular e escolha o celular do HyperXR na lista.
4. Segure o controle como um controle remoto: tela para cima, ponta apontando para a frente. Ao
   conectar, a direção do controle é alinhada com a da sua cabeça. Segurar **Home** realinha;
   um toque em Home recentraliza a visão.

## Como funciona

- Protocolo e modelo do controle ficam em `core/src/main/kotlin/com/hyperxr/connect/HyperConnect.kt`,
  com testes em `HyperConnectTest`.
- **Quadros:** `H` `C` versão tipo tamanho, little-endian. Tipos: estado, apresentação, vibração.
- **Modelo de braço:** o raio sai de onde fica a mão (ao lado e abaixo da cabeça, seguindo só a
  direção da cabeça), mais um antebraço curto na direção do controle.
- **Mão e posição:** a mão é escolhida pelo campo "canhoto" da apresentação. O controle é 3DoF
  (só rotação): não há posição da mão real.
- O app do controle é o módulo `hyperconnect/`, e o servidor no HyperXR é
  `app/.../connect/HyperConnectServer.kt`.

## Limitações

- **Não testado em aparelhos.** A latência real do Bluetooth (geralmente 10–30 ms) depende dos
  dois celulares.
- **Escolha do celular:** é preciso parear antes; o app só lista aparelhos já pareados.
- **Deriva lenta:** o sensor de rotação "game" pode derivar devagar na horizontal. Segure Home
  para realinhar.
