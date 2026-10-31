package com.hyperxr.xrapps

/**
 * Picks a game's lines out of the system log (`logcat -v threadtime`): its process ids come from
 * the activity manager's "Start proc" lines and the crash reports, and every line of those
 * processes is kept, with the system's own lines about the package.
 */
object GameLog {
    private val START = Regex("""Start proc (\d+):([\w.]+)""")
    private val FATAL_PID = Regex("""Process: ([\w.]+), PID: (\d+)""")
    private val TOMBSTONE = Regex("""pid: (\d+), tid: \d+, name: .*>>> ([\w.:]+) <<<""")
    private val THREADTIME = Regex("""^\d\d-\d\d\s+[\d:.]+\s+(\d+)\s+\d+\s+[VDIWEF]\s""")

    fun filter(lines: List<String>, pkg: String, max: Int = 400): List<String> {
        val pids = HashSet<String>()
        for (l in lines) {
            START.find(l)?.let { if (it.groupValues[2] == pkg || it.groupValues[2].startsWith("$pkg:")) pids += it.groupValues[1] }
            FATAL_PID.find(l)?.let { if (it.groupValues[1] == pkg) pids += it.groupValues[2] }
            TOMBSTONE.find(l)?.let { if (it.groupValues[2] == pkg || it.groupValues[2].startsWith("$pkg:")) pids += it.groupValues[1] }
        }
        val kept = lines.filter { l ->
            val pid = THREADTIME.find(l)?.groupValues?.get(1)
            (pid != null && pid in pids) || l.contains(pkg)
        }
        return kept.takeLast(max)
    }

    private val KEY = listOf(
        Regex("FATAL EXCEPTION"), Regex("Caused by:"), Regex("UnsatisfiedLinkError"), Regex("dlopen failed"), Regex("CANNOT LINK EXECUTABLE"),
        Regex("Abort message"), Regex("""signal \d+ \(SIG\w+\)"""), Regex("(?i)entitlement"), Regex("(?i)not entitled"),
        Regex("(?i)openxr.*(fail|error)"), Regex("(?i)xrCreateInstance"), Regex("(?i)OVRPlugin.*(fail|error|unsupported)"),
        Regex("Application\\.Quit|Unity.*Quit"), Regex("has died"), Regex("(?i)HyperMagic.*(fail|error)"),
    )

    /** The lines that most likely say why the game closed (at most [max]), in log order. */
    fun summary(lines: List<String>, max: Int = 10): List<String> =
        lines.filter { l -> KEY.any { it.containsMatchIn(l) } }.map { it.replace(THREADTIME, "").trim() }.distinct().take(max)
}
