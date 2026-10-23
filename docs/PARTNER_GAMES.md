# Jogos parceiros

Jogos de outros autores que o HyperXR pode oferecer **com a permissão deles**, do jeito que foram
publicados.

- O HyperXR baixa o APK original da página do próprio autor, com os mesmos passos do botão
  Download do itch.io.
- Ele confere o SHA-256 (tem que ser byte a byte a versão conhecida) e instala sem nenhuma
  alteração.
- Esses jogos nunca passam pelo "Corrigir APK".
- O APK não fica guardado neste repositório: a fonte é sempre a página do autor.
- O código e o jogo continuam sendo do autor (não são open source).
- Se o autor publicar outra versão, o hash muda e o HyperXR avisa em vez de instalar. Atualize o
  hash e o tamanho em `core/src/main/kotlin/com/hyperxr/xrapps/PartnerGames.kt`.

Fica em Jogos VR > Parceiros.

| Jogo | Autor | Permissão | Arquivo | SHA-256 |
| --- | --- | --- | --- | --- |
| [BETA] Gorilla Rush (`com.welligames.gorillarush` 1.0.0) | Wellidev (Welli), https://wellidev.itch.io/beta-gorilla-rush | Dada pelo autor no Discord: pode, desde que o jogo não seja modificado | `Gorilla Rush_1_1.apk`, 9.268.918 bytes | `4626f7f9eb7afbcb0c40420b6a79cd30322aefa3bb810b3344afab4512a2e980` |

O Gorilla Rush é um jogo em WebView (HTML/JS dentro do APK) para VR Box, que divide a tela
sozinho e usa a câmera. O HyperXR só instala e abre o jogo; o jogo cuida da imagem dele.
Não testado num aparelho.
