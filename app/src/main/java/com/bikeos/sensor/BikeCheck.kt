package com.bikeos.sensor

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.bikeos.core.peloton.CompatibilityReport
import com.bikeos.core.peloton.DexFile
import com.bikeos.core.peloton.PelotonCodeInspector
import com.bikeos.core.peloton.PelotonService
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads (never modifies) Peloton's sensor-service APK on this tablet and inspects its code, so BikeOS
 * knows which interfaces and transaction numbers this exact firmware uses before talking to it.
 *
 * Installed APKs are world-readable on Android, so no root or special permission is needed.
 */
object BikeCheck {
    sealed class Result {
        data class Ok(val report: CompatibilityReport, val version: String, val apkPath: String) : Result()
        data class NotInstalled(val why: String) : Result()
        data class Unreadable(val why: String) : Result()
    }

    fun run(context: Context): Result {
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(PelotonService.AFFERNET_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Result.NotInstalled("Peloton's sensor service (${PelotonService.AFFERNET_PACKAGE}) isn't on this device.")
        }
        val version = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PelotonService.AFFERNET_PACKAGE, 0).versionName ?: "?"
        }.getOrDefault("?")
        val apks = listOfNotNull(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val dexes = mutableListOf<DexFile>()
        val problems = mutableListOf<String>()
        for (path in apks) {
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { e ->
                        if (e.size in 1..(96L * 1024 * 1024)) {
                            dexes += DexFile(zip.getInputStream(e).use { it.readBytes() })
                        }
                    }
                }
            }.onFailure { problems += "${File(path).name}: ${it.message}" }
        }
        // Pre-installed system apps sometimes keep their code only in an optimized .vdex next to the APK.
        if (dexes.isEmpty()) dexes += vdexFallback(info.sourceDir, problems)
        if (dexes.isEmpty()) {
            Log.w("BikeOS.Check", "no DEX found: $problems")
            return Result.Unreadable(
                "Couldn't read Peloton's sensor-service code (${problems.joinToString().ifBlank { "no DEX inside the APK" }}). " +
                    "BikeOS will use the community-documented defaults.",
            )
        }
        return Result.Ok(PelotonCodeInspector.inspect(dexes), version, info.sourceDir)
    }

    private fun vdexFallback(apkPath: String?, problems: MutableList<String>): List<DexFile> {
        val dir = apkPath?.let { File(it).parentFile } ?: return emptyList()
        val vdexes = dir.walkTopDown().maxDepth(3).filter { it.extension == "vdex" }.toList()
        val out = mutableListOf<DexFile>()
        for (v in vdexes) {
            runCatching {
                val bytes = v.readBytes()
                // Embedded standard DEX files start with "dex\n0". Compact DEX ("cdex") isn't supported.
                var i = 0
                while (i < bytes.size - 0x70) {
                    if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'e'.code.toByte() && bytes[i + 2] == 'x'.code.toByte() &&
                        bytes[i + 3] == '\n'.code.toByte() && bytes[i + 4] == '0'.code.toByte()
                    ) {
                        val size = java.nio.ByteBuffer.wrap(bytes, i + 0x20, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                        if (size in 0x70..(bytes.size - i)) {
                            out += DexFile(bytes.copyOfRange(i, i + size))
                            i += size
                            continue
                        }
                    }
                    i++
                }
                if (out.isEmpty() && String(bytes, 0, 4.coerceAtMost(bytes.size)) == "vdex") problems += "${v.name}: compact DEX not supported"
            }.onFailure { problems += "${v.name}: ${it.message}" }
        }
        return out
    }
}
