package com.hyperxr.sdk

import java.io.File
import kotlin.system.exitProcess

/**
 * `hyperx` command line tool.
 *   pack <sourceDir> <out.hyperx>      build a package with integrity hashes
 *   inspect <file.hyperx>              validate a package without executing it
 *   packall <gamesDir> <outDir>        pack every sub-directory containing manifest.json
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "pack" -> {
            val m = HyperxPackage.pack(File(args[1]), File(args[2]))
            println("packed ${m.id} ${m.version} (${m.integrity.files.size} files)")
        }
        "packall" -> {
            val out = File(args[2]).apply { mkdirs() }
            File(args[1]).listFiles()?.filter { File(it, "manifest.json").isFile }?.sortedBy { it.name }?.forEach { dir ->
                val target = File(out, dir.name + HyperxPackage.EXTENSION)
                val m = HyperxPackage.pack(dir, target)
                val r = HyperxPackage.inspect(target)
                if (!r.valid) { System.err.println("${dir.name}: ${r.errors}"); exitProcess(1) }
                println("packed ${m.id} -> ${target.name}")
            }
        }
        "inspect" -> {
            val r = HyperxPackage.inspect(File(args[1]))
            println(if (r.valid) "VALID ${r.manifest!!.id} ${r.manifest.version}" else "INVALID")
            r.errors.forEach { println("error: $it") }
            r.warnings.forEach { println("warning: $it") }
            if (!r.valid) exitProcess(2)
        }
        else -> { System.err.println("usage: hyperx pack|packall|inspect ..."); exitProcess(64) }
    }
}
