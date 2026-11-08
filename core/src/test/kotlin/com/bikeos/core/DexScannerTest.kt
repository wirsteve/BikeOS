package com.bikeos.core

import com.bikeos.core.peloton.DexFile
import com.bikeos.core.peloton.PelotonCallPolicy
import com.bikeos.core.peloton.PelotonCodeInspector
import com.bikeos.core.peloton.PelotonService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds tiny but structurally valid DEX files for tests. */
private class DexBuilder {
    data class Field(val name: String, val type: String, val value: Any)
    data class Cls(val name: String, val superName: String?, val interfaces: List<String>, val statics: List<Field>)

    private val classes = mutableListOf<Cls>()
    private val extraStrings = mutableListOf<String>()

    fun cls(name: String, superName: String? = "java.lang.Object", interfaces: List<String> = emptyList(), vararg statics: Field) =
        apply { classes += Cls(name, superName, interfaces, statics.toList()) }

    fun string(s: String) = apply { extraStrings += s }

    private fun desc(n: String) = if (n.length == 1) n else "L" + n.replace('.', '/') + ";"

    fun build(): ByteArray {
        val strings = LinkedHashSet<String>()
        val types = LinkedHashSet<String>()
        classes.forEach { c ->
            types += desc(c.name); c.superName?.let { types += desc(it) }; c.interfaces.forEach { types += desc(it) }
            c.statics.forEach { f -> types += desc(f.type); strings += f.name; (f.value as? String)?.let { strings += it } }
        }
        strings += types
        strings += extraStrings
        val sList = strings.toList(); val tList = types.toList()
        val sIdx = sList.withIndex().associate { it.value to it.index }
        val tIdx = tList.withIndex().associate { it.value to it.index }
        data class FieldId(val cls: Int, val type: Int, val name: Int)
        val fieldIds = mutableListOf<FieldId>()
        val classFieldStart = classes.map { c ->
            val start = fieldIds.size
            c.statics.forEach { fieldIds += FieldId(tIdx.getValue(desc(c.name)), tIdx.getValue(desc(it.type)), sIdx.getValue(it.name)) }
            start
        }

        val headerSize = 0x70
        val stringIdsOff = headerSize
        val typeIdsOff = stringIdsOff + 4 * sList.size
        val fieldIdsOff = typeIdsOff + 4 * tList.size
        val classDefsOff = fieldIdsOff + 8 * fieldIds.size
        var dataOff = classDefsOff + 32 * classes.size
        val data = ByteArrayOutputStream()
        fun here() = dataOff + data.size()
        fun uleb(v: Int) { var x = v; while (true) { val b = x and 0x7F; x = x ushr 7; if (x == 0) { data.write(b); return }; data.write(b or 0x80) } }
        fun u4(v: Int) { repeat(4) { data.write((v ushr (8 * it)) and 0xFF) } }
        fun u2(v: Int) { data.write(v and 0xFF); data.write((v ushr 8) and 0xFF) }

        val stringDataOffs = sList.map { s -> val o = here(); uleb(s.length); data.write(s.toByteArray()); data.write(0); o }
        while (data.size() % 4 != 0) data.write(0)
        val ifaceOffs = classes.map { c ->
            if (c.interfaces.isEmpty()) 0 else { while (data.size() % 4 != 0) data.write(0); val o = here(); u4(c.interfaces.size); c.interfaces.forEach { u2(tIdx.getValue(desc(it))) }; o }
        }
        val classDataOffs = classes.mapIndexed { i, c ->
            if (c.statics.isEmpty()) 0 else {
                val o = here()
                uleb(c.statics.size); uleb(0); uleb(0); uleb(0)
                var prev = 0
                c.statics.forEachIndexed { k, _ -> val idx = classFieldStart[i] + k; uleb(idx - prev); prev = idx; uleb(0x19) }
                o
            }
        }
        val staticOffs = classes.map { c ->
            if (c.statics.isEmpty()) 0 else {
                val o = here()
                uleb(c.statics.size)
                c.statics.forEach { f ->
                    when (val v = f.value) {
                        is Int -> { data.write((3 shl 5) or 0x04); u4(v) }
                        is String -> { data.write((3 shl 5) or 0x17); u4(sIdx.getValue(v)) }
                        else -> error("unsupported")
                    }
                }
                o
            }
        }

        val total = dataOff + data.size()
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("dex\n035\u0000".toByteArray())
        buf.putInt(0x20, total); buf.putInt(0x24, headerSize); buf.putInt(0x28, 0x12345678)
        buf.putInt(0x38, sList.size); buf.putInt(0x3C, stringIdsOff)
        buf.putInt(0x40, tList.size); buf.putInt(0x44, typeIdsOff)
        buf.putInt(0x50, fieldIds.size); buf.putInt(0x54, fieldIdsOff)
        buf.putInt(0x60, classes.size); buf.putInt(0x64, classDefsOff)
        stringDataOffs.forEachIndexed { i, o -> buf.putInt(stringIdsOff + 4 * i, o) }
        tList.forEachIndexed { i, t -> buf.putInt(typeIdsOff + 4 * i, sIdx.getValue(t)) }
        fieldIds.forEachIndexed { i, f ->
            buf.putShort(fieldIdsOff + 8 * i, f.cls.toShort()); buf.putShort(fieldIdsOff + 8 * i + 2, f.type.toShort()); buf.putInt(fieldIdsOff + 8 * i + 4, f.name)
        }
        classes.forEachIndexed { i, c ->
            val b = classDefsOff + 32 * i
            buf.putInt(b, tIdx.getValue(desc(c.name))); buf.putInt(b + 4, 1)
            buf.putInt(b + 8, c.superName?.let { tIdx.getValue(desc(it)) } ?: -1)
            buf.putInt(b + 12, ifaceOffs[i]); buf.putInt(b + 16, -1); buf.putInt(b + 20, 0)
            buf.putInt(b + 24, classDataOffs[i]); buf.putInt(b + 28, staticOffs[i])
        }
        buf.position(dataOff); buf.put(data.toByteArray())
        return buf.array()
    }
}

