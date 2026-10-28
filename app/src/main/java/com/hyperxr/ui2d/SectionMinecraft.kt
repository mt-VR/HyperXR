package com.hyperxr.ui2d

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypermagic.minecraft.McProfiles
import com.hypermagic.minecraft.McProfiles.Profile
import com.hypermagic.minecraft.McRuntime
import kotlinx.coroutines.delay
import pojlib.account.LoginHelper

/**
 * Minecraft: HyperMagic's own launcher for Minecraft Java Edition, ported from QuestCraft's
 * launcher library (Pojlib). Microsoft sign-in, the QuestCraft and GorillaCraft profiles, install
 * and play. The game itself runs in its own process (MinecraftActivity) through HyperMagic's
 * OpenXR runtime.
 */
@Composable
fun MinecraftSection(a: MainActivity) {
    var tick by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<Profile?>(null) }
    var status by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }

    // follows the sign-in thread (device code, then Xbox and Minecraft)
    LaunchedEffect(signingIn) {
        while (signingIn) {
            delay(500)
            tick++
            if (!LoginHelper.busy) {
                signingIn = false
                if (McProfiles.saveSignedIn(a)) status = "Conta conectada."
                else if (LoginHelper.error != null) status = "Não deu para entrar: ${LoginHelper.error}"
            }
        }
    }

    SectionCard(
        "Minecraft Java Edition",
        "O Minecraft de verdade (Java Edition) rodando dentro do HyperMagic, com a tecnologia do QuestCraft: o launcher (Pojlib) foi portado para cá, sem Unity, e o jogo abre direto no runtime OpenXR do HyperMagic com o Vivecraft. Nada de APK do QuestCraft modificado.",
    ) {
        Note("Precisa de uma conta Microsoft que tenha o Minecraft Java. Sem o jogo comprado, abre no modo demonstração.")
        Note("Na primeira vez o HyperMagic baixa o Java 22 do QuestCraft (41 MB), as bibliotecas nativas (26 MB) e o Minecraft com os sons e texturas (algumas centenas de MB). Use Wi-Fi.")
        if (!McRuntime.supported()) Note("Este celular não é ARM 64 bits: o Java do QuestCraft não existe para ele.", HxColors.warn)
        Note("Créditos: QuestCraft e Pojlib (QuestCraftPlusPlus, LGPLv3), Vivecraft, MobileGlues, LWJGL, Fabric. GorillaCraft usa o ViveMonkeCraft (laggyboi20-jpg, MIT).")
    }

    SectionCard("Conta") {
        @Suppress("UNUSED_EXPRESSION") tick
        val name = McProfiles.accountName(a)
        if (name != null) {
            KeyValue("Conectado como", name)
            OutlinedButton(onClick = { McProfiles.signOut(a); tick++ }) { Text("Sair da conta") }
        } else if (signingIn) {
            val code = LoginHelper.userCode
            if (code == null) Note("Pedindo um código à Microsoft...")
            else {
                Text("Abra ${LoginHelper.verificationUri ?: "microsoft.com/link"} em qualquer aparelho e digite:", color = HxColors.text)
                Text(code, color = HxColors.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LoginHelper.verificationUri ?: "https://www.microsoft.com/link"))) }
                    }) { Text("Abrir no navegador") }
                    OutlinedButton(onClick = {
                        a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Código", code))
                        status = "Código copiado."
                    }) { Text("Copiar código") }
                }
                Note("Esperando você confirmar no site...")
            }
        } else {
            Button(onClick = { McProfiles.beginLogin(a); signingIn = true; status = "" }) { Text("Entrar com a conta Microsoft") }
        }
    }

    ProfileCard(a, Profile.QUESTCRAFT,
        "QuestCraft",
        "O QuestCraft como ele é: Minecraft com Vivecraft, Sodium e os mods de desempenho do QuestCraft. Joga com as mãos ou com controle.",
        McProfiles.VERSIONS.toList(), working, tick, { working = it }, { status = it })
    ProfileCard(a, Profile.GORILLACRAFT,
        "GorillaCraft",
        "Minecraft com movimento de gorila (Gorilla Tag): você anda empurrando o chão e as paredes com as mãos. Ajustado para VR Box: sem pernas no corpo, sem prancha voadora nem teleporte, impulso limitado para o personagem não sair voando, e mais tolerância ao tremor do rastreamento das mãos pela câmera.",
        McProfiles.GORILLA_VERSIONS.toList(), working, tick, { working = it }, { status = it })

    if (status.isNotEmpty()) SectionCard("Andamento") { Text(status, color = HxColors.text) }
}

@Composable
private fun ProfileCard(
    a: MainActivity, p: Profile, title: String, text: String, versions: List<String>,
    working: Profile?, tick: Int, setWorking: (Profile?) -> Unit, setStatus: (String) -> Unit,
) {
    var version by remember { mutableStateOf(McProfiles.version(a, p).takeIf { it in versions } ?: versions.first()) }
    val installed = remember(tick, version, working) { McProfiles.installed(a, p) }
    val signedIn = McProfiles.accountUuid(a) != null
    SectionCard(title, text) {
        ChoiceRow("Versão do Minecraft", versions, version, { it }) { version = it; McProfiles.setVersion(a, p, it) }
        KeyValue("Estado", when {
            working == p -> "Instalando..."
            installed -> "Instalado"
            else -> "Não instalado"
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = working == null && McRuntime.supported(),
                onClick = {
                    McProfiles.setVersion(a, p, version)
                    setWorking(p)
                    Thread({
                        val msg = try {
                            McProfiles.prepare(a, p) { step -> a.runOnUiThread { setStatus("$title: $step") } }
                            "$title pronto para jogar."
                        } catch (t: Throwable) {
                            "$title: ${t.message ?: t.javaClass.simpleName}"
                        }
                        a.runOnUiThread { setStatus(msg); setWorking(null) }
                    }, "mc-install").start()
                },
                colors = ButtonDefaults.buttonColors(containerColor = HxColors.surface2),
            ) { Text(if (installed) "Atualizar" else "Instalar") }
            Button(enabled = installed && signedIn && working == null, onClick = { McProfiles.play(a, p) }) { Text("Jogar") }
        }
        if (!signedIn) Note("Entre na conta Microsoft acima para jogar.")
    }
}
