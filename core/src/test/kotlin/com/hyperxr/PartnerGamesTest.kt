package com.hyperxr

import com.hyperxr.xrapps.PartnerGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerGamesTest {
    @Test fun readsTheItchDownloadSteps() {
        val html = """<form><input type="hidden" name="csrf_token" value="WyJZaWpD.fBs9="/></form>"""
        assertEquals("WyJZaWpD.fBs9=", PartnerGames.csrfToken(html))
        val json = """{"external":false,"url":"https:\/\/itch.io\/x\/file.apk?sig=1"}"""
        assertEquals("https://itch.io/x/file.apk?sig=1", PartnerGames.downloadUrl(json))
        val g = PartnerGames.ALL.first()
        assertTrue(PartnerGames.fileEndpoint(g).startsWith(g.itchPage + "/file/19790223?"))
        assertEquals(64, g.sha256.length)
    }

    @Test fun hashesLikeSha256sum() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PartnerGames.sha256("abc".byteInputStream()))
    }
}
