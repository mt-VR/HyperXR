package com.hyperxr

import com.hyperxr.voice.VoiceCommand
import com.hyperxr.voice.VoiceCommands
import com.hyperxr.voice.VoiceCommands.Named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    private val apps = listOf(Named("web:youtube", "YouTube"), Named("hx:files", "Arquivos"), Named("game:catgame", "Gato vs. Vovó"), Named("web:whatsapp", "WhatsApp"))
    private val envs = listOf(Named("polyhaven-hdri:sunset", "Sunset Fairway"), Named("builtin:studio", "HyperXR Studio"))
    private fun p(s: String, ws: List<String> = emptyList()) = VoiceCommands.parse(s, apps, envs, ws)

    @Test fun opensAppsEvenMisheard() {
        assertEquals(VoiceCommand.OpenApp("web:youtube", "YouTube"), p("Abre o YouTube por favor"))
        assertEquals("web:youtube", (p("abrir youtubi") as VoiceCommand.OpenApp).appId)
        assertEquals("game:catgame", (p("jogar gato vs vovó") as VoiceCommand.OpenApp).appId)
        assertEquals("hx:files", (p("arquivos") as VoiceCommand.OpenApp).appId)
    }

    @Test fun launcherCommands() {
        assertEquals(VoiceCommand.SetMode(true), p("modo dock"))
        assertEquals(VoiceCommand.SetMode(false), p("Modo New"))
        assertEquals(VoiceCommand.Recenter, p("recentralizar"))
        assertEquals(VoiceCommand.CloseWindows, p("fechar todas as janelas"))
        assertEquals(VoiceCommand.Background("dim"), p("escurecer"))
        assertEquals(VoiceCommand.Background("camera"), p("câmera"))
        assertEquals(VoiceCommand.Background("polyhaven-hdri:sunset"), p("ambiente sunset"))
        assertEquals(VoiceCommand.Search("receita de bolo"), p("pesquisar receita de bolo"))
        assertEquals(VoiceCommand.RealityWindow, p("mostrar o mundo real"))
        assertEquals(VoiceCommand.Record(true), p("gravar a tela"))
        assertEquals(VoiceCommand.Record(false), p("parar gravação"))
        assertTrue(p("blá blá") is VoiceCommand.Unknown)
    }

    @Test fun workspaces() {
        assertEquals(VoiceCommand.SaveWorkspace("cinema"), p("salvar espaço cinema"))
        assertEquals(VoiceCommand.OpenWorkspace("cinema"), p("abrir espaço cinema", listOf("cinema", "estudo")))
        assertEquals(VoiceCommand.OpenWorkspace("estudo"), p("abre o espaço estudos", listOf("cinema", "estudo")))
    }
}
