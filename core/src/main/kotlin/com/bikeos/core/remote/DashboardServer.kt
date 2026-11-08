package com.bikeos.core.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the second-screen dashboard shows. Read-only by design. */
@Serializable
data class LiveSnapshot(
    val riding: Boolean,
    val paused: Boolean,
    val workout: String? = null,
    val segment: String? = null,
    val segmentRemainingSec: Int? = null,
    val elapsedSec: Int,
    val power: Int,
    val zone: Int,
    val cadence: Int,
    val resistance: Int,
    val heartRate: Int? = null,
    val speed: Float,
    val speedUnit: String,
    val distance: Float,
    val distanceUnit: String,
    val outputKj: Int,
    val calories: Int,
    val targetCadence: String? = null,
    val targetResistance: String? = null,
    val targetPower: String? = null,
    val suggestion: String? = null,
)

/**
 * A tiny HTTP server so any phone, tablet or laptop on the same Wi-Fi can show live bike metrics in
 * a browser (e.g. an iPhone next to Apple Fitness+ on an iPad or Apple TV).
 *
 *   GET /            dashboard page
 *   GET /api/live    current snapshot as JSON
 *   GET /api/stream  Server-Sent Events, one snapshot per second
 *
 * It only ever reads data: there are no endpoints that change anything on the bike.
 */
