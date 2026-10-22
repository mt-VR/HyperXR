package com.hyperxr.files

import java.io.File
import java.io.IOException

/** What the file manager does with a file when it is opened. */
enum class FileKind { FOLDER, TEXT, IMAGE, VIDEO, AUDIO, APK, HYPERX, OBB, ARCHIVE, PDF, OTHER }

object FileKinds {
    private val text = setOf("txt", "md", "markdown", "log", "json", "xml", "csv", "tsv", "ini", "cfg", "conf", "yaml", "yml",
        "toml", "properties", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "hpp", "py", "lua", "sh",
        "gradle", "srt", "vtt", "sub", "gltf", "nfo")
    private val image = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val video = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "ts", "avi")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mid", "midi", "amr")
    private val archive = setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String, isDirectory: Boolean = false): FileKind {
        if (isDirectory) return FileKind.FOLDER
        return when (val e = extension(name)) {
            "apk", "apks", "xapk" -> FileKind.APK
            "hyperx" -> FileKind.HYPERX
            "obb" -> FileKind.OBB
            "pdf" -> FileKind.PDF
            // .ts is both TypeScript and MPEG transport stream; a transport stream is never tiny text
            in video -> FileKind.VIDEO
            in text -> FileKind.TEXT
            in image -> FileKind.IMAGE
            in audio -> FileKind.AUDIO
            in archive -> FileKind.ARCHIVE
            else -> if (e.isEmpty()) FileKind.OTHER else FileKind.OTHER
        }
    }

    /** Short label for a kind (Portuguese, like the rest of the UI). */
    fun label(kind: FileKind): String = when (kind) {
        FileKind.FOLDER -> "Pasta"; FileKind.TEXT -> "Texto"; FileKind.IMAGE -> "Imagem"; FileKind.VIDEO -> "Vídeo"
        FileKind.AUDIO -> "Áudio"; FileKind.APK -> "App Android"; FileKind.HYPERX -> "Pacote HyperXR"; FileKind.OBB -> "Dados de jogo (OBB)"
        FileKind.ARCHIVE -> "Arquivo compactado"; FileKind.PDF -> "PDF"; FileKind.OTHER -> "Arquivo"
    }

    /** True when the first bytes look like text (no NULs, mostly printable UTF-8). */
    fun looksLikeText(head: ByteArray, n: Int = head.size): Boolean {
        if (n == 0) return true
        var bad = 0
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xff
            if (b == 0) return false
            if (b < 0x09 || (b in 0x0e..0x1f)) bad++
        }
        return bad * 20 < n
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }
}

enum class FileSort { NAME, DATE, SIZE, TYPE }

/** File operations used by the file manager; every one refuses to act outside sane bounds. */
object FileOps {
    private val badName = Regex("[/\\\\\u0000:*?\"<>|]")

    /** A name the user typed is valid when it is a single path segment. */
    fun isValidName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." && !badName.containsMatchIn(name) && name.length <= 255

    /** "foto.jpg" -> "foto (2).jpg" ... until the name is free in [dir]. */
    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        var i = 2
        while (File(dir, "$base ($i)$ext").exists()) i++
        return "$base ($i)$ext"
    }

    fun sorted(files: List<File>, sort: FileSort, descending: Boolean = false): List<File> {
        val folders = files.filter { it.isDirectory }; val plain = files.filter { !it.isDirectory }
        val cmp: Comparator<File> = when (sort) {
            FileSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSort.DATE -> compareBy { it.lastModified() }
            FileSort.SIZE -> compareBy { it.length() }
            FileSort.TYPE -> compareBy<File> { FileKinds.extension(it.name) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val c = if (descending) cmp.reversed() else cmp
        // folders first, like every file manager
        return folders.sortedWith(if (sort == FileSort.SIZE) compareBy(String.CASE_INSENSITIVE_ORDER) { it.name } else c) + plain.sortedWith(c)
    }

    /** Is [child] the same as or inside [parent]? (copying a folder into itself must fail) */
    fun isInside(child: File, parent: File): Boolean {
        val c = child.canonicalFile.path; val p = parent.canonicalFile.path
        return c == p || c.startsWith(p + File.separator)
    }

    /** Copies a file or folder into [destDir] under a free name; returns the new file. */
    fun copyInto(src: File, destDir: File, progress: (Long) -> Unit = {}): File {
        if (src.isDirectory && isInside(destDir, src)) throw IOException("Não é possível copiar uma pasta para dentro dela mesma")
        val dest = File(destDir, uniqueName(destDir, src.name))
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { copyInto(it, dest, progress) }
        } else {
            src.inputStream().use { i -> dest.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); progress(n.toLong()) }
            } }
            dest.setLastModified(src.lastModified())
        }
        return dest
    }

    /** Moves by rename when possible (same volume), otherwise copy + delete. */
    fun moveInto(src: File, destDir: File): File {
        if (src.isDirectory && isInside(destDir, src)) throw IOException("Não é possível mover uma pasta para dentro dela mesma")
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return src
        val dest = File(destDir, uniqueName(destDir, src.name))
        if (src.renameTo(dest)) return dest
        val copy = copyInto(src, destDir)
        if (!src.deleteRecursively()) throw IOException("Copiado, mas o original não pôde ser apagado")
        return copy
    }

    fun rename(src: File, newName: String): File {
        if (!isValidName(newName)) throw IOException("Nome inválido")
        val dest = File(src.parentFile, newName)
        if (dest.exists()) throw IOException("Já existe \"$newName\"")
        if (!src.renameTo(dest)) throw IOException("Não foi possível renomear")
        return dest
    }

    fun totalSize(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
}
