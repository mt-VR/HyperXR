# Godot XR Tools Demo para o HyperMagic

Port do demo oficial do **Godot XR Tools**: o código do demo, sem mudanças na jogabilidade,
compilado para celulares com o runtime OpenXR do HyperMagic.

- **Jogo:** [Godot XR Tools](https://github.com/GodotVR/godot-xr-tools), de Bastiaan Olij e
  colaboradores, licença MIT (`LICENSE` do repositório). Commit usado:
  `6c6189bdfcdf855052ef1aeb9f3e0ec4c7a7eb60`. O repositório é também um demo jogável: um hub de
  mundinhos para andar, escalar, pegar e jogar objetos, atirar e voar.
- **Motor:** Godot 4.4.1-stable (MIT), o editor e os templates de exportação oficiais. O projeto
  já usa o renderizador Compatibilidade (OpenGL ES), o que o runtime do HyperMagic oferece.

## O que muda (tudo feito no `build.sh`, à vista)

1. **Loader OpenXR da Khronos.** O plugin de exportação do HyperMagic (`ports/common/hypermagic_export`)
   põe o `openxr_loader_for_android` da Khronos, que acha o runtime pelo broker OpenXR, e a categoria
   OpenXR no lançador. O preset "Android Khronos" do demo vira "Android (HyperMagic)" sem o plugin
   de fabricante.
2. **Pacote próprio** (`com.hypermagic.xrtoolsdemo`) e assinatura com a chave do HyperMagic.
3. **Sem o singleton GDNative do Godot 3** no `project.godot` (sobra antiga, sem uso no Godot 4).

## Compilar

```bash
ports/xrtools/build.sh [pasta-de-trabalho]   # -> ports/xrtools/dist/XRToolsDemo-4.4.0-arm64.apk
```

O workflow `.github/workflows/xrtools.yml` faz o mesmo e publica o APK no release
`xrtools-v4.4.0`, de onde a aba Importar e jogar do HyperMagic baixa.

## Estado

Compilado aqui sem erros; não testado num celular.