class DashboardServer(
    private val port: Int,
    private val snapshot: () -> LiveSnapshot,
    private val streamIntervalMs: Long = 1_000,
) {
    private val json = Json { encodeDefaults = true }
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "BikeOS-Dashboard").apply { isDaemon = true } }

    val boundPort: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        pool.execute {
            while (running.get()) {
                val client = try { ss.accept() } catch (_: SocketException) { break }
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
            val out = s.getOutputStream()
            if (method != "GET") return respond(out, 405, "text/plain", "Read-only")
            when (path) {
                "/" -> respond(out, 200, "text/html; charset=utf-8", DashboardPage.HTML)
                "/api/live" -> respond(out, 200, "application/json", json.encodeToString(LiveSnapshot.serializer(), snapshot()))
                "/api/stream" -> stream(s, out)
                else -> respond(out, 404, "text/plain", "Not found")
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun stream(s: Socket, out: OutputStream) {
        s.soTimeout = 0
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n".toByteArray())
        out.flush()
        try {
            while (running.get() && !s.isClosed) {
                out.write(("data: " + json.encodeToString(LiveSnapshot.serializer(), snapshot()) + "\n\n").toByteArray())
                out.flush()
                Thread.sleep(streamIntervalMs)
            }
        } catch (_: Exception) {
            // client went away
        }
    }
}

object DashboardPage {
    val HTML = """
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="theme-color" content="#07090d">
<title>BikeOS Live</title>
<style>
:root{--bg:#07090d;--card:#12161d;--line:#2a3240;--text:#f2f5f9;--dim:#8e99ab;--good:#2ee6a8;--warn:#ffb020}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px -apple-system,system-ui,sans-serif;padding:16px}
h1{font-size:18px;margin:0 0 4px}#sub{color:var(--dim);margin-bottom:14px;min-height:20px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}
.c{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:14px;text-align:center}
.l{font-size:11px;letter-spacing:1px;color:var(--dim);text-transform:uppercase}
.v{font-size:52px;font-weight:900;line-height:1.1}.u{font-size:14px;color:var(--dim)}
.t{font-size:13px;font-weight:700;margin-top:4px;color:var(--good)}.t.off{color:var(--warn)}
#sug{margin-top:12px;color:var(--warn);font-weight:700;font-size:20px;min-height:24px}
#seg{font-size:20px;font-weight:800;margin:6px 0 12px}
.z1{color:#8e99ab}.z2{color:#4fc3ff}.z3{color:#2ee6a8}.z4{color:#ffd84d}.z5{color:#ff9f43}.z6{color:#ff5c5c}.z7{color:#c86bff}
</style></head><body>
<h1>BikeOS Live</h1><div id="sub">Connecting…</div><div id="seg"></div>
<div class="grid">
 <div class="c"><div class="l">Power</div><div class="v" id="pw">--</div><div class="u" id="zn">W</div><div class="t" id="tp"></div></div>
 <div class="c"><div class="l">Cadence</div><div class="v" id="cd" style="color:#4fc3ff">--</div><div class="u">rpm</div><div class="t" id="tc"></div></div>
 <div class="c"><div class="l">Resistance</div><div class="v" id="rs" style="color:#ff5c8a">--</div><div class="u">%</div><div class="t" id="tr"></div></div>
 <div class="c"><div class="l">Heart rate</div><div class="v" id="hr" style="color:#ff4d4d">--</div><div class="u">bpm</div></div>
 <div class="c"><div class="l">Time</div><div class="v" id="tm">0:00</div><div class="u" id="rm"></div></div>
 <div class="c"><div class="l">Output</div><div class="v" id="kj" style="color:#2ee6a8">0</div><div class="u">kJ</div></div>
 <div class="c"><div class="l">Speed</div><div class="v" id="sp">0</div><div class="u" id="spu"></div></div>
 <div class="c"><div class="l">Distance</div><div class="v" id="ds">0</div><div class="u" id="dsu"></div></div>
 <div class="c"><div class="l">Calories</div><div class="v" id="ca" style="color:#ff7a2f">0</div><div class="u">kcal</div></div>
</div><div id="sug"></div>
<script>
const ${'$'}=id=>document.getElementById(id);
const clk=s=>{s=Math.max(0,s|0);const h=s/3600|0,m=(s%3600)/60|0,x=s%60;return(h?h+':'+String(m).padStart(2,'0'):m)+':'+String(x).padStart(2,'0')};
function inRange(v,r){if(!r)return null;const p=r.replace(/[^0-9-]/g,' ').trim().split(/[ -]+/).map(Number);return v>=p[0]&&v<=p[1]}
function tgt(el,txt,val){const e=${'$'}(el);if(!txt){e.textContent='';return}const ok=inRange(val,txt);e.textContent=(ok?'✓ ':ok===false?'⚠ ':'')+txt;e.className='t'+(ok===false?' off':'')}
function show(d){
 ${'$'}('sub').textContent=d.riding?(d.paused?'Paused':(d.workout||'Riding')):'Waiting for you to start pedaling';
 ${'$'}('seg').textContent=d.segment?d.segment+(d.segmentRemainingSec!=null?' · '+clk(d.segmentRemainingSec)+' left':''):'';
 ${'$'}('pw').textContent=d.power;${'$'}('pw').className='v z'+d.zone;${'$'}('zn').textContent='W · Zone '+d.zone;
 ${'$'}('cd').textContent=d.cadence;${'$'}('rs').textContent=d.resistance;${'$'}('hr').textContent=d.heartRate??'--';
 ${'$'}('tm').textContent=clk(d.elapsedSec);${'$'}('kj').textContent=d.outputKj;${'$'}('ca').textContent=d.calories;
 ${'$'}('sp').textContent=d.speed.toFixed(1);${'$'}('spu').textContent=d.speedUnit;${'$'}('ds').textContent=d.distance.toFixed(2);${'$'}('dsu').textContent=d.distanceUnit;
 tgt('tc',d.targetCadence,d.cadence);tgt('tr',d.targetResistance,d.resistance);tgt('tp',d.targetPower,d.power);
 ${'$'}('sug').textContent=d.suggestion||'';
}
function connect(){const es=new EventSource('/api/stream');es.onmessage=e=>show(JSON.parse(e.data));es.onerror=()=>{${'$'}('sub').textContent='Reconnecting…';es.close();setTimeout(connect,2000)}}
connect();
</script></body></html>
""".trimIndent()
}
