package com.hyperxr.questgames

/**
 * HyperXR's games inspired by famous Quest games: the mechanics only (names, rules, art
 * and code are HyperXR's own; 3D models from Kenney's CC0 kits). Five play with the hands
 * alone, five need a controller (Joy-Con, the HyperConnect phone or a Bluetooth gamepad).
 */
object QuestGameCatalog {
    class Info(val id: String, val title: String, val inspiredBy: String, val controller: Boolean, val icon: String, val howTo: String)

    val games = listOf(
        Info("rhythm", "Sabre de Ritmo", "jogos de ritmo de cortar blocos (Beat Saber)", false, "music_note",
            "Corte os blocos no ritmo com a mão da cor certa, na direção da seta"),
        Info("fruit", "Ninja das Frutas", "Fruit Ninja VR", false, "nutrition",
            "Passe a mão rápido pelas frutas jogadas para cima; evite as bombas"),
        Info("boxing", "Ringue", "The Thrill of the Fight", false, "sports_mma",
            "Boxe: soque cabeça e corpo, esquive movendo a cabeça, bloqueie com as mãos na frente do rosto"),
        Info("plank", "Prancha no Céu", "Richie's Plank Experience", false, "apartment",
            "Suba de elevador a 90 m e ande na prancha até a ponta (andando de verdade ou com a pinça)"),
        Info("golf", "Minigolfe", "Walkabout Mini Golf", false, "golf_course",
            "Faça a pinça para segurar o taco e empurre a bola até o buraco; três buracos"),
        Info("slowtime", "Tempo Parado", "SUPERHOT VR", true, "hourglass_bottom",
            "O tempo só anda quando você se mexe: atire nos inimigos vermelhos e desvie das balas"),
        Info("drones", "Caçador de Drones", "Space Pirate Trainer", true, "rocket_launch",
            "Atire nos drones com o gatilho; segure o outro botão para o escudo"),
        Info("pingpong", "Tênis de Mesa", "Eleven Table Tennis", true, "sports_tennis",
            "O controle é a raquete; o gatilho saca; partida até 11"),
        Info("fishing", "Pescaria", "Real VR Fishing", true, "phishing",
            "Balance e solte o gatilho para lançar, fisgue quando a boia afundar, recolha sem arrebentar a linha"),
        Info("diorama", "Pequeno Herói", "Moss", true, "cruelty_free",
            "Ande com o analógico num mundo em miniatura sobre a mesa, pule, pegue moedas e chegue à bandeira"),
        Info("climb", "Escalada", "The Climb", false, "hiking",
            "Feche a mão numa agarra (ou aperte o gatilho do controle) e puxe para subir um penhasco de 27 m; soltando tudo, você cai até a última plataforma"),
    )

    val ids = games.map { it.id }.toSet()
}
