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

## Sem ícone fornecido (espaço reservado neutro, sem símbolo)

Hand Physics Lab, YouTube, Twitch, TikTok e pacotes `.hyperx` de terceiros
(que podem trazer seu próprio `icon` no manifesto — exibição dele é pendente). Para esses, o launcher
mostra um bloco gradiente neutro e o nome do app.
