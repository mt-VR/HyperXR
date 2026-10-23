# libretro

- `include/libretro.h` — API libretro, MIT (RetroArch team), copiado do QuickNES_Core.
- `CORES` — núcleos embutidos, com URL e commit exato.
- Os binários ficam em `app/src/main/jniLibs/<abi>/libcore_*.so`; refaça com
  `tools/build_libretro_cores.sh`.

Licenças dos núcleos: QuickNES e Gambatte são GPL-2.0, mGBA é MPL-2.0. O código-fonte
correspondente está nos repositórios e commits listados em `CORES`. Detalhes em
`docs/EMULATORS.md`.
