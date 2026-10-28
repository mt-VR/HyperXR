package com.hyperxr.ui2d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hyperxr.social.Friend
import com.hyperxr.social.Social

/**
 * Pessoas: the HyperMagic account (Supabase), the user name friends search for, friend requests,
 * and the server it talks to. Messages, calls and screen sharing happen in the XR Pessoas app.
 */
@Composable
fun PeopleSection(a: MainActivity) {
    Social.init(a)
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { a.runOnUiThread { tick++ } }
        Social.addListener(l)
        Social.start()
        onDispose { Social.removeListener(l) }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var friendName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val sb = Social.sb
    val me = Social.me

    fun work(msg: String, body: () -> Unit) {
        note = msg
        Thread {
            val r = runCatching(body)
            a.runOnUiThread { note = r.exceptionOrNull()?.message ?: ""; tick++ }
        }.start()
    }

    if (!sb.signedIn) {
        SectionCard("Conta HyperMagic", "Para conversar, ligar e mostrar a tela para amigos. A conta fica no servidor Supabase do HyperMagic.") {
            OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, label = { Text("Senha (6 ou mais caracteres)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { work("Entrando…") { sb.signIn(email.trim(), password); Social.start() } }) { Text("Entrar") }
                OutlinedButton(onClick = {
                    work("Criando a conta…") {
                        if (sb.signUp(email.trim(), password)) Social.start()
                        else throw IllegalStateException("Conta criada. Abra o link de confirmação que chegou em ${email.trim()} e depois toque em Entrar.")
                    }
                }) { Text("Criar conta") }
            }
            val anon = remember { mutableStateOf<Boolean?>(null) }
            if (anon.value == null) Thread { val v = runCatching { sb.authSettings().optJSONObject("external")?.optBoolean("anonymous_users") == true }.getOrDefault(false); a.runOnUiThread { anon.value = v } }.start()
            if (anon.value == true) OutlinedButton(onClick = { work("Entrando…") { sb.signInAnonymously(); Social.start() } }) { Text("Entrar sem e-mail (conta só neste celular)") }
        }
    } else if (me == null) {
        SectionCard("Seu nome de usuário", "É como os amigos te acham: 3 a 20 letras minúsculas, números, _ ou ponto.") {
            OutlinedTextField(username, { username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '.' }.take(20) },
                label = { Text("Usuário (@nome)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(display, { display = it.take(30) }, label = { Text("Nome que aparece (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { note = "Salvando…"; Social.createProfile(username, display) }) { Text("Salvar") }
            OutlinedButton(onClick = { Social.signOut() }) { Text("Sair da conta") }
        }
    } else {
        SectionCard("Você") {
            KeyValue("Usuário", "@${me.username}")
            KeyValue("Nome", me.name)
            KeyValue("Nível", "${me.level} (${me.xp} XP)")
            sb.email?.takeIf { it.isNotEmpty() }?.let { KeyValue("E-mail", it) }
            OutlinedButton(onClick = { Social.signOut() }) { Text("Sair da conta") }
        }
        SectionCard("Amigos", "Mensagens, chamadas e mostrar a tela ficam no app Pessoas do modo XR.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(friendName, { friendName = it.lowercase().take(21) }, label = { Text("Adicionar por @usuário") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { Social.addFriend(friendName); note = "Pedido enviado para @${friendName.removePrefix("@")}"; friendName = "" }) { Text("Adicionar") }
            }
            if (Social.friends.isEmpty()) Note("Nenhum amigo ainda.")
            for (f in Social.friends) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${f.profile.name} (@${f.profile.username})", color = HxColors.text, modifier = Modifier.weight(1f))
                    when (f.state) {
                        Friend.State.FRIEND -> Text(f.profile.activity(), color = if (f.profile.online) HxColors.ok else HxColors.dim)
                        Friend.State.REQUEST_IN -> {
                            Button(onClick = { Social.acceptFriend(f.profile.id) }) { Text("Aceitar") }
                            OutlinedButton(onClick = { Social.removeFriend(f.profile.id) }) { Text("Recusar") }
                        }
                        Friend.State.REQUEST_OUT -> OutlinedButton(onClick = { Social.removeFriend(f.profile.id) }) { Text("Cancelar pedido") }
                    }
                }
            }
        }
    }
    val error = note.ifEmpty { Social.lastError ?: "" }
    if (error.isNotEmpty()) SectionCard("Aviso") { Text(error, color = HxColors.warn) }

    var url by remember { mutableStateOf(sb.url) }
    var key by remember { mutableStateOf(sb.key) }
    SectionCard("Servidor", "O HyperMagic usa o Supabase do projeto. Para usar outro servidor, rode supabase/schema.sql nele e troque aqui.") {
        OutlinedTextField(url, { url = it }, label = { Text("URL do projeto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("Chave pública (publishable / anon)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { sb.setServer(url, key); Social.signOut(); tick++ }) { Text("Usar este servidor") }
        Note("Chamadas usam conexão direta entre os celulares (WebRTC). Em algumas redes de dados móveis ela não fecha; no Wi-Fi costuma funcionar.")
    }
}
