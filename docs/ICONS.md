# Ícones

Todos os ícones de apps são **arte original do autor do HyperXR** (fornecida em
`branding/icons-source/hyperxr_icon_sheet.jpg`; inspirados em launchers XR, sem afiliação à Meta).
Nenhum ícone foi desenhado pelo assistente, e nenhum emoji ou caractere é usado no lugar de ícone.

## Biblioteca central

- Camadas: `app/src/main/res/drawable-nodpi/ic_app_<nome>_bg.png` e `ic_app_<nome>_fg.png`.
- Mapeamento app → ícone: `IconLibrary` em `core/…/launcher/AppCatalog.kt`.
- Composição (máscara arredondada, bg + fg): `IconFactory` em `app/…/rendering/Images.kt`.
- Trocar um ícone = substituir os dois PNGs com o mesmo nome.

As camadas foram extraídas da folha enviada (blocos de 108 px). A camada `fg` teve o fundo cinza do
explorador de arquivos removido por preenchimento a partir da borda + recuperação de alfa nas bordas.
**Pendência:** substituir pelos PNGs originais em resolução cheia quando disponíveis.

| Ícone | Uso no HyperXR |
|---|---|
| browser | Navegador |
| calculator | Calculadora (app Window em Lua) |
| calendar | Calendário (app Window em Lua) |
| camera_share | Captura |
| clock | Relógio (app Window em Lua) |
| facebook | Facebook (web) |
| files | Arquivos |
| gallery | Galeria |
| help_center | Central de ajuda |
| instagram | Instagram (web) |
| remote_desktop | Apps XR, Área de trabalho remota (indisponível) e ícone do app HyperXR (é um headset) |
| settings | Configurações / ajustes rápidos |
| store | HyperXR Environments |
| system_messenger | Mensagens do sistema |
| tv | TV (hub de vídeo) |
| whatsapp | WhatsApp Web |

## Apps sem ícone do autor: bibliotecas abertas de ícones

Para os apps que não têm ícone na folha do autor, os símbolos vêm de bibliotecas de ícones
estabelecidas (não foram desenhados pelo assistente), brancos sobre um degradê no estilo da folha:
- apps do HyperXR: **Material Symbols** do Google (Rounded, preenchido), licença Apache-2.0;
- marcas: o logo oficial pelo **Simple Icons** (CC0) — YouTube, Twitch, TikTok.

Gerados por `tools/make_icons.py` (SVG → PNG 216 px, camadas `bg`/`fg` como as do autor).

| Ícone | Símbolo | App |
|---|---|---|
| importapk | build (Material) | Corrigir APK |
| opengames | sports_esports (Material) | Jogos open source |
| youtube | YouTube (Simple Icons) | YouTube XR, YouTube (web) |
| emulators | videogame_asset (Material) | Emuladores |
| androidapps | android (Material) | Apps Android |
| handlab | back_hand (Material) | Hand Physics Lab |
| ocean | tsunami (Material) | Oceano (blocos do menu: rainy, thunderstorm, waves, layers, view_in_ar, light_mode, volume_up, arrow_downward, undo) |
| appworld | view_in_ar (Material) | App-to-World |
| assistant | auto_awesome (Material) | Assistente |
| screenshare | screen_share (Material) | Compartilhar tela |
| workspaces | dashboard (Material) | Espaços de trabalho |
| reality | visibility (Material) | Mundo real |
| catgame | pets (Material) | Gato vs. Vovó |
| twitch | Twitch (Simple Icons) | Twitch |
| tiktok | TikTok (Simple Icons) | TikTok |
| hyperx_package | extension (Material) | Pacotes .hyperx sem ícone próprio |

Apps Android instalados usam o ícone do próprio app. Para trocar qualquer um destes por arte do
autor, basta substituir os dois PNGs com o mesmo nome.

## Logo HX (ícone do HyperXR e animação de inicialização)

A imagem enviada pelo autor do projeto está em `branding/logo/hx_logo_source.jpg`. A partir dela,
`branding/logo/hx_logo.png` (e `res/drawable-nodpi/hx_logo.png`) é a máscara limpa: logo branco
sobre fundo transparente, recortado. O ícone do app é `ic_hx_logo_bg` (degradê grafite) +
`ic_hx_logo_fg` (logo com brilho suave).

Atenção: esse desenho é o logo da marca HyperX (HP), uma marca registrada. Usar como está é
decisão do autor do projeto. Para publicar o app, troque estes arquivos por um logo próprio: o
ícone e as duas animações de inicialização usam só `hx_logo.png`.
