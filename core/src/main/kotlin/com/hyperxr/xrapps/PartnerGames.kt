package com.hyperxr.xrapps

/**
 * Games their authors allowed HyperXR to offer, exactly as published: the original APK is
 * downloaded from the author's own page, checked against its SHA-256 and installed unpatched (the
 * permission covers the unmodified game only, so the patcher never touches it). Not open source:
 * the game and its code stay the author's.
 */
data class PartnerGame(
    val name: String,
    val author: String,
    val packageName: String,
    /** itch.io game page and the upload (file) id of the APK on it. */
    val itchPage: String,
    val itchUploadId: Long,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val description: String,
    val permission: String,
)

object PartnerGames {
    val ALL = listOf(
        PartnerGame(
            name = "[BETA] Gorilla Rush",
            author = "Wellidev (Welli)",
            packageName = "com.welligames.gorillarush",
            itchPage = "https://wellidev.itch.io/beta-gorilla-rush",
            itchUploadId = 19790223L,
            fileName = "Gorilla Rush_1_1.apk",
            bytes = 9_268_918L,
            sha256 = "4626f7f9eb7afbcb0c40420b6a79cd30322aefa3bb810b3344afab4512a2e980",
            description = "Jogo para VR Box: ele mesmo divide a tela para as lentes e usa a câmera.",
            permission = "Oferecido com permissão do autor, sem nenhuma modificação.",
        ),
    )

    /** The csrf token of an itch.io page (needed to ask for a free file's download link). */
    fun csrfToken(html: String): String? = Regex("""name="csrf_token" value="([^"]+)"""").find(html)?.groupValues?.get(1)

    /** URL that answers (POST, csrf token) with the file's temporary download link. */
    fun fileEndpoint(g: PartnerGame) = "${g.itchPage}/file/${g.itchUploadId}?source=view_game&as_props=1&after_download_lightbox=true"

    /** The "url" field of that answer. */
    fun downloadUrl(json: String): String? = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")

    fun sha256(input: java.io.InputStream): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