class DexScannerTest {
    private val bp = PelotonService.BIKE_PLUS_INTERFACE
    private val v1 = PelotonService.V1_INTERFACE

    private fun pelotonLikeDex(getData: Int = 14, setRes: Int? = 22, register: Int = 1) = DexBuilder()
        .cls(bp, null, emptyList(), DexBuilder.Field("DESCRIPTOR", "java.lang.String", bp))
        .cls(
            "$bp\$Stub", "android.os.Binder", listOf(bp),
            *listOfNotNull(
                DexBuilder.Field("TRANSACTION_getBikeData", "I", getData),
                DexBuilder.Field("TRANSACTION_startCalibration", "I", 3),
                setRes?.let { DexBuilder.Field("TRANSACTION_setTargetResistance", "I", it) },
            ).toTypedArray(),
        )
        .cls(v1, null)
        .cls(
            "$v1\$Stub", "android.os.Binder", listOf(v1),
            DexBuilder.Field("TRANSACTION_registerCallback", "I", register),
            DexBuilder.Field("TRANSACTION_unregisterCallback", "I", register + 1),
        )
        .cls("com.onepeloton.affernetservice.model.BikeData", "java.lang.Object")
        .string(PelotonService.V1_CALLBACK)
        .build()

    @Test fun parsesClassesAndConstants() {
        val dex = DexFile(pelotonLikeDex())
        val stub = dex.classes { it == "$bp\$Stub" }.single()
        assertEquals("android.os.Binder", stub.superName)
        assertEquals(listOf(bp), stub.interfaces)
        assertEquals(14L, stub.constants["TRANSACTION_getBikeData"])
        val iface = dex.classes { it == bp }.single()
        assertEquals(bp, iface.constants["DESCRIPTOR"])
        assertTrue(dex.containsString(PelotonService.V1_CALLBACK))
    }

    @Test fun inspectorConfirmsDefaultsAndFindsResistanceCall() {
        val r = PelotonCodeInspector.inspect(listOf(DexFile(pelotonLikeDex())))
        assertTrue(r.v1InterfaceFound && r.v1CallbackFound && r.bikePlusInterfaceFound && r.bikeDataFound)
        assertEquals(14, r.protocol.bikePlusGetBikeData)
        assertEquals(1, r.protocol.v1RegisterCallback)
        assertEquals(22, r.protocol.bikePlusSetResistance)
        assertEquals(listOf("setTargetResistance" to 22), r.resistanceCandidates)
        // calibration is never picked
        assertFalse(r.resistanceCandidates.any { it.first.contains("Calibration") })
    }

    @Test fun adaptsWhenPelotonRenumbersAfterAnUpdate() {
        val r = PelotonCodeInspector.inspect(listOf(DexFile(pelotonLikeDex(getData = 15, register = 4))))
        assertEquals(15, r.protocol.bikePlusGetBikeData)
        assertEquals(4, r.protocol.v1RegisterCallback)
        assertEquals(5, r.protocol.v1UnregisterCallback)
        assertTrue(r.notes.any { it.contains("adapted") })
        // and the safety policy follows the adapted protocol, still refusing everything else
        assertNotNull(PelotonCallPolicy.check(bp, 15, r.protocol))
        assertNull(PelotonCallPolicy.check(bp, 14, r.protocol))
        assertNull(PelotonCallPolicy.check(bp, 3, r.protocol, userResistanceCode = null))
    }

    @Test fun obfuscatedStubIsFoundThroughItsInterface() {
        val dex = DexBuilder()
            .cls(bp, null)
            .cls("a.b", "android.os.Binder", listOf(bp), DexBuilder.Field("a", "I", 14), DexBuilder.Field("b", "I", 9))
            .build()
        val r = PelotonCodeInspector.inspect(listOf(DexFile(dex)))
        assertEquals(mapOf("a" to 14, "b" to 9), r.transactions["IBikeInterface"])
        assertNull(r.protocol.bikePlusSetResistance) // no name => never guessed
    }

    @Test fun reportsMissingInterfaces() {
        val r = PelotonCodeInspector.inspect(listOf(DexFile(DexBuilder().cls("x.Y").build())))
        assertFalse(r.anyInterfaceFound)
        assertTrue(r.notes.isNotEmpty())
    }
}
