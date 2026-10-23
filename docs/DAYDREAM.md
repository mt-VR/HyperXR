# HyperXR Daydream

Apps Daydream e Cardboard feitos com o Google VR SDK (GVR) procuram o pacote
`com.google.vr.vrcore` (Google VR Services). Ele não está mais na Play Store e não roda em
celulares que não são Daydream-ready. O **HyperXR Daydream** é um APK pequeno, gerado pelo
módulo `vrcore/` e embutido no HyperXR. Ele ocupa esse nome de pacote e oferece duas coisas:

1. **O serviço do controle Daydream** (`com.google.vr.vrcore.controller.BIND`). Ele fala o mesmo
   protocolo que o SDK usa: transações de `IControllerService` e `IControllerListener`, mais os
   pacotes `ControllerEventPacket2` com orientação, giroscópio, acelerômetro, touchpad e botões.
   Esse protocolo foi descrito lendo as classes do cliente no SDK 1.200; nenhum código do Google foi
   copiado. Veja `core/src/main/kotlin/com/hyperxr/daydream/`.
2. **O provedor de ajustes de VR** (`com.google.vr.vrcore.settings`). Ele entrega ao app os
   parâmetros do visor (lentes do VR Box) iguais aos do HyperXR. O SDK só aceita esse provedor
   de pacotes que começam com `com.google.`; é por isso que o APK usa esse nome.

As outras partes do Google VR Services (carregar a biblioteca GVR pelo VrCore, o serviço do SDK,
o DaydreamManager) conferem a assinatura do Google. Com o HyperXR Daydream essas verificações
falham, e o SDK usa o próprio código, como num celular sem VR Services.

## Instalação

Ajustes > Mãos e controles > **Instalar HyperXR Daydream**. Se o Shizuku estiver ativo, a
instalação acontece direto; senão, o instalador do sistema pede confirmação. Se o Google VR
Services real estiver instalado, desinstale-o antes, porque o nome do pacote é o mesmo.

Depois, abra o app "HyperXR Daydream" uma vez e toque em **Permitir acesso ao controle
(Shizuku)**.

## De onde vem o controle

| Fonte | Orientação | Botões e touchpad |
| --- | --- | --- |
| Joy-Con / gamepad com o sensor exposto pelo kernel (`hid-nintendo`, `hid-playstation`) | giroscópio do controle, com correção de inclinação pela gravidade | sim |
| Joy-Con / gamepad sem o sensor | segue a cabeça (sensor de rotação do celular) | sim |
| Nenhum controle ou sem Shizuku | segue a cabeça | não |

Mapeamento: stick = touchpad (tocar fora da zona morta), A/B do Joy-Con ou A do gamepad = clique,
X/Y = app, ZR/ZL = gatilho, R/L = grip, + / - = volume, HOME = home. Segurar HOME por 0,8 s
recentraliza (o app recebe `onControllerRecentered`).

Os eixos do giroscópio dependem de como o driver do kernel orienta o sensor. Se o controle girar
no sentido errado, troque o conjunto de eixos na tela do HyperXR Daydream; o teste ao vivo mostra
a direção.

## Limitações

- Nada disso foi testado num aparelho: o protocolo, o leitor de `/dev/input` e o modelo do
  controle têm testes no computador (`DaydreamTest`), mas não há celular aqui.
- O Shizuku lê o Joy-Con direto de `/dev/input`, então o HyperXR Daydream não depende do app que
  está na frente. Sem Shizuku não há botões.
- Vibração (`request`) é aceita e ignorada.
- Apps de SDKs muito antigos que não conhecem `ControllerEventPacket2` recebem o pacote antigo
  automaticamente; versões do protocolo diferentes da 1.x não foram verificadas.
