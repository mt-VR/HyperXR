package com.hyperxr

import com.hyperxr.assistant.AssistantBrain
import com.hyperxr.assistant.AssistantKnowledge
import com.hyperxr.assistant.RuntimeSnapshot
import com.hyperxr.voice.VoiceCommand
import com.hyperxr.voice.VoiceCommands.Named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBrainTest {
    private val s = RuntimeSnapshot(
        xrMode = "VR SBS", tracking = "3DoF", launcherMode = "dock", battery = "64%",
        windows = listOf("Navegador (2 abas)", "WhatsApp"),
        apps = listOf(Named("web:youtube", "YouTube"), Named("web:netflix", "Netflix"), Named("hx:files", "Arquivos")),
        environments = listOf(Named("studio", "Estúdio"), Named("beach", "Praia ao pôr do sol")),
        workspaces = listOf("cinema", "estudo"),
        settings = mapOf("fps" to "60", "escala_render" to "0.85"),
    )

    @Test fun parsesActionStrings() {
        val r = AssistantBrain.parse("""{"resposta":"Abrindo o YouTube e escurecendo.","acoes":["abrir_app:YouTube","fundo:escurecer"]}""", "abre o youtube e escurece", s)
        assertEquals("Abrindo o YouTube e escurecendo.", r.say)
        assertEquals(listOf(VoiceCommand.OpenApp("web:youtube", "YouTube"), VoiceCommand.Background("dim")), r.actions)
    }

    @Test fun parsesFencedJsonAndObjectActions() {
        val out = "```json\n{\n  \"resposta\": \"Colocando a praia.\",\n  \"acoes\": [{\"acao\":\"fundo\",\"alvo\":\"Praia\"}]\n}\n```"
        val r = AssistantBrain.parse(out, "coloca o ambiente de praia", s)
        assertEquals(listOf(VoiceCommand.Background("beach")), r.actions)
    }

    @Test fun questionsRunNoActions() {
        val r = AssistantBrain.parse("""{"resposta":"Há duas janelas abertas.","acoes":["fechar_janelas"]}""", "quantas janelas estão abertas", s)
        assertEquals("Há duas janelas abertas.", r.say)
        assertTrue(r.actions.isEmpty())
        assertTrue(AssistantBrain.isQuestion("o que é o app to world"))
        assertTrue(AssistantBrain.isQuestion("como eu calibro a mão"))
        assertFalse(AssistantBrain.isQuestion("abre o youtube"))
        assertFalse(AssistantBrain.isQuestion("deixa mais fluido ta travando"))
    }

    @Test fun inventedDestructiveActionsAreDropped() {
        val r = AssistantBrain.parse("""{"resposta":"Melhorando.","acoes":["fechar_janelas","modo:dock","ajuste:escala_render=0,7"]}""", "deixa mais fluido ta travando", s)
        assertEquals(listOf(VoiceCommand.Setting("escala_render", "0.70")), r.actions)
    }

    @Test fun settingsAreValidatedAndClamped() {
        assertEquals(VoiceCommand.Setting("fps", "90"), AssistantBrain.setting("fps=144"))
        assertEquals(VoiceCommand.Setting("hand_tracking", "nao"), AssistantBrain.setting("hand_tracking=não"))
        assertEquals(null, AssistantBrain.setting("root=sim"))
        assertEquals(null, AssistantBrain.setting("fps=rapido"))
    }

    @Test fun forgottenCommandComesFromTheRules() {
        val r = AssistantBrain.parse("""{"resposta":"Abriu o Instagram e colocou o ambiente de praia.","acoes":[]}""", "coloca o ambiente de praia", s)
        assertEquals(listOf(VoiceCommand.Background("beach")), r.actions)
        assertEquals("Ambiente: Praia ao pôr do sol.", r.say)
    }

    @Test fun settingWrittenAsActionAndAliases() {
        val r = AssistantBrain.parse("""{"resposta":"ok","acoes":["hand_tracking:nao","abrir_app:Netflix","ajuste:scale_render=0.7"]}""", "desliga o hand tracking e abre a netflix", s)
        assertEquals(listOf(VoiceCommand.Setting("hand_tracking", "nao"), VoiceCommand.OpenApp("web:netflix", "Netflix")), r.actions)
    }

    @Test fun unrelatedSettingIsDropped() {
        val r = AssistantBrain.parse("""{"resposta":"O Joy-Con não está conectado.","acoes":["ajuste:hand_tracking=nao"]}""", "meu joy-con não conecta", s)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun emptyHowToAnswerUsesKnowledge() {
        val r = AssistantBrain.parse("""{"resposta":"Para calibrar o tamanho da sua mão, siga estas etapas:","acoes":[]}""", "como eu calibro o tamanho da minha mão?", s)
        assertTrue(r.say, r.say.contains("tamanho da mão em cm"))
    }

    @Test fun fastPathOnlyForShortPlainCommands() {
        assertEquals(VoiceCommand.OpenApp("web:youtube", "YouTube"), AssistantBrain.fastPath("abre o youtube", s))
        assertEquals(null, AssistantBrain.fastPath("abre o youtube e escurece o fundo", s))
        assertEquals(null, AssistantBrain.fastPath("como abro o youtube", s))
    }

    @Test fun plainTextAnswerIsKept() {
        val r = AssistantBrain.parse("A bateria está em 64%.", "qual a bateria", s)
        assertEquals("A bateria está em 64%.", r.say)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun workspacesMatchSavedNames() {
        val r = AssistantBrain.parse("""{"resposta":"Abrindo.","acoes":["abrir_espaco:Estudos"]}""", "abre meu espaço de estudos", s)
        assertEquals(listOf(VoiceCommand.OpenWorkspace("estudo")), r.actions)
    }

    @Test fun promptCarriesStateAndRelevantKnowledge() {
        val p = AssistantBrain.prompt(s, "como calibro o tamanho da minha mão?", emptyList(), 1280)
        assertTrue(p.contains("Bateria: 64%"))
        assertTrue(p.contains("Navegador (2 abas), WhatsApp"))
        assertTrue(p.contains("tamanho da mão em cm"))
        assertTrue(p.endsWith("Pedido: como calibro o tamanho da minha mão?"))
        assertTrue(AssistantKnowledge.relevant("o joy-con não conecta").any { it.text.startsWith("Joy-Con") })
        // ASSISTANT_DUMP=dir writes prompts to try against the real model on a PC
        System.getenv("ASSISTANT_DUMP")?.let { dir ->
            val qs = listOf("abre o youtube e escurece o fundo", "como eu calibro o tamanho da minha mão?", "quantas janelas estão abertas?",
                "deixa mais fluido, ta travando", "salva isso como espaço estudo", "o que é o app to world?", "coloca o ambiente de praia",
                "qual a bateria?", "meu joy-con não conecta", "desliga o hand tracking e abre a netflix")
            qs.forEachIndexed { i, q -> java.io.File(dir, "q$i.txt").writeText(AssistantBrain.prompt(s, q, emptyList(), 1280)) }
        }
    }
}
