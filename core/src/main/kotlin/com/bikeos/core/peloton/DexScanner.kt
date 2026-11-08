package com.bikeos.core.peloton

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal read-only parser for Android DEX files: just enough to find classes, their interfaces and
 * the values of their static constants.
 *
 * BikeOS uses it to inspect Peloton's own sensor service on the bike (its APK is readable by any app)
 * and confirm the Binder interfaces and transaction numbers it relies on, so a Peloton OTA update that
 * renumbers them is detected instead of silently breaking — and so the Bike+ "set resistance" call can be
 * found without anyone guessing a number.
 */
class DexFile(bytes: ByteArray) {
    private val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    data class ClassInfo(
        val name: String, // e.g. com.onepeloton.affernetservice.IBikeInterface$Stub
        val superName: String?,
        val interfaces: List<String>,
        /** Static fields with a constant initial value, in declaration order. Value is Long, String, Boolean, Double or null. */
        val constants: Map<String, Any?>,
    )

    init {
        require(bytes.size >= 0x70 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() && bytes[2] == 'x'.code.toByte()) {
            "not a DEX file"
        }
    }

    private fun u4(off: Int) = b.getInt(off).toLong() and 0xFFFFFFFFL
    private fun u2(off: Int) = b.getShort(off).toInt() and 0xFFFF
    private val stringIdsSize = u4(0x38).toInt()
    private val stringIdsOff = u4(0x3C).toInt()
    private val typeIdsSize = u4(0x40).toInt()
    private val typeIdsOff = u4(0x44).toInt()
    private val fieldIdsOff = u4(0x54).toInt()
    private val classDefsSize = u4(0x60).toInt()
    private val classDefsOff = u4(0x64).toInt()

    private val stringCache = HashMap<Int, String>()

    fun string(idx: Int): String = stringCache.getOrPut(idx) {
        require(idx in 0 until stringIdsSize)
        var p = u4(stringIdsOff + idx * 4).toInt()
        // skip uleb128 utf16 length
        while (b.get(p).toInt() and 0x80 != 0) p++
        p++
        val start = p
        while (b.get(p).toInt() != 0) p++
        val arr = ByteArray(p - start)
        b.position(start); b.get(arr)
        String(arr, Charsets.UTF_8) // MUTF-8; identical for the ASCII names we care about
    }

    fun typeName(idx: Int): String {
        require(idx in 0 until typeIdsSize)
        val d = string(u4(typeIdsOff + idx * 4).toInt())
        return if (d.startsWith("L") && d.endsWith(";")) d.substring(1, d.length - 1).replace('/', '.') else d
    }

    private fun fieldName(idx: Int): String = string(u4(fieldIdsOff + idx * 8 + 4).toInt())

    /** True if [s] appears anywhere in the string table (cheap check for interface descriptors). */
    fun containsString(s: String): Boolean = (0 until stringIdsSize).any { runCatching { string(it) == s }.getOrDefault(false) }

