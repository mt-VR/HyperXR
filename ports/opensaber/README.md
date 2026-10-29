# Open Saber para o HyperMagic

Port de verdade do **Open Saber**, não um remake: o código do jogo, sem mudanças na jogabilidade,
compilado para celulares com o runtime OpenXR do HyperMagic.

- **Jogo:** [Open Saber](https://github.com/leandrodreamer/BeepSaber/tree/godot-4-port), de
  leandrodreamer: port para Godot 4.3 e OpenXR do Beep Saber de NeoSpark314 (Holger Dammertz).
  Licença MIT (`LICENSE-OpenSaber`). Commit usado: `64200ad3572b634ae57159e234e87b5e3e5d96eb`.
- **Motor:** Godot 4.3-stable (MIT), o editor e os templates de exportação oficiais.

## O que muda (tudo em `hypermagic.patch`)

1. **Loader OpenXR da Khronos no lugar do da Meta.** O export original usa o plugin da Meta, cujo
   loader só roda num Quest. Aqui um plugin de exportação do próprio projeto
   (`addons/hypermagic_export`) põe o `openxr_loader_for_android` da Khronos (Maven), que acha o
   runtime pelo broker OpenXR: num celular, o runtime do HyperMagic.
2. **Categoria OpenXR no lançador** (`org.khronos.openxr.intent.category.IMMERSIVE_HMD`), para o
   HyperMagic achar e abrir o jogo no modo VR.
3. **Pacote próprio** (`com.hypermagic.opensaber`) e assinatura com a chave do HyperMagic.
4. **Sem a música de demonstração.** A faixa de exemplo do original é de terceiros e não tem
   licença para ser redistribuída num app. As músicas vêm do BeatSaver, pelo menu do próprio jogo.
   A capa padrão do painel de recordes passou a ser o logo do jogo.

## Compilar

```bash
ports/opensaber/build.sh [pasta-de-trabalho]   # -> ports/opensaber/dist/OpenSaber-0.5.0-arm64.apk
```

O script:
- baixa o Godot 4.3;
- extrai só os templates de Android do pacote de templates (`remote_zip.py`, por leitura parcial);
- baixa o JDK 17 que o build do Godot exige;
- aplica o patch e exporta.

O workflow `.github/workflows/opensaber.yml` faz o mesmo e publica o APK no release
`opensaber-v0.5.0`, de onde a Loja do HyperMagic baixa.

## Estado

O APK compila, tem o loader da Khronos e a categoria OpenXR, e foi conferido com `aapt2` e
`apksigner`. Ainda não foi jogado num celular com o runtime do HyperMagic. Os controles esperados
são dois controles com pose (sabres): com Joy-Cons, controle por cor ou HyperConnect.
