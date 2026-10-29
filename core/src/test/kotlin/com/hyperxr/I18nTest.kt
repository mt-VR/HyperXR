package com.hyperxr

import com.hyperxr.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {
    @After fun back() = I18n.use("pt", emptyMap())

    @Test fun translatesExactTemplatesAndJoinedTexts() {
        I18n.use("en", mapOf(
            "Loja" to "Store", "Grátis" to "Free", "Baixar" to "Download",
            "Página {0} de {1}" to "Page {0} of {1}", "{0} de {1} portões" to "{0} of {1} gates",
            "Abrir {0}" to "Open {0}", "Pessoas" to "People", "Configurações" to "Settings",
        ))
        assertEquals("Store", I18n.t("Loja"))
        assertEquals("Page 2 of 12", I18n.t("Página 2 de 12"))
        assertEquals("3 of 12 gates", I18n.t("3 de 12 portões"))
        assertEquals("Free • Download", I18n.t("Grátis • Baixar"))
        assertEquals("Open People", I18n.t("Abrir Pessoas"))
        assertEquals("  Store", I18n.t("  Loja"))
        assertEquals("Settings…", I18n.t("Configurações…"))
        assertEquals("Desconhecido", I18n.t("Desconhecido"))
        // a template of short words only takes numbers: names stay names
        I18n.use("en", mapOf("{0} de {1}" to "{0} of {1}", "Tudo" to "Everything"))
        assertEquals("3 of 8", I18n.t("3 de 8"))
        assertEquals("Maria de Souza", I18n.t("Maria de Souza"))
        assertEquals("Everything  89", I18n.t("Tudo  89"))
    }

    @Test fun holesCanMoveInTheTranslation() {
        I18n.use("ru", mapOf("{0} jogou {1}" to "{1}: играл {0}"))
        assertEquals("Ritmo: играл Ana", I18n.t("Ana jogou Ritmo"))
    }

    @Test fun portugueseIsUnchanged() {
        I18n.use("pt", mapOf("Loja" to "Store"))
        assertEquals("Loja", I18n.t("Loja"))
    }
}