    fun classes(filter: (String) -> Boolean = { true }): Sequence<ClassInfo> = sequence {
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            val name = typeName(u4(base).toInt())
            if (!filter(name)) continue
            yield(readClass(base, name))
        }
    }

    private fun readClass(base: Int, name: String): ClassInfo {
        val superIdx = u4(base + 8)
        val interfacesOff = u4(base + 12).toInt()
        val classDataOff = u4(base + 24).toInt()
        val staticValuesOff = u4(base + 28).toInt()
        val superName = if (superIdx == 0xFFFFFFFFL) null else typeName(superIdx.toInt())
        val interfaces = if (interfacesOff == 0) emptyList() else {
            val n = u4(interfacesOff).toInt()
            (0 until n).map { typeName(u2(interfacesOff + 4 + it * 2)) }
        }
        val constants = LinkedHashMap<String, Any?>()
        if (classDataOff != 0) {
            val r = Reader(classDataOff)
            val staticCount = r.uleb()
            r.uleb(); r.uleb(); r.uleb() // instance fields, direct methods, virtual methods
            val staticNames = ArrayList<String>(staticCount)
            var fieldIdx = 0
            repeat(staticCount) {
                fieldIdx += r.uleb()
                r.uleb() // access flags
                staticNames += fieldName(fieldIdx)
            }
            if (staticValuesOff != 0) {
                val v = Reader(staticValuesOff)
                val n = v.uleb()
                for (k in 0 until n) {
                    val value = v.encodedValue()
                    staticNames.getOrNull(k)?.let { constants[it] = value }
                }
            }
        }
        return ClassInfo(name, superName, interfaces, constants)
    }

    private inner class Reader(var p: Int) {
        fun u1(): Int = b.get(p++).toInt() and 0xFF
        fun uleb(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val x = u1()
                result = result or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun signed(size: Int): Long {
            var v = 0L
            for (i in 0 until size) v = v or (u1().toLong() shl (8 * i))
            val shift = 64 - 8 * size
            return (v shl shift) shr shift
        }

        private fun unsigned(size: Int): Long {
            var v = 0L
            for (i in 0 until size) v = v or (u1().toLong() shl (8 * i))
            return v
        }

        fun encodedValue(): Any? {
            val h = u1()
            val type = h and 0x1F
            val arg = h shr 5
            val size = arg + 1
            return when (type) {
                0x00, 0x02, 0x04, 0x06 -> signed(size) // byte, short, int, long
                0x03 -> unsigned(size) // char
                0x10 -> { val raw = unsigned(size) shl (8 * (4 - size)); java.lang.Float.intBitsToFloat(raw.toInt()).toDouble() }
                0x11 -> { val raw = unsigned(size) shl (8 * (8 - size)); java.lang.Double.longBitsToDouble(raw) }
                0x17 -> string(unsigned(size).toInt())
                0x15, 0x16, 0x18, 0x19, 0x1A, 0x1B -> { unsigned(size); null }
                0x1C -> { val n = uleb(); repeat(n) { encodedValue() }; null }
                0x1D -> { uleb(); val n = uleb(); repeat(n) { uleb(); encodedValue() }; null }
                0x1E -> null
                0x1F -> arg != 0
                else -> error("bad encoded_value type $type")
            }
        }
    }
}

/** Binder transaction numbers BikeOS uses. Defaults are the community-documented values. */
data class PelotonProtocol(
    val v1RegisterCallback: Int = PelotonService.V1_TX_REGISTER_CALLBACK,
    val v1UnregisterCallback: Int = PelotonService.V1_TX_UNREGISTER_CALLBACK,
    val bikePlusGetBikeData: Int = PelotonService.BIKE_PLUS_TX_GET_BIKE_DATA,
    /** Set only when the bike check found it in Peloton's code (or the user entered it). */
    val bikePlusSetResistance: Int? = null,
    val source: String = "built-in defaults",
)

/** Result of inspecting Peloton's sensor service code on this bike. */
data class CompatibilityReport(
    val v1InterfaceFound: Boolean,
    val v1CallbackFound: Boolean,
    val bikePlusInterfaceFound: Boolean,
    val bikeDataFound: Boolean,
    /** Transaction constants found per interface, e.g. "IBikeInterface" -> {getBikeData=14, ...}. */
    val transactions: Map<String, Map<String, Int>>,
    val protocol: PelotonProtocol,
    val resistanceCandidates: List<Pair<String, Int>>,
    val notes: List<String>,
) {
    val anyInterfaceFound: Boolean get() = v1InterfaceFound || bikePlusInterfaceFound
}

object PelotonCodeInspector {
    private const val PKG = "com.onepeloton.affernetservice"

