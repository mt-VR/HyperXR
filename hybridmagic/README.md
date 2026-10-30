# HybridMagic PC

A placa de vídeo do PC rastreia as mãos para o HyperMagic no celular.

## Usar

1. Baixe `HybridMagic-PC-<versão>.zip` nos releases do GitHub e extraia a pasta inteira.
2. Abra `HybridMagic.exe` e deixe a janela aberta.
3. No celular, deixe **Ajustes > Mãos > HybridMagic** ligado (já vem ligado) e entre no modo VR,
   no mesmo Wi-Fi do PC.

Não precisa digitar IP nem mexer no firewall. O PC manda um aviso na rede (UDP 9955), o celular
responde e o PC abre a conexão com o celular (TCP 9956). Como a conexão sai do PC, o Firewall do
Windows deixa passar. A janela mostra o celular conectado, quantas mãos ele vê, os quadros por
segundo e o tempo de cada quadro.

Se o PC sair do ar, ficar lento ou o Wi-Fi engasgar, o celular volta na hora para o tracking
dele e tenta o PC de novo dois segundos depois. As mãos não param.

## O que o PC faz a mais

É o mesmo RTMPose do celular (OpenMMLab, Apache-2.0), com o trabalho que o celular não aguenta:

- **Detector em todo quadro.** O celular só procura mão quando perde uma. Aqui a busca roda em
  todo quadro, e de novo no centro da imagem com zoom de 2x, para achar mãos pequenas e longe.
- **Olhar espelhado.** Cada mão é analisada duas vezes, como vista e espelhada, e as duas
  respostas são combinadas: juntas mais firmes e menos dedos trocados.
- **Segunda passada.** Cada mão é recortada de novo em volta das juntas que a primeira passada
  achou, centralizada e do tamanho com que o modelo foi treinado.
- **Abaixo do pixel.** As juntas saem do pico suave da distribuição do modelo, não só do maior
  valor.

Um PC sem placa de vídeo boa fica mais lento que o celular espera (70 ms por quadro, Wi-Fi
incluído). Por isso o programa desliga esses extras um a um quando passa de 35 ms e religa quando
sobra tempo. A GPU é usada pelo DirectML (NVIDIA, AMD ou Intel); sem ela, roda na CPU.

## O que não faz

O PC não soma CPU e GPU com o celular, nem desenha os jogos. Ele faz uma tarefa pesada, as mãos,
e devolve o resultado. O resto continua no celular.

## Rodar pelo código

```
pip install -r requirements.txt          # Windows: requirements-windows.txt
python hybridmagic.py                    # ou: python hybridmagic.py --console
python test_engine.py                    # testa o motor nas fotos de mão do mmpose
```

Na primeira vez os modelos são baixados da OpenMMLab para `models/`. O formato das mensagens está
em `protocol.py`; o lado do celular fica em `app/src/main/java/com/hyperxr/hybrid/`.

## Licenças

- Modelos RTMDet-nano (mãos) e RTMPose-m (hand5): OpenMMLab, Apache-2.0.
- ONNX Runtime: MIT. OpenCV: Apache-2.0. NumPy: BSD.
