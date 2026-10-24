# Controles

O HyperXR aceita qualquer controle que o Android enxergue. Ele detecta o tipo sozinho
(`GamepadProfiles`) e usa sempre a mesma lógica:

| Tipo | Como é reconhecido |
|---|---|
| Nintendo: Joy-Con (esquerdo, direito, em par) e Pro Controller | fabricante 0x057E ou nome |
| PlayStation: DualShock 4 e DualSense | fabricante Sony 0x054C, "Wireless Controller", "DualSense" |
| Xbox | fabricante Microsoft 0x045E ou "Xbox" |
| 8BitDo | fabricante 0x2DC8 ou "8BitDo" |
| Qualquer outro gamepad Bluetooth ou USB | qualquer dispositivo de jogo; botões numerados (BUTTON_1..16) dos controles baratos também |
| Controle do VR Box e outros controles remotos | em modo jogo (gamepad), teclado/mídia (setas, Enter, volume, play) ou mouse (o analógico move o mouse) |

## Botões

São iguais em todos os controles:

| Ação | Botão |
|---|---|
| Clicar / selecionar | A, ou X (Cross) no PlayStation |
| Voltar / fechar | B, ou Círculo no PlayStation |
| Mover o cursor | analógico direito, ou o direcional quando o analógico está parado, ou o mouse no controle do VR Box |
| Rolar | R ou ZR + analógico; nos controles com dois analógicos, também o esquerdo |
| Gatilhos L2/R2 | analógicos; nos drivers antigos do DualShock, lidos dos eixos RX/RY |

**Controle do VR Box:** o botão "Voltar" dele é um botão do HyperXR (B) e não fecha o app.
Quando o Android mostra o controle como dois dispositivos (teclado e mouse), os dois viram um
controle só.

## Para onde o controle aponta

Vem sempre da mão rastreada: um raio do ombro passando pela mão, com posição 6DoF e sem drift.
O giroscópio do controle nunca é usado, mesmo quando ele tem um, porque é instável e acumula
erro.

## Testes e limites

**Testes:** `GamepadProfilesTest` cobre a classificação e os botões. Teclados de verdade (os
que servem para digitar), os botões do próprio celular e mouses comuns nunca são confundidos com
controle.

**Limite:** não testado com controles reais. Cada fabricante e cada versão do Android pode mapear
os botões de um jeito. Se algum botão vier trocado, diga qual controle é.