    /**
     * Inspect the given DEX files (all from Peloton's sensor service APK).
     * Transaction constants are found on the AIDL Stub of each interface: either by its real name
     * (`IBikeInterface$Stub`) or, if names are obfuscated, as the Binder subclass implementing the interface.
     */
    fun inspect(dexFiles: List<DexFile>): CompatibilityReport {
        val notes = mutableListOf<String>()
        val all = dexFiles.flatMap { d -> runCatching { d.classes().toList() }.onFailure { notes += "DEX parse error: ${it.message}" }.getOrDefault(emptyList()) }
        val byName = all.associateBy { it.name }
        fun hasString(s: String) = dexFiles.any { runCatching { it.containsString(s) }.getOrDefault(false) }

        fun txFor(iface: String): Map<String, Int> {
            val stub = byName["$iface\$Stub"]
                ?: all.firstOrNull { it.superName == "android.os.Binder" && iface in it.interfaces }
            val consts = stub?.constants ?: return emptyMap()
            val named = consts.filterKeys { it.startsWith("TRANSACTION_") }
            val source = named.ifEmpty { consts } // obfuscated names: fall back to every int constant
            return source.mapNotNull { (k, v) -> (v as? Long)?.toInt()?.takeIf { it in 1..0xFFFFFF }?.let { k.removePrefix("TRANSACTION_") to it } }.toMap()
        }

        val v1 = PelotonService.V1_INTERFACE
        val bp = PelotonService.BIKE_PLUS_INTERFACE
        val v1Found = byName.containsKey(v1) || hasString(v1)
        val cbFound = byName.containsKey(PelotonService.V1_CALLBACK) || hasString(PelotonService.V1_CALLBACK)
        val bpFound = byName.containsKey(bp) || hasString(bp)
        val bikeDataFound = all.any { it.name.endsWith(".BikeData") }

        val v1Tx = txFor(v1)
        val bpTx = txFor(bp)
        val shortName = { s: String -> s.substringAfterLast('.') }
        val transactions = buildMap {
            if (v1Tx.isNotEmpty()) put(shortName(v1), v1Tx)
            if (bpTx.isNotEmpty()) put(shortName(bp), bpTx)
        }

        fun pick(tx: Map<String, Int>, default: Int, vararg names: String): Int {
            val hit = names.firstNotNullOfOrNull { n -> tx.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value }
            if (hit != null && hit != default) notes += "Peloton uses #$hit for ${names.first()} on this firmware (default #$default) — adapted."
            return hit ?: default
        }

        val candidates = bpTx.filterKeys { k ->
            val l = k.lowercase()
            l.contains("resist") && (l.startsWith("set") || l.contains("target") || l.contains("move"))
        }.toList().sortedBy { it.second }
        val bestResistance = candidates.firstOrNull { it.first.lowercase().let { n -> n == "setresistance" || n == "settargetresistance" } }
            ?: candidates.singleOrNull()

        if (!v1Found && !bpFound) notes += "Neither Peloton sensor interface was found in $PKG. A Peloton update may have changed it."
        if (bpFound && bpTx.isEmpty()) notes += "IBikeInterface found but its transaction table could not be read (code may be obfuscated)."
        if (bpTx.isNotEmpty() && candidates.isEmpty()) notes += "No set-resistance call found in IBikeInterface; auto-resistance stays manual."
        if (candidates.size > 1 && bestResistance == null) notes += "Several resistance calls found; choose one manually: ${candidates.joinToString { "${it.first}=#${it.second}" }}"

        val protocol = PelotonProtocol(
            v1RegisterCallback = pick(v1Tx, PelotonService.V1_TX_REGISTER_CALLBACK, "registerCallback"),
            v1UnregisterCallback = pick(v1Tx, PelotonService.V1_TX_UNREGISTER_CALLBACK, "unregisterCallback"),
            bikePlusGetBikeData = pick(bpTx, PelotonService.BIKE_PLUS_TX_GET_BIKE_DATA, "getBikeData", "getCurrentBikeData"),
            bikePlusSetResistance = bestResistance?.second,
            source = if (transactions.isEmpty()) "built-in defaults" else "read from Peloton's code on this bike",
        )
        return CompatibilityReport(v1Found, cbFound, bpFound, bikeDataFound, transactions, protocol, candidates, notes)
    }
}
