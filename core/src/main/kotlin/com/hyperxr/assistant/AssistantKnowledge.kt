package com.hyperxr.assistant

import com.hyperxr.voice.VoiceCommands

/**
 * What the assistant knows about HyperXR itself. A small model has a short context, so only the
 * few topics that share words with the question go into the prompt.
 */
object AssistantKnowledge {
    class Topic(val keys: List<String>, val text: String)

    val topics = listOf(
        Topic(listOf("clicar", "clique", "selecionar", "apertar", "botao", "olhar", "retículo"),
            "Para clicar: olhar para o botão por ~1 s, tocar a tela/botão do visor, pinçar com a mão apontada, tocar com o dedo (Direct Touch) ou o R/A do Joy-Con."),
        Topic(listOf("mao", "maos", "pinca", "pincar", "hand", "tracking", "dedo", "bolinha"),
            "Mãos: uma bolinha entre polegar e indicador se preenche ao pinçar; pinçar é tocar, segurar e mover arrasta. Pinçar e segurar longe de botões abre o menu radial. Polegar deslizando no indicador rola; dois toques do polegar voltam."),
        Topic(listOf("calibrar", "tamanho", "mao", "pequena", "grande", "escala"),
            "Tamanho da mão: é medido sozinho (calibração automática). Para ajustar à mão: Configurações 2D > Hand tracking > tamanho da mão em cm."),
        Topic(listOf("toque", "direto", "direct", "touch", "encostar"),
            "Direct Touch: tocar os painéis com a ponta do dedo, como uma tela; liga e desliga em Ajustes rápidos."),
        Topic(listOf("joy", "joycon", "controle", "analogico", "botao", "gamepad"),
            "Joy-Con: o analógico move o cursor, A clica, B volta (segurar B fecha a janela), R/ZR + analógico rola, Home recentraliza. Pareie pelo Bluetooth do celular."),
        Topic(listOf("recentralizar", "centralizar", "torto", "frente", "girou", "deslocado"),
            "Recentralizar: botão Home do Joy-Con, Ajustes rápidos > Recentralizar visão ou dizer \"recentralizar\"."),
        Topic(listOf("dock", "new", "modo", "barra", "launcher", "biblioteca", "tarefas"),
            "Modo New: a biblioteca de apps fica à frente com a barra de tarefas embaixo. Modo Dock: janelas lado a lado sobre a barra; a biblioteca abre pelo botão Biblioteca. Troca no botão Dock/New da barra."),
        Topic(listOf("realidade", "mista", "camera", "passthrough", "mr", "arcore", "6dof", "3dof"),
            "Realidade mista: com ARCore o rastreamento é 6DoF e a câmera aparece ao fundo; sem ARCore, câmera mono e 3DoF; sem câmera, VR SBS com ambiente 3D. O fundo pode ser câmera, câmera escurecida ou um ambiente."),
        Topic(listOf("ambiente", "360", "fundo", "cenario", "panorama", "praia", "estudio"),
            "Ambientes: escolha na aba de fundos da biblioteca ou no painel Ambientes (baixa ambientes Poly Haven). Uma foto 360 da Galeria pode ser salva como ambiente."),
        Topic(listOf("navegador", "site", "internet", "aba", "favorito", "pesquisar", "web"),
            "Navegador: motor GeckoView (do Firefox/Wolvic), com abas, favoritos, histórico, modo desktop, downloads e tela de cinema para vídeos em tela cheia."),
        Topic(listOf("janela", "janelas", "curvar", "maior", "menor", "fechar", "mover"),
            "Janelas: arraste pela barra de baixo; a barra tem Voltar, Teclado, Curvar/Plano, Menor, Maior, Tela 2D e Fechar. O botão Ocultar da barra de tarefas esconde todas sem fechar."),
        Topic(listOf("android", "app", "apps", "shizuku", "whatsapp", "instagram", "preto"),
            "Apps Android em janelas: precisam do Shizuku ativo e da permissão no painel Apps Android. Apps com DRM ou que bloqueiam displays secundários aparecem pretos (limite do Android)."),
        Topic(listOf("openxr", "runtime", "broker", "jogo", "jogos", "unity", "unreal"),
            "Runtime OpenXR do HyperMagic: apps OpenXR (loader Khronos) rodam nele. Para eles acharem o runtime, instale o HyperMagic OpenXR Broker ou escolha \"HyperMagic Runtime\" no broker que já existir no celular."),
        Topic(listOf("quest", "apk", "patcher", "corrigir", "instalar", "meta", "vrapi"),
            "Jogos de Quest: abra o APK em Arquivos e use Corrigir e instalar. O HyperMagic diz antes se roda: loader OpenXR da Khronos funciona, o da Meta é trocado; VrApi não roda; checagem de licença da Meta pode fechar o jogo."),
        Topic(listOf("daydream", "cardboard", "google"),
            "Apps Daydream e Cardboard: a camada de compatibilidade grava os parâmetros do visor e abre o app em modo Cardboard."),
        Topic(listOf("arquivos", "arquivo", "pasta", "copiar", "pdf", "video", "foto"),
            "Arquivos: gerenciador do armazenamento (copiar, recortar, colar, renomear, apagar). Abre texto, fotos, PDF, vídeo e áudio no XR e instala .hyperx e APKs."),
        Topic(listOf("galeria", "foto", "fotos", "video", "videos", "360", "captura"),
            "Galeria: fotos e vídeos do celular e as capturas do HyperMagic; fotos e vídeos 360 abrem ao seu redor."),
        Topic(listOf("app", "world", "objeto", "objetos", "notificacao", "notificacoes", "musica", "caixa", "estante", "portal"),
            "App-to-World: apps viram objetos no espaço; a música vira uma caixa de som, notificações viram cartas com responder, arquivos recentes uma estante e qualquer app um portal. Precisa do acesso às notificações."),
        Topic(listOf("gato", "vovo", "avo", "jogo", "cat"),
            "Gato vs. Vovó: você é um gato na casa da vovó; com Joy-Cons, atrapalhe as tarefas dela e acerte-a com objetos antes que ela termine, desviando dos ataques."),
        Topic(listOf("lab", "fisica", "physics", "bancada", "cubos"),
            "Hand Physics Lab: bancada com física de objetos à sua frente, para pegar e jogar coisas com as mãos ou o cursor do Joy-Con."),
        Topic(listOf("espaco", "espacos", "workspace", "salvar", "trabalho"),
            "Espaços de trabalho: salvam as janelas abertas com um nome e reabrem todas juntas (app Espaços de trabalho ou \"salvar espaço X\")."),
        Topic(listOf("mundo", "real", "sala", "quarto", "ver", "fora"),
            "Mundo real: janela móvel com a imagem da câmera dentro do VR; precisa da câmera ligada (hand tracking ou realidade mista)."),
        Topic(listOf("travando", "lento", "fluido", "lag", "desempenho", "fps", "esquentando", "quente", "bateria"),
            "Desempenho: se travar ou esquentar, baixe a escala de render (ex. 0.7) e mantenha 60 fps; o modo de desempenho sustentado e a resolução dinâmica ajudam. O hand tracking também gasta bateria."),
        Topic(listOf("ipd", "lente", "lentes", "embacado", "duplo", "distorcao", "fov", "visor", "box"),
            "Visor: IPD (distância entre os olhos, padrão 63 mm), separação das lentes, FOV e distorção ficam em Configurações 2D > Gráficos. Imagem dupla geralmente é IPD errado."),
        Topic(listOf("teclado", "digitar", "escrever", "ditado"),
            "Teclado XR: abre ao tocar num campo de texto ou no botão Teclado da janela; tem botão de ditado por voz."),
        Topic(listOf("capturar", "captura", "print", "screenshot", "gravar"),
            "Captura: menu radial > Captura ou dizer \"capturar\"; as imagens vão para a Galeria."),
        Topic(listOf("gravar", "gravacao", "video", "compartilhar", "transmitir", "tv", "16", "9", "hdmi", "espelhar", "stream"),
            "Compartilhar tela 16:9: grava vídeo MP4 em 720p/1080p/1440p sem as duas lentes (Filmes/HyperMagic), transmite para o navegador no mesmo Wi-Fi, mostra em telas externas e, no Android 15+, deixa gravações de outros apps em 16:9."),
        Topic(listOf("sair", "configuracoes", "ajustes", "2d"),
            "Configurações 2D: Ajustes rápidos > Sair para as configurações 2D. Lá ficam gráficos, rastreamento, hand tracking, controles e áudio."),
        Topic(listOf("sdk", "hyperx", "lua", "criar", "desenvolver"),
            "SDK: apps e jogos HyperMagic são pacotes .hyperx em Lua (LuaJIT), com API hx de física, UI e entrada; veja docs/SDK.md."),
        Topic(listOf("assistente", "ia", "voce", "modelo", "offline"),
            "Assistente: IA que roda no próprio celular (Qwen 2.5, offline depois de baixar). Fale pelo botão Voz (reconhecimento do Google) ou digite no painel Assistente."),
    )

    /** Up to [n] topics sharing the most words with [question] (accents and case ignored). */
    fun relevant(question: String, n: Int = 3): List<Topic> {
        val words = VoiceCommands.normalize(question).split(' ').filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        return topics.map { t -> t to t.keys.count { k -> VoiceCommands.normalize(k).let { nk -> words.any { w -> w == nk || (nk.length > 4 && w.startsWith(nk.take(5))) } } } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(n).map { it.first }
    }
}
