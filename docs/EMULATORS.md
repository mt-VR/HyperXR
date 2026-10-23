# Emuladores embutidos

App **Emuladores** na biblioteca (categoria jogos HyperXR). Os núcleos são open source, do
projeto libretro. Foram compilados do código-fonte com o NDK pelo script
`tools/build_libretro_cores.sh`; os commits exatos estão em `native/libretro/CORES`. **Nenhum jogo
vem incluído.** O usuário usa as próprias ROMs.

| Sistema | Núcleo | Autores | Licença | Código-fonte |
|---|---|---|---|---|
| NES | QuickNES | Shay Green (blargg), kode54, equipe libretro | GPL-2.0 | https://github.com/libretro/QuickNES_Core (26bb785c9ded) |
| Game Boy / Color | Gambatte | Sindre Aamås (sinamas), equipe libretro | GPL-2.0 | https://github.com/libretro/gambatte-libretro (d9d6cd06382d) |
| Game Boy Advance | mGBA | Vicki Pfau (endrift) e colaboradores | MPL-2.0 | https://github.com/libretro/mgba (7a12d6d4b9ac) |

A API libretro (`native/libretro/include/libretro.h`) é MIT, do RetroArch team. Os créditos
aparecem no app: no XR em Emuladores > Créditos, e no app 2D em Ajustes > Emuladores embutidos.

Ficaram de fora núcleos com licença não comercial, como Snes9x, Genesis Plus GX e PicoDrive.

## Como funciona

- **Frontend próprio** (`app/src/main/cpp/retro_frontend.cpp`):
  - carrega o núcleo com `dlopen` e `RTLD_LOCAL`, um por vez, porque os callbacks da libretro são
    globais;
  - o vídeo vai direto para a `ANativeWindow` da janela XR, sem cópia pelo Java, convertendo
    0RGB1555, RGB565 ou XRGB8888 para RGBX;
  - o controle é uma máscara de bits do joypad;
  - suporta saves do jogo (SRAM) e save states.
- **Sessão** (`EmulatorSession`):
  - roda numa thread própria;
  - o áudio dita o ritmo: escritas bloqueantes num AudioTrack de baixa latência com cerca de 3
    quadros de buffer;
  - sem áudio, dorme até o próximo quadro;
  - a SRAM é gravada a cada 10 s e ao fechar.
- **Janela** (`EmulatorWindow`): tela grande, plana ou curva, com a barra Soltar controle,
  Reiniciar, Salvar estado, Carregar estado, Curvar, Menor, Maior e Fechar.
  - Clique na tela: o controle vai para o jogo (Joy-Cons, Pro Controller, qualquer gamepad ou
    teclado Bluetooth).
  - HOME devolve o controle ao HyperXR.
- **Controle para as mãos** (`EmulatorPad`): fica abaixo da tela. Segure um botão com a pinça ou
  o cursor. As duas mãos seguram um botão cada (andar com uma e pular com a outra), e deslizar
  passa para o botão vizinho.
- **ROMs:** Importar ROM abre o seletor do Android e aceita .nes, .gb, .gbc, .gba e .zip (tira a
  primeira ROM do zip). Com o acesso aos arquivos, as ROMs do celular também aparecem na lista.
- **Arquivos:** `files/emulators/{roms,saves,states,system}`. O mGBA usa a BIOS própria (HLE);
  uma `gba_bios.bin` em `system/` também funciona.

## Mapeamento

| Controle / teclado | Botão |
|---|---|
| A / X | A |
| B / Z | B |
| X / S | X |
| Y / A | Y |
| L, R / Q, W | L, R |
| ZL, ZR | L2, R2 |
| + / Enter | Start |
| − / Espaço | Select |
| Analógico, direcional, hat | Direções |

## Testado

- **No servidor:** o mesmo `retro_frontend.cpp`, compilado para Linux com stubs da API Android,
  rodou os três núcleos com ROMs de teste geradas na hora (não são jogos):
  - NES 240x224 a 60 fps com áudio a 48 kHz (800 amostras por quadro). A cor da tela muda com A
    apertado, o que confirma vídeo, controle e conversão de cor.
  - Game Boy 160x144 a 59,7 fps com áudio a 32768 Hz.
  - GBA 240x160 a 59,7 fps com áudio a 65536 Hz.
  - Save state funcionou nos três.
- **No celular:** não testado ainda (falta áudio real, toque, Joy-Con e desempenho).
