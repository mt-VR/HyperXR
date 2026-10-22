# App-to-World

Apps Android viram objetos no espaço, não só retângulos. Abra **App-to-World** na Biblioteca
(Sistema) para escolher os objetos e ligar o modo **Organizar** (segure um objeto e arraste).

## Adaptadores que funcionam com qualquer app (sem mudar o app)
| Objeto | De onde vem | Interação |
|---|---|---|
| Caixa de som com a capa do álbum | sessão de mídia do app que está tocando (Spotify, YouTube Music, podcasts...) | toque à esquerda/meio/direita: anterior, tocar/pausar, próxima |
| Bandeja de cartas | notificações dos apps | toque: abre as cartas com os botões do próprio app, resposta rápida pelo teclado XR, dispensar |
| Estante de livros | arquivos recentes (Download, Documentos, Câmera, Imagens, Vídeos, Música) | toque num livro: abre no visualizador; segure: leva o livro e solte num portal ou objeto |
| Portal de app | qualquer app instalado | toque: abre o app; solte um arquivo: compartilha com ele (ACTION_SEND) |

Música e cartas precisam do **acesso às notificações** (o painel abre a tela do Android). O som
continua saindo do app de música: o Android não deixa outro app capturar esse áudio, então a caixa
de som é o controle e a representação do player, não a fonte do som.

## API para apps compatíveis
Um app declara objetos e ações num JSON; o HyperXR monta os objetos com as próprias formas, chama
as ações com intents **para o mesmo pacote** e lê o estado de um provider do app. Nada do app roda
dentro do HyperXR.

Manifesto do app:
```xml
<receiver android:name=".SpatialActions" android:exported="true">
    <intent-filter><action android:name="com.hyperxr.action.SPATIAL" /></intent-filter>
    <meta-data android:name="com.hyperxr.spatial" android:resource="@raw/hyperxr_spatial" />
</receiver>
```

`res/raw/hyperxr_spatial.json`:
```json
{
  "version": 1,
  "objects": [
    {
      "id": "lamp", "label": "Luz da sala", "model": "lamp", "color": "#FFB040", "size": 0.3,
      "state": "content://com.exemplo.casa.spatial/lamp",
      "actions": [
        { "id": "toggle", "label": "Ligar/desligar", "gesture": "pinch",
          "intent": { "type": "broadcast", "action": "com.exemplo.casa.TOGGLE", "extras": { "room": "sala" } } },
        { "id": "photo", "label": "Mostrar foto", "gesture": "drop", "accepts": "image/*",
          "intent": { "type": "activity", "action": "android.intent.action.SEND", "component": ".ShowActivity" } }
      ]
    }
  ]
}
```

- `model`: box, sphere, cylinder, speaker, shelf, lamp, plant, screen, pedestal.
- `gesture`: `pinch` (toque), `long_pinch` (segurar), `drop` (soltar um arquivo; exige `accepts`, um MIME como `image/*`).
- `intent.type`: `broadcast` (padrão) ou `activity`. O HyperXR sempre chama `setPackage(<seu pacote>)`;
  `component` só pode ser do próprio pacote. Extras viram strings; `com.hyperxr.extra.OBJECT` traz o id
  do objeto. Em `drop` o arquivo chega em `EXTRA_STREAM` com permissão de leitura.
- `state` (opcional): provider do app consultado a cada 2 s; linhas com colunas `key` e `value`.
  `on` = `1`/`true` acende lâmpada/tela; `text` aparece no rótulo.
- Limites: 8 objetos, 6 ações por objeto, ids `[a-z][a-z0-9_]*`. Manifestos inválidos são ignorados
  (o motivo vai para as Mensagens do sistema). Validação em `core/.../appworld/SpatialManifest.kt`, com testes.
