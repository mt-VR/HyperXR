# Minecraft no HyperMagic (QuestCraft e GorillaCraft)

Launcher próprio do HyperMagic para o **Minecraft Java Edition**, portado do
[Pojlib](https://github.com/QuestCraftPlusPlus/Pojlib) (ramo `QuestCraft-6.0.1`, commit
`0e22a02a`), a biblioteca de launcher do [QuestCraft](https://github.com/QuestCraftPlusPlus/QuestCraft).
Não é um APK do QuestCraft modificado e não é um jogo recriado: é o Minecraft Java de verdade,
com o Vivecraft do QuestCraft, rodando dentro do HyperMagic.

## Como funciona

- `pojlib/`, `org/lwjgl/glfw/`, `com/oracle/dalvik/`: código Java do Pojlib (LGPLv3), com as
  mudanças marcadas `HyperMagic` nos comentários.
- `src/main/cpp/`: as partes nativas do Pojlib (inicia a JVM, ponte GLFW/EGL, entrada,
  área de transferência), compiladas do código-fonte pelo CMake.
- `MinecraftActivity`: substitui o `UnityPlayerActivity` do QuestCraft (sem Unity). Roda no
  processo `:minecraft`; a JVM inicia nele, o Vivecraft abre a sessão OpenXR nessa activity e o
  runtime OpenXR do HyperMagic desenha o jogo. Quando o jogo fecha, só esse processo termina.
- `McRuntime`: baixa no celular, do commit exato do Pojlib, o que é binário (Java 22 do QuestCraft,
  nativos do LWJGL, MobileGlues, OpenAL, JNA, SQLite) e confere o SHA-256 de cada arquivo.
  Nenhum desses binários está neste repositório nem no APK.
- `McProfiles`: os perfis.
  - **QuestCraft**: Minecraft com Vivecraft, Fabric e os mods de desempenho da lista do QuestCraft.
  - **GorillaCraft**: o mesmo mais o [ViveMonkeCraft](https://github.com/laggyboi20-jpg/ViveMonkeCraft)
    (MIT), baixado do Modrinth com o hash conferido, e uma configuração para VR Box: sem pernas
    no corpo, sem prancha nem teleporte, impulso máximo menor para não sair voando, mãos maiores e
    limiar de velocidade mais alto contra o tremor do rastreamento pela câmera.
- `pojlib/account/LoginHelper`: login Microsoft pelo código de dispositivo (microsoft.com/link),
  sem a biblioteca msal4j. Usa o mesmo aplicativo Azure do QuestCraft, o que o Pojlib usa.

## Correções feitas no código portado

- O cliente do Minecraft era apagado e baixado de novo quando o hash **batia** (condição invertida).
- Leitura de assets e de texto podia vir cortada (`available()` + uma leitura) ou com acentos
  quebrados (decodificação em pedaços de 512 bytes).
- Streams que não eram fechados (`GsonUtils`, `FileUtil`).
- A instalação de uma instância falhava em silêncio e deixava um classpath `null`; agora o erro
  chega ao launcher.
- `hasExtraProject` com lista vazia dava NullPointerException.
- O teste de conexão só aceitava Wi-Fi e não tinha timeout.
- A memória do Java fica em no máximo metade da RAM do celular.

## Créditos

QuestCraft e Pojlib (QuestCraftPlusPlus, LGPLv3), PojavLauncher (base do Pojlib), Vivecraft,
LWJGL, MobileGlues, OpenAL Soft, Fabric. GorillaCraft: ViveMonkeCraft de laggyboi20-jpg (MIT).
Minecraft é da Mojang/Microsoft; é preciso ter o jogo na conta Microsoft.
