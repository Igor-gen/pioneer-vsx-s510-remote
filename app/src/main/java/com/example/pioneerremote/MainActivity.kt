// ============================================================================
// Pioneer VSX-S510 Remote — verzia 6 (FAV tlačidlo + Rozšírené rádiá s fallbackmi)
// - Tmavý dizajn podľa náhľadu (karty, zaoblené tlačidlá, cyan/orange akcent)
// - Posuvník hlasitosti: posiela VU/VD až po pustení palca, zrýchlená odozva (50 ms)
// - 🔍 vyhľadanie receivera: sken podsiete telefónu (port 8102 + overenie ?V)
// - MUTE ako prepínač (sleduje MUT0/MUT1 spätnú väzbu)
// - Zvýraznenie aktívneho vstupu / režimu / rádia
// - Screen 2:
//     * Rozšírený zoznam slovenských a českých rádií s HTTP streammi a fallbackmi na nižší bitrate
//     * Tlačidlo ⭐ +FAV (pridanie/odstránenie aktuálnej skladby/stanice do interného priečinka Favorites - 30NW)
//     * Doplnené DIDL-Lite metadáta a automatické prepínanie na MEDIA SERVER / DMR
//     * Priame TCP ovládanie pre vTuner NETRADIO (38FN) a FAVORITES (45FN)
// - Screen 3: DLNA prehliadač + album fronta s DIDL-Lite metadátami
// ----------------------------------------------------------------------------
// BUILD: GitHub Actions — Android Studio / Gradle.
// Poznámka: AndroidManifest.xml musí mať theme @style/Theme.AppCompat.NoActionBar
// a povolené usesCleartextTraffic="true" v <application> pre HTTP komunikáciu!
// ============================================================================

package com.example.pioneerremote

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // ===== konfiguracia =====
    private var host: String = "192.168.1.80"
    private val port: Int = 8102
    private val volMax = 160

    // ===== farby (tmavy dizajn ako v nahlade) =====
    private val C_BG = 0xFF0B1220.toInt()
    private val C_CARD = 0xFF1E293B.toInt()
    private val C_CARD2 = 0xFF16202F.toInt()
    private val C_BTN = 0xFF1E293B.toInt()
    private val C_STROKE = 0xFF334155.toInt()
    private val C_CYAN = 0xFF22D3EE.toInt()
    private val C_CYAN_D = 0xFF155E75.toInt()
    private val C_ORANGE = 0xFFF97316.toInt()
    private val C_RED = 0xFFDC2626.toInt()
    private val C_GREEN = 0xFF34D399.toInt()
    private val C_WHITE = 0xFFF8FAFC.toInt()
    private val C_SLATE = 0xFF94A3B8.toInt()
    private val C_DIM = 0xFF64748B.toInt()
    private val C_ON_ACCENT = 0xFF06121F.toInt()

    // ===== stav =====
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketRef = AtomicReference<Socket?>(null)
    private var outWriter: OutputStreamWriter? = null
    private var currentVol = 0
    private var isMuted = false
    private var isOn = false
    private var activeInput: String? = null
    private var activeMode: String? = null
    private var activeRadio: String? = null
    private var seekJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ===== UI =====
    private lateinit var ipEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var volumeBig: TextView
    private lateinit var infoLine: TextView
    private lateinit var radioLine: TextView
    private lateinit var connLine: TextView
    private lateinit var volSlider: SeekBar
    private lateinit var powerBtn: Button
    private lateinit var muteBtn: Button
    private val inputButtons = mutableMapOf<String, Button>()
    private val modeButtons = mutableMapOf<String, Button>()
    private val radioButtons = mutableMapOf<String, Button>()

    private val inputs = linkedMapOf(
        "BD" to "25FN", "CD" to "01FN", "DVR/BDR" to "15FN",
        "DVD" to "04FN", "IPOD/USB" to "17FN", "SAT/CBL" to "05FN",
        "TUNER" to "02FN", "VIDEO 1" to "10FN", "VIDEO 2" to "14FN",
        "MEDIA SRV" to "44FN", "NETRADIO" to "38FN", "FAVORITES" to "45FN",
        "HDMI 1" to "19FN", "HDMI 2" to "20FN", "HDMI 3" to "21FN",
        "HDMI 4" to "22FN", "HDMI 5" to "23FN", "HDMI 6" to "24FN"
    )

    private val modes = linkedMapOf(
        "STEREO" to "0001SR",
        "DIRECT" to "0004SR",
        "PURE DIRECT" to "0006SR",
        "AUTO SURR." to "0007SR",
        "PLII MOVIE" to "0016SR",
        "PLII MUSIC" to "0017SR"
    )

    // Rozšírený zoznam SK a CZ rádií s podporou viacerých alternatívnych URL (priorita vysoký bitrate -> fallback na nižší)
    private val radios = linkedMapOf(
        "Rádio Slovensko" to listOf("http://icecast.stv.livebox.sk/slovensko_256.mp3", "http://icecast.stv.livebox.sk/slovensko_128.mp3"),
        "Rádio_FM" to listOf("http://icecast.stv.livebox.sk/fm_256.mp3", "http://icecast.stv.livebox.sk/fm_128.mp3"),
        "Rádio Devín" to listOf("http://icecast.stv.livebox.sk/devin_256.mp3", "http://icecast.stv.livebox.sk/devin_128.mp3"),
        "Rádio Regina BA" to listOf("http://icecast.stv.livebox.sk/regina_ba_256.mp3", "http://icecast.stv.livebox.sk/regina_ba_128.mp3"),
        "Rádio Expres" to listOf("http://stream.bauermedia.sk/expres-hi.mp3", "http://stream.bauermedia.sk/expres.mp3"),
        "Európa 2" to listOf("http://stream.bauermedia.sk/europa2-hi.mp3", "http://stream.bauermedia.sk/europa2.mp3"),
        "Rádio Melody" to listOf("http://stream.bauermedia.sk/melody-hi.mp3", "http://stream.bauermedia.sk/melody.mp3"),
        "Rádio Rock" to listOf("http://stream.bauermedia.sk/rock-hi.mp3", "http://stream.bauermedia.sk/rock.mp3"),
        "Fun Rádio" to listOf("http://stream.funradio.sk:8000/fun192.mp3", "http://stream.funradio.sk:8000/fun128.mp3"),
        "Rádio Vlna" to listOf("http://stream.radiovlna.sk/vlna128.mp3"),
        "Rádio Jemné" to listOf("http://stream.jemne.sk/jemne128.mp3"),
        "Rádio Lumen" to listOf("http://stream.lumen.sk:8000/lumen_128.mp3"),
        "Best FM" to listOf("http://stream.bestfm.sk:8000/bestfm128.mp3"),
        "ČRo Radiožurnál" to listOf("http://amp.cesnet.cz:8000/cro1.mp3", "http://rozhlas.stream/radiozurnal_high.aac"),
        "ČRo Dvojka" to listOf("http://amp.cesnet.cz:8000/cro2.mp3"),
        "ČRo Vltava"
