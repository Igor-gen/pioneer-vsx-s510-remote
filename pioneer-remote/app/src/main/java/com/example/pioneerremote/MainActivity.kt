// ============================================================================
// Pioneer VSX-S510 Remote — klon oficiálnej aplikácie iControlAV5
// ----------------------------------------------------------------------------
// Ako zostaviť (build):
// 1. Android Studio -> New Project -> "Empty Views Activity" (Kotlin),
//    Minimum SDK: API 24 (Android 7.0).
// 2. Tento súbor ulož ako app/src/main/java/com/example/pioneerremote/MainActivity.kt
// 3. AndroidManifest.xml: pridaj nad <application>:
//      <uses-permission android:name="android.permission.INTERNET" />
//      <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
//    a v <application> pridaj android:usesCleartextTraffic="true"
//    (protokol Pioneer je čistý TCP — TLS nie je).
// 4. Spusti na telefóne pripojenom na rovnakú Wi-Fi sieť ako receiver.
//
// Nastavenie receivera VSX-S510:
// - Pripoj receiver Ethernet káblom do routra.
// - V menu: Home Menu -> System Setup -> Network Setup:
//    * zapni "Network Standby" (aby app vedela receiver zapnúť),
//    * nastav pevnú IP alebo si IP poznač (zobrazí sa v Network Setup).
// - Protokol: legacy Pioneer IP control, TCP port 8102 (prípadne 23),
//    textové príkazy ukončené CR/LF. Zdroj príkazov: oficiálny
//    dokument Pioneer "IP & RS-232 Codes" (FY13AVR).
// ============================================================================

package com.example.pioneerremote

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    // ---- Konfigurácia ----
    private var host: String = "192.168.1.80"   // IP adresa tvojho VSX-S510
    private val port: Int = 8102                // Pioneer IP control port (alternatíva: 23)

    // ---- Stav ----
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketRef = AtomicReference<Socket?>(null)
    private var outWriter: OutputStreamWriter? = null
    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private lateinit var inputText: TextView

    // Vstupy VSX-S510 (kódy vstupov z protokolu FN)
    private val inputs = linkedMapOf(
        "BD" to "25FN", "HDMI1" to "19FN", "HDMI2" to "20FN",
        "HDMI3" to "21FN", "HDMI4" to "22FN", "HDMI5" to "23FN",
        "HDMI6" to "24FN", "DVD" to "04FN", "TV/SAT" to "05FN",
        "VIDEO 1" to "10FN", "VIDEO 2" to "14FN", "DVR/BDR" to "15FN",
        "iPod/USB" to "17FN", "NETRADIO" to "38FN"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("pioneer", MODE_PRIVATE)
        host = prefs.getString("host", host) ?: host
        buildUI()
        connect()
    }

    // ============================ UI (programovo, bez XML) =====================
    private fun buildUI() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Horný riadok: IP + pripoj
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ipEdit = EditText(this).apply {
            setText(host); hint = "IP adresa receivera"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnReconnect = Button(this).apply { text = "Pripoj" }
        btnReconnect.setOnClickListener {
            host = ipEdit.text.toString().trim()
            getSharedPreferences("pioneer", MODE_PRIVATE)
                .edit().putString("host", host).apply()
            connect()
        }
        top.addView(ipEdit); top.addView(btnReconnect)
        root.addView(top)

        // Stav spojenia
        statusText = TextView(this).apply { text = "Odpojené"; textSize = 14f }
        root.addView(statusText)

        // Stavová karta
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.dialog_holo_light_frame)
        }
        volumeText = TextView(this).apply { text = "Hlasitosť: —"; textSize = 22f; gravity = Gravity.CENTER }
        inputText = TextView(this).apply { text = "Vstup: —"; textSize = 16f; gravity = Gravity.CENTER }
        card.addView(volumeText); card.addView(inputText)
        root.addView(card)

        // Napájanie + mute
        root.addView(row(listOf(
            b("⏻ ZAP") { send("PO") },
            b("⏻ VYP") { send("PF") },
            b("🔇 MUTE") { send("MO") }
        )))
        // Hlasitosť
        root.addView(row(listOf(
            b("VOL –") { send("VD"); send("?V") },
            b("VOL +") { send("VU"); send("?V") }
        )))

        // Vstupy — mriežka
        root.addView(TextView(this).apply { text = "Výber vstupu"; textSize = 16f })
        val grid = GridLayout(this).apply { columnCount = 3; useDefaultMargins = true }
        inputs.forEach { (name, code) ->
            grid.addView(Button(this).apply {
                text = name
                setOnClickListener { send(code); send("?F") }
                layoutParams = GridLayout.LayoutParams().apply {
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            })
        }
        root.addView(grid)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun row(buttons: List<Button>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach {
                addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun b(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    // ============================ TCP spojenie =================================
    private fun connect() {
        scope.coroutineContext.cancelChildren()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 3000)
                sock.keepAlive = true
                outWriter = OutputStreamWriter(sock.getOutputStream(), Charsets.US_ASCII)
                socketRef.set(sock)
                ui { statusText.text = "Pripojené: $host:$port" }
                sendSilent("?P"); sendSilent("?V"); sendSilent("?F")
                listen(sock)
            } catch (e: Exception) {
                ui { statusText.text = "Nepodarilo sa pripojiť (${e.message}). Skontroluj IP a Wi-Fi." }
            }
        }
    }

    // Číta odpovede receivera (VOLnnn, FNnn, PWR0/2, MUT0/1, R = ready...)
    private fun listen(sock: Socket) {
        scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                while (isActive && sock.isConnected) {
                    val line = reader.readLine() ?: break
                    handleResponse(line.trim())
                }
            } catch (_: Exception) {
                ui { statusText.text = "Spojenie prerušené" }
            }
        }
    }

    private fun handleResponse(line: String) {
        ui {
            when {
                line.startsWith("VOL") ->
                    volumeText.text = "Hlasitosť: ${line.substring(3)}"
                line.startsWith("FN") && line.length == 4 -> {
                    val code = line.substring(2) + "FN"
                    val name = inputs.entries.firstOrNull { it.value == code }?.key ?: line
                    inputText.text = "Vstup: $name"
                }
                line == "PWR0" -> statusText.text = "Receiver: ZAPNUTÝ"
                line == "PWR2" -> statusText.text = "Receiver: STANDBY"
                line == "MUT0" -> volumeText.text = "🔇 MUTE"
                line == "MUT1" -> sendSilent("?V")
            }
        }
    }

    // Odoslanie príkazu (ukončenie CR+LF, ako vyžaduje protokol)
    private fun send(cmd: String) = sendSilent(cmd)

    private fun sendSilent(cmd: String) {
        scope.launch(Dispatchers.IO) {
            try {
                outWriter?.let {
                    it.write(cmd + "\r\n"); it.flush()
                }
            } catch (e: Exception) {
                ui { statusText.text = "Chyba odosielania: ${e.message}" }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
        runCatching { socketRef.get()?.close() }
    }
}
