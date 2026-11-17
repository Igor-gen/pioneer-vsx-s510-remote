// ============================================================================
// Pioneer VSX-S510 Remote — verzia 22
// Opravy podľa analýzy logu:
// 1. Príkaz xxGHP na VSX-S510 berie absolútny index položky (01GHP..99GHP),
//    nie relatívny riadok 1..8! (Preto 05GHP pri položke 37 otváralo 5. adresár "Era - Ameno").
// 2. Odstránený súbeh vlákien (race condition): parsovanie GBP/GCP/GDP/GEP beží
//    okamžite v IO vlákne, takže rawPageLines.clear() nikdy nevymaže prichádzajúce
//    riadky 1..8 v UI fronte.
// 3. Pri čítaní stránky (GGP) sa čaká na prijatie všetkých riadkov okna (podľa GBP),
//    nielen na prvý riadok GEP01.
// ============================================================================

package com.example.pioneerremote

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    // ===== konfiguracia =====
    private var host: String = "192.168.1.80"
    private val port: Int = 8102
    private val volMax = 160

    // ===== farby (tmavy dizajn) =====
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
    private var activeFavIndex: Int = -1
    private var activeStationName: String = ""
    private var favJob: Job? = null
    private var usbJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Jedno vlákno na odosielanie => príkazy idú v poradí a nikdy sa neprelínajú
    private val sendDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // ===== DIAGNOSTICKÝ LOG =====
    private val logLock = Any()
    private val logTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logFile: File by lazy { File(getExternalFilesDir(null) ?: filesDir, "pioneer_log.txt") }

    private fun logRaw(tag: String, text: String) {
        try {
            synchronized(logLock) {
                val f = logFile
                if (f.exists() && f.length() > 3_000_000L) f.delete()
                f.appendText("${logTimeFmt.format(Date())} $tag $text\n")
            }
        } catch (_: Exception) { }
    }

    private fun logEv(text: String) = logRaw("EV", text)

    private fun readLogTail(maxChars: Int = 120000): String {
        return try {
            synchronized(logLock) {
                val t = if (logFile.exists()) logFile.readText() else ""
                if (t.length > maxChars) "…(začiatok logu skrátený)…\n" + t.takeLast(maxChars) else t
            }
        } catch (e: Exception) {
            "Chyba čítania logu: ${e.message}"
        }
    }

    private fun shareLog() {
        val header = "Pioneer Remote v22 | host=$host | vstup=$activeInput | usbUseIp=$usbUseIp" +
            " | adresárov v pamäti=${usbTreeCache.size} | ${android.os.Build.MODEL}\n"
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, "Pioneer Remote log")
        i.putExtra(Intent.EXTRA_TEXT, header + readLogTail())
        startActivity(Intent.createChooser(i, "Odoslať log"))
    }

    private fun clearLog() {
        synchronized(logLock) { runCatching { logFile.delete() } }
        logEv("log vymazaný")
        if (::usbStatusText.isInitialized) usbStatusText.text = "🗑 Log vymazaný."
    }

    // ===== Hlavný displej a ovládacie prvky =====
    private lateinit var ipEdit: EditText
    private lateinit var mainStationBig: TextView
    private lateinit var mainShowBitrate: TextView
    private lateinit var infoLine: TextView
    private lateinit var connLine: TextView
    private lateinit var statusText: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var volBar: ProgressBar
    private lateinit var powerBtn: Button
    private lateinit var muteBtn: Button

    private val inputButtons = mutableMapOf<String, Button>()
    private val modeButtons = mutableMapOf<String, Button>()
    private val favButtons = mutableMapOf<Int, Button>()

    private val inputs = linkedMapOf(
        "BD" to "25FN",
        "CD" to "01FN",
        "DVR/BDR" to "15FN",
        "DVD" to "04FN",
        "IPOD/USB" to "17FN",
        "SAT/CBL" to "06FN",
        "TV" to "05FN",
        "TUNER" to "02FN",
        "VIDEO" to "10FN",
        "MEDIA SRV" to "44FN",
        "NETRADIO" to "38FN",
        "FAVORITES" to "45FN"
    )

    private val modes = linkedMapOf(
        "STEREO" to "0001SR",
        "AUTO SURR." to "0005SR",
        "DIRECT" to "0006SR",
        "ALC / PURE" to "0151SR",
        "PLII MOVIE" to "0013SR",
        "PLII MUSIC" to "0014SR",
        "STANDARD" to "0010SR",
        "ADV. SURR." to "0100SR"
    )

    // ===== KARTA RÁDIO =====
    private lateinit var favToggleBtn: Button
    private lateinit var favDrawerCard: LinearLayout
    private lateinit var favDrawerStatus: TextView
    private lateinit var favTableContainer: LinearLayout
    private var isFavListExpanded: Boolean = false

    private val favStations = linkedMapOf<Int, String>()
    private val playMetaLines = Collections.synchronizedMap(mutableMapOf<Int, Pair<String, String>>())
    @Volatile private var isSyncingFavorites: Boolean = false
    @Volatile private var isPlayingFavorite: Boolean = false
    @Volatile private var nwScreenType: String = ""
    @Volatile private var nwScreenName: String = ""
    @Volatile private var listScreenReady: Boolean = false
    private var playTimeText: String = ""
    private var lastFlText: String = ""

    // ===== KARTA MÉDIÁ: PREPÍNAČ USB KĽÚČ / DLNA SERVER =====
    private var isUsbMediaMode: Boolean = true
    private lateinit var modeUsbBtn: Button
    private lateinit var modeDlnaBtn: Button
    private lateinit var usbContainer: LinearLayout
    private lateinit var dlnaContainer: LinearLayout

    // ===== STROMOVÁ PAMÄŤ (CACHE) USB KĽÚČA + OZNAČENIE =====
    private data class UsbNode(
        val indexInParent: Int,
        val title: String,
        val isFolder: Boolean,
        val pathKey: String
    )

    private val usbTreeCache = linkedMapOf<String, MutableList<UsbNode>>()
    private val usbCurrentPath = mutableListOf<Pair<Int, String>>()
    private var usbSelectedPathKey: String? = null
    private val usbNodeButtons = mutableMapOf<String, Button>()
    private val usbCheckButtons = mutableMapOf<String, Button>()

    // Nízkoúrovňové premenné pre čítanie zoznamu z receivera (GEP01..GEP08)
    private val pageLock = Any()
    private val rawPageLines = linkedMapOf<Int, Pair<String, String>>()
    @Volatile private var rawRangeStart: Int = 1
    @Volatile private var rawRangeEnd: Int = 0
    @Volatile private var rawTotalItems: Int = 0
    @Volatile private var rawPageUpdatedAt: Long = 0L
    @Volatile private var rawGdhUpdatedAt: Long = 0L
    @Volatile private var isUsbPlayingNow: Boolean = false

    private lateinit var usbPathText: TextView
    private lateinit var usbSelectedText: TextView
    private lateinit var usbStatusText: TextView
    private lateinit var usbListLayout: LinearLayout
    private lateinit var usbPlayBtn: Button
    private lateinit var usbStopBtn: Button
    private lateinit var usbPauseBtn: Button

    // ===== USB prehrávanie =====
    private enum class UsbState { STOPPED, PLAYING, PAUSED }
    @Volatile private var usbState = UsbState.STOPPED
    private var usbStartedKey: String? = null
    @Volatile private var usbBusy = false
    @Volatile private var usbScanning = false
    @Volatile private var lastErrAt = 0L
    private var usbUseIp: Boolean? = null
    private val usbCmdMutex = Mutex()

    // ===== DLNA =====
    private data class MediaEntry(
        val id: String, val title: String,
        val isContainer: Boolean, val url: String?
    )

    private var mediaServerDesc: String? = null
    private var mediaServerName: String = ""
    private var cdsControlUrl: String? = null
    private var avtControlUrl: String? = null
    private val browseStack = mutableListOf<Pair<String, String>>()
    private var currentListing = listOf<MediaEntry>()
    private var playQueue = listOf<MediaEntry>()
    private var queueIdx = -1
    private var queueJob: Job? = null

    private lateinit var mediaServerText: TextView
    private lateinit var mediaPathText: TextView
    private lateinit var mediaStatusText: TextView
    private lateinit var mediaList: LinearLayout

    // =====================================================================
    // ŽIVOTNÝ CYKLUS
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        val p = prefs()
        host = p.getString("host", host) ?: host
        mediaServerDesc = p.getString("dlnaServer", null)
        mediaServerName = p.getString("dlnaName", "") ?: ""
        isUsbMediaMode = p.getBoolean("isUsbMediaMode", true)
        logEv("=== štart aplikácie v22, host=$host ===")
        loadCachedFavorites()
        loadCachedUsbTree()
        buildUI()

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("pioneerSSDP").apply {
            setReferenceCounted(false); acquire()
        }

        autoConnectOrDiscoverOnStartup()

        if (mediaServerDesc != null) initMediaServer(mediaServerDesc!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
        runCatching { socketRef.get()?.close() }
        multicastLock?.release()
        sendDispatcher.close()
    }

    private fun prefs() = getSharedPreferences("pioneer", MODE_PRIVATE)

    private fun saveHostIp(newIp: String) {
        host = newIp.trim()
        prefs().edit().putString("host", host).apply()
        ui {
            if (::ipEdit.isInitialized && ipEdit.text.toString() != host) {
                ipEdit.setText(host)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadCachedFavorites() {
        val raw = prefs().getString("favStationsJson", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            favStations.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val idx = obj.getInt("idx")
                val name = obj.getString("name")
                if (!looksLikeMetadata(name)) {
                    favStations[idx] = name
                }
            }
        }
    }

    private fun saveCachedFavorites() {
        runCatching {
            val arr = JSONArray()
            favStations.forEach { (idx, name) ->
                val obj = JSONObject()
                obj.put("idx", idx)
                obj.put("name", name)
                arr.put(obj)
            }
            prefs().edit().putString("favStationsJson", arr.toString()).apply()
        }
    }

    private fun loadCachedUsbTree() {
        val raw = prefs().getString("usbTreeJsonV22", null) ?: return
        runCatching {
            val rootObj = JSONObject(raw)
            usbTreeCache.clear()
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = rootObj.getJSONArray(k)
                val list = mutableListOf<UsbNode>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        UsbNode(
                            indexInParent = o.getInt("idx"),
                            title = o.getString("title"),
                            isFolder = o.getBoolean("isFolder"),
                            pathKey = o.getString("pathKey")
                        )
                    )
                }
                usbTreeCache[k] = list
            }
        }
    }

    private fun saveCachedUsbTree() {
        runCatching {
            val rootObj = JSONObject()
            usbTreeCache.forEach { (k, list) ->
                val arr = JSONArray()
                list.forEach { node ->
                    val o = JSONObject()
                    o.put("idx", node.indexInParent)
                    o.put("title", node.title)
                    o.put("isFolder", node.isFolder)
                    o.put("pathKey", node.pathKey)
                    arr.put(o)
                }
                rootObj.put(k, arr)
            }
            prefs().edit().putString("usbTreeJsonV22", rootObj.toString()).apply()
        }
    }

    private fun looksLikeMetadata(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (Regex("(?i).*\\b(kbps|khz|wma|pcm|buffer)\\b.*").matches(t)) return true
        if (Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(t)) return true
        if (Regex("^\\d+\\s*%$").matches(t)) return true
        return false
    }

    private fun isLikelyUsbFolder(typeCode: String, title: String): Boolean {
        val lower = title.trim().lowercase()
        val audioExts = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".wma", ".ogg", ".alac", ".aif", ".aiff", ".pls", ".m3u", ".jpg", ".jpeg", ".png", ".cue", ".txt")
        if (audioExts.any { lower.endsWith(it) }) return false
        return typeCode == "00" || typeCode == "01"
    }

    // =====================================================================
    // ŠTÝLOVÉ POMOCNÍKY
    // =====================================================================

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(1), strokeColor)
        return d
    }

    private fun flatBtn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 12f
        b.maxLines = 2
        b.setTextColor(C_WHITE)
        b.transformationMethod = null
        b.background = roundedBg(C_BTN, 12, C_STROKE)
        b.setPadding(dp(4), dp(8), dp(4), dp(8))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun pillBtn(label: String, bg: Int, fg: Int, onClick: (Button) -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 13f
        b.maxLines = 1
        b.gravity = Gravity.CENTER
        b.setTextColor(fg)
        b.transformationMethod = null
        b.background = roundedBg(bg, 10)
        b.setPadding(dp(12), 0, dp(12), 0)
        b.minimumWidth = 0
        b.minimumHeight = 0
        b.setOnClickListener { onClick(b) }
        return b
    }

    private fun highlightBtn(b: Button, active: Boolean, accent: Int = C_CYAN) {
        b.background = roundedBg(if (active) accent else C_BTN, 12, if (active) accent else C_STROKE)
        b.setTextColor(if (active) C_ON_ACCENT else C_WHITE)
        b.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rowOf(buttons: List<Button>): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        buttons.forEach {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            r.addView(it, lp)
