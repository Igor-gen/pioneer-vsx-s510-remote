// ============================================================================
// Pioneer VSX-S510 Remote — verzia 23
// Úpravy:
// 1. Zjednodušené UI pre USB: ponechané iba funkčné tlačidlá ▶ PLAY a ⏹ STOP
//    (odstránené nefunkčné Pauza, Predch., Ďalšia, Repeat).
// 2. ▶ PLAY funguje okamžite na 1 kliknutie (vždy zastaví predchádzajúcu skladbu a spustí novú).
// 3. ⏹ STOP opravené: vykoná garantované zastavenie prehrávania (prepnutie 06FN -> 17FN),
//    pretože samotné 02IP/20NW firmware VSX-S510 pri FLAC/MP3 z USB ignoruje.
// 4. Tlačidlo "🔄 Načítať USB" je zmenšené a chránené potvrdzovacím dialógom proti nechcenému kliknutiu.
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
        val header = "Pioneer Remote v23 | host=$host | vstup=$activeInput" +
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

    private lateinit var usbScanAllBtn: Button
    private lateinit var usbPathText: TextView
    private lateinit var usbSelectedText: TextView
    private lateinit var usbStatusText: TextView
    private lateinit var usbListLayout: LinearLayout
    private lateinit var usbPlayBtn: Button
    private lateinit var usbStopBtn: Button

    // ===== USB prehrávanie =====
    private enum class UsbState { STOPPED, PLAYING }
    @Volatile private var usbState = UsbState.STOPPED
    private var usbStartedKey: String? = null
    @Volatile private var usbBusy = false
    @Volatile private var usbScanning = false
    @Volatile private var lastErrAt = 0L

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
        logEv("=== štart aplikácie v23, host=$host ===")
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
        }
        return r
    }

    private fun makeGrid(buttons: List<Button>, cols: Int = 3): GridLayout {
        val g = GridLayout(this)
        g.columnCount = cols
        g.useDefaultMargins = true
        buttons.forEach { b ->
            val lp = GridLayout.LayoutParams()
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.width = 0
            g.addView(b, lp)
        }
        return g
    }

    private fun sectionLabel(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 13f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(C_SLATE)
        tv.setPadding(0, dp(10), 0, dp(4))
        return tv
    }

    private fun hint(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 10f
        tv.setTextColor(C_DIM)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(12), 0, dp(8))
        return tv
    }

    // =====================================================================
    // VYTVORENIE UI
    // =====================================================================

    private fun buildUI() {
        val pad = dp(12)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)
        root.setPadding(pad, dp(8), pad, 0)

        val header = FrameLayout(this)
        header.setPadding(0, 0, 0, dp(6))

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.gravity = Gravity.CENTER_HORIZONTAL

        val title = TextView(this)
        title.text = "Pioneer Remote"
        title.textSize = 19f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(C_WHITE)
        title.gravity = Gravity.CENTER
        titleBox.addView(title)

        val sub = TextView(this)
        sub.text = "VSX-S510"
        sub.textSize = 11f
        sub.setTextColor(C_CYAN)
        sub.gravity = Gravity.CENTER
        titleBox.addView(sub)

        header.addView(titleBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        val circleSize = dp(40)
        powerBtn = Button(this)
        powerBtn.textSize = 11f
        powerBtn.typeface = Typeface.DEFAULT_BOLD
        powerBtn.gravity = Gravity.CENTER
        powerBtn.transformationMethod = null
        powerBtn.setPadding(0, 0, 0, 0)
        powerBtn.minimumWidth = 0
        powerBtn.minimumHeight = 0
        powerBtn.setOnClickListener { togglePower() }
        updatePowerButton()

        header.addView(powerBtn, FrameLayout.LayoutParams(
            circleSize,
            circleSize,
            Gravity.END or Gravity.CENTER_VERTICAL
        ))

        root.addView(header)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        ipEdit = EditText(this)
        ipEdit.setText(host)
        ipEdit.hint = "IP adresa receivera"
        ipEdit.inputType = InputType.TYPE_CLASS_PHONE
        ipEdit.setTextColor(C_WHITE)
        ipEdit.setHintTextColor(C_DIM)
        ipEdit.textSize = 12f
        ipEdit.background = roundedBg(C_CARD2, 10, C_STROKE)
        ipEdit.setPadding(dp(10), dp(6), dp(10), dp(6))
        top.addView(ipEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val scanBtn = pillBtn("🔍", C_BTN, C_WHITE) { discoverReceivers(autoConnectFirst = false) }
        top.addView(scanBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val connBtn = pillBtn("Pripoj", C_CYAN, C_ON_ACCENT) { connectPressed() }
        top.addView(connBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.background = roundedBg(C_CARD, 18, C_CYAN_D)

        mainStationBig = TextView(this)
        mainStationBig.text = "📻  —"
        mainStationBig.textSize = 20f
        mainStationBig.typeface = Typeface.DEFAULT_BOLD
        mainStationBig.setTextColor(C_WHITE)
        mainStationBig.gravity = Gravity.CENTER
        mainStationBig.maxLines = 2

        mainShowBitrate = TextView(this)
        mainShowBitrate.text = "Relácia / Skladba: —   ·   Bitrate: —"
        mainShowBitrate.textSize = 12f
        mainShowBitrate.setTextColor(C_CYAN)
        mainShowBitrate.gravity = Gravity.CENTER
        mainShowBitrate.setPadding(0, dp(2), 0, dp(2))

        infoLine = TextView(this)
        infoLine.text = "Vstup: —   ·   Režim: —"
        infoLine.textSize = 12f
        infoLine.setTextColor(C_SLATE)
        infoLine.gravity = Gravity.CENTER

        connLine = TextView(this)
        connLine.text = "○  Odpojené ($host)"
        connLine.textSize = 11f
        connLine.setTextColor(C_RED)
        connLine.gravity = Gravity.CENTER

        statusText = TextView(this)
        statusText.text = ""
        statusText.textSize = 11f
        statusText.setTextColor(C_ORANGE)
        statusText.gravity = Gravity.CENTER

        val volBox = LinearLayout(this)
        volBox.orientation = LinearLayout.HORIZONTAL
        volBox.gravity = Gravity.CENTER_VERTICAL
        volBox.setPadding(dp(6), dp(8), dp(6), dp(2))

        volumeLabel = TextView(this)
        volumeLabel.text = "🔊 —"
        volumeLabel.textSize = 13f
        volumeLabel.typeface = Typeface.DEFAULT_BOLD
        volumeLabel.setTextColor(C_GREEN)
        volumeLabel.setPadding(0, 0, dp(10), 0)

        volBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        volBar.max = volMax
        volBar.progress = 0
        volBar.progressTintList = ColorStateList.valueOf(C_GREEN)
        volBar.progressBackgroundTintList = ColorStateList.valueOf(C_STROKE)
        volBar.isClickable = false
        volBar.isFocusable = false

        val barLp = LinearLayout.LayoutParams(0, dp(10), 1f)
        volBox.addView(volumeLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        volBox.addView(volBar, barLp)

        card.addView(mainStationBig)
        card.addView(mainShowBitrate)
        card.addView(infoLine)
        card.addView(connLine)
        card.addView(statusText)
        card.addView(volBox)

        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.topMargin = dp(6)
        root.addView(card, cardLp)

        val tabDefs = listOf("🎚 OVLÁDANIE", "📻 RÁDIO", "💽 MÉDIÁ")
        val tabBtns = tabDefs.map { t -> pillBtn(t, C_BTN, C_SLATE) { } }
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabBtns.forEach { b ->
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val tabsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tabsLp.topMargin = dp(6)
        root.addView(tabs, tabsLp)

        val pager = ViewPager2(this)
        pager.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val screens = listOf(buildScreenHome(), buildScreenRadio(), buildScreenMedia())
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = screens.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(FrameLayout(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val frame = holder.itemView as FrameLayout
                frame.removeAllViews()
                frame.addView(screens[position])
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabBtns.forEachIndexed { i, b -> styleTab(b, i == position) }
                if (position == 2 && isUsbMediaMode) {
                    renderUsbCachedFolder()
                }
            }
        })
        tabBtns.forEachIndexed { i, b ->
            b.setOnClickListener { pager.currentItem = i }
        }
        styleTab(tabBtns[0], true)

        muteBtn = flatBtn("🔇 MUTE") { toggleMute() }
        val vstupBtn = flatBtn("VSTUP ▶") { send("FU"); sendSilent("?F") }
        muteBtn.textSize = 13f; vstupBtn.textSize = 13f
        val muteLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        muteLp.topMargin = dp(6)
        root.addView(rowOf(listOf(muteBtn, vstupBtn)), muteLp)

        val volMinus = flatBtn("VOL −") { send("VD"); sendSilent("?V") }
        val volPlus = flatBtn("VOL +") { send("VU"); sendSilent("?V") }
        volMinus.textSize = 13f; volPlus.textSize = 13f
        root.addView(rowOf(listOf(volMinus, volPlus)))

        root.addView(pager)
        setContentView(root)
    }

    private fun styleTab(btn: Button, active: Boolean) {
        btn.background = roundedBg(
            if (active) C_ORANGE else C_BTN, 10, if (active) C_ORANGE else C_STROKE)
        btn.setTextColor(if (active) C_ON_ACCENT else C_SLATE)
        btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun connectPressed() {
        val entered = ipEdit.text.toString().trim()
        if (entered.isNotEmpty()) {
            saveHostIp(entered)
        }
        avtControlUrl = null
        connect(silentFail = false)
    }

    // =====================================================================
    // SCREEN 1 — OVLÁDANIE
    // =====================================================================

    private fun buildScreenHome(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(4), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        page.addView(sectionLabel("VÝBER VSTUPU"))
        val ib = mutableListOf<Button>()
        inputs.forEach { (name, code) ->
            val b = flatBtn(name) {
                if (code != "45FN") isPlayingFavorite = false
                send(code)
                sendSilent("?F")
                activeInput = name
                updateInfoLine()
                styleInputButtons()
                updateMainDisplayHeader()
            }
            inputButtons[name] = b
            ib.add(b)
        }
        page.addView(makeGrid(ib, 3))

        page.addView(sectionLabel("PRIESTOROVÝ REŽIM (SURROUND)"))
        val mb = mutableListOf<Button>()
        modes.forEach { (name, code) ->
            val b = flatBtn(name) {
                setListeningMode(name, code)
            }
            modeButtons[name] = b
            mb.add(b)
        }
        page.addView(makeGrid(mb, 2))
        page.addView(hint("← swipe na rádio →"))
        return sv
    }

    private fun setListeningMode(name: String, code: String) {
        activeMode = name
        updateInfoLine()
        styleModeButtons()
        scope.launch(Dispatchers.IO) {
            sendSilent(code)
            delay(250)
            sendSilent("?S")
            sendSilent("?L")
        }
    }

    // =====================================================================
    // SCREEN 2 — RÁDIO
    // =====================================================================

    private fun buildScreenRadio(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        favToggleBtn = flatBtn("⭐ FAVORITES (Načítať predvoľby ▼)") {
            onFavoritesButtonClicked()
        }
        favToggleBtn.background = roundedBg(C_CYAN_D, 12, C_CYAN)
        favToggleBtn.setTextColor(C_WHITE)
        favToggleBtn.typeface = Typeface.DEFAULT_BOLD
        favToggleBtn.textSize = 14f
        favToggleBtn.setPadding(dp(12), dp(12), dp(12), dp(12))

        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        btnLp.setMargins(dp(2), dp(4), dp(2), dp(4))
        page.addView(favToggleBtn, btnLp)

        favDrawerCard = LinearLayout(this)
        favDrawerCard.orientation = LinearLayout.VERTICAL
        favDrawerCard.setPadding(dp(10), dp(10), dp(10), dp(10))
        favDrawerCard.background = roundedBg(C_CARD, 14, C_CYAN)
        val drawerLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        drawerLp.topMargin = dp(6)
        favDrawerCard.layoutParams = drawerLp
        favDrawerCard.visibility = View.GONE

        favDrawerStatus = TextView(this)
        favDrawerStatus.text = "Vyber predvoľbu zo zoznamu receivera:"
        favDrawerStatus.textSize = 12f
        favDrawerStatus.setTextColor(C_CYAN)
        favDrawerStatus.setPadding(dp(4), 0, dp(4), dp(6))
        favDrawerCard.addView(favDrawerStatus)

        favTableContainer = LinearLayout(this)
        favTableContainer.orientation = LinearLayout.VERTICAL
        favDrawerCard.addView(favTableContainer)

        page.addView(favDrawerCard)
        renderFavoriteTable()

        page.addView(hint("← swipe na ovládanie / médiá →"))
        return sv
    }

    private fun renderFavoriteTable() {
        if (!::favTableContainer.isInitialized) return
        favTableContainer.removeAllViews()
        favButtons.clear()

        val entriesToRender = if (favStations.isNotEmpty()) {
            favStations.entries.sortedBy { it.key }.map { it.key to it.value }
        } else {
            (1..8).map { it to "Predvoľba $it" }
        }

        entriesToRender.forEach { (idx, name) ->
            val b = flatBtn("$idx.  📻  $name") {
                onFavoritePresetSelected(idx, name)
            }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 13f
            b.setPadding(dp(14), dp(10), dp(14), dp(10))
            highlightBtn(b, idx == activeFavIndex)
            favButtons[idx] = b

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            favTableContainer.addView(b, lp)
        }
    }

    private fun setFavDrawerExpanded(expanded: Boolean) {
        isFavListExpanded = expanded
        if (::favDrawerCard.isInitialized) {
            favDrawerCard.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        if (::favToggleBtn.isInitialized) {
            favToggleBtn.text = if (expanded)
                "⭐ FAVORITES (Zoznam otvorený ▲)"
            else
                "⭐ FAVORITES (Načítať predvoľby ▼)"
        }
    }

    private fun onFavoritesButtonClicked() {
        favJob?.cancel()
        setFavDrawerExpanded(true)
        favDrawerStatus.text = "⏳ Prepínam NETRADIO → FAVORITES a načítavam predvoľby z receivera…"
        renderFavoriteTable()

        favJob = scope.launch(Dispatchers.IO) {
            try {
                isSyncingFavorites = true
                isPlayingFavorite = false
                listScreenReady = false

                if (!isOn) {
                    sendSilent("PO")
                    delay(2500)
                }

                sendSilent("38FN")
                ui {
                    activeInput = "NETRADIO"
                    updateInfoLine()
                    styleInputButtons()
                }
                delay(700)

                sendSilent("45FN")
                sendSilent("?F")
                ui {
                    activeInput = "FAVORITES"
                    updateInfoLine()
                    styleInputButtons()
                }

                for (step in 0 until 12) {
                    delay(200)
                    if (step == 3 || step == 7) {
                        sendSilent("?GAP")
                        sendSilent("?GAH")
                    }
                    if (listScreenReady) break
                }

                sendSilent("?GAP")
                sendSilent("?GAH")
                sendSilent("?FL")
                delay(700)
                sendSilent("?GAP")
                delay(400)

                ui {
                    favDrawerStatus.text = "Vyber predvoľbu (po kliknutí sa zoznam zasunie a stanica začne hrať):"
                    renderFavoriteTable()
                }
            } finally {
                isSyncingFavorites = false
            }
        }
    }

    private fun onFavoritePresetSelected(index: Int, name: String) {
        favJob?.cancel()
        activeFavIndex = index
        val stationTitle = favStations[index] ?: name
        activeStationName = stationTitle

        setFavDrawerExpanded(false)

        playMetaLines.clear()
        playTimeText = ""
        updateMainDisplayHeader()

        favJob = scope.launch(Dispatchers.IO) {
            isSyncingFavorites = false
            isPlayingFavorite = true

            val ghpCmd = String.format("%02dGHP", index)
            sendSilent(ghpCmd)
            delay(350)
            sendSilent("30NW")
            sendSilent("10NW")

            delay(1500)
            sendSilent("?GAP")
            sendSilent("?GAH")
            sendSilent("?FL")
            delay(1500)
            sendSilent("?GAP")
            sendSilent("?FL")
        }
    }

    // =====================================================================
    // SCREEN 3 — MÉDIÁ (PREPÍNAČ: 🔌 USB KĽÚČ / 🌐 DLNA SERVER)
    // =====================================================================

    private fun buildScreenMedia(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        modeUsbBtn = flatBtn("🔌 USB KĽÚČ") {
            switchMediaMode(usbMode = true)
        }
        modeDlnaBtn = flatBtn("🌐 DLNA SERVER") {
            switchMediaMode(usbMode = false)
        }
        modeUsbBtn.textSize = 13f
        modeDlnaBtn.textSize = 13f
        val switchRow = rowOf(listOf(modeUsbBtn, modeDlnaBtn))
        val switchLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        switchLp.bottomMargin = dp(6)
        page.addView(switchRow, switchLp)

        // =================================================================
        // KONTAJNER 1: 🔌 USB KĽÚČ (IBA PLAY, STOP A MENŠIE TLAČIDLÁ NÁSTROJOV)
        // =================================================================
        usbContainer = LinearLayout(this)
        usbContainer.orientation = LinearLayout.VERTICAL

        // Hlavné tlačidlá: iba ▶ PLAY a ⏹ STOP
        usbPlayBtn = flatBtn("▶ PLAY") { onUsbPlayPressed() }
        usbPlayBtn.background = roundedBg(C_GREEN, 12, C_GREEN)
        usbPlayBtn.setTextColor(C_ON_ACCENT)
        usbPlayBtn.typeface = Typeface.DEFAULT_BOLD
        usbPlayBtn.textSize = 14f
        usbPlayBtn.setPadding(dp(8), dp(12), dp(8), dp(12))

        usbStopBtn = flatBtn("⏹ STOP") { stopUsbPlayback() }
        usbStopBtn.background = roundedBg(C_RED, 12, C_RED)
        usbStopBtn.setTextColor(C_WHITE)
        usbStopBtn.typeface = Typeface.DEFAULT_BOLD
        usbStopBtn.textSize = 14f
        usbStopBtn.setPadding(dp(8), dp(12), dp(8), dp(12))

        usbContainer.addView(rowOf(listOf(usbPlayBtn, usbStopBtn)))

        // Kompaktný riadok nástrojov: menšie tlačidlo "🔄 Načítať USB" (s potvrdením) + Log tlačidlá
        usbScanAllBtn = flatBtn("🔄 Načítať USB") {
            onUsbScanButton()
        }
        usbScanAllBtn.textSize = 11f
        usbScanAllBtn.setTextColor(C_SLATE)
        usbScanAllBtn.background = roundedBg(C_CARD2, 10, C_STROKE)
        usbScanAllBtn.setPadding(dp(4), dp(6), dp(4), dp(6))

        val logShareBtn = flatBtn("📤 Odoslať LOG") { shareLog() }
        logShareBtn.textSize = 11f
        logShareBtn.setTextColor(C_SLATE)
        logShareBtn.background = roundedBg(C_CARD2, 10, C_STROKE)
        logShareBtn.setPadding(dp(4), dp(6), dp(4), dp(6))

        val logClearBtn = flatBtn("🗑 Vymazať LOG") { clearLog() }
        logClearBtn.textSize = 11f
        logClearBtn.setTextColor(C_SLATE)
        logClearBtn.background = roundedBg(C_CARD2, 10, C_STROKE)
        logClearBtn.setPadding(dp(4), dp(6), dp(4), dp(6))

        usbContainer.addView(rowOf(listOf(usbScanAllBtn, logShareBtn, logClearBtn)))

        usbPathText = TextView(this)
        usbPathText.text = "📂 Adresár: / (Koreň USB)"
        usbPathText.textSize = 12f
        usbPathText.typeface = Typeface.DEFAULT_BOLD
        usbPathText.setTextColor(C_CYAN)
        usbPathText.setPadding(dp(4), dp(8), dp(4), dp(2))
        usbContainer.addView(usbPathText)

        usbSelectedText = TextView(this)
        usbSelectedText.text = "✓ Označené: (nič) — ☐ označí adresár/súbor, klik na adresár ho otvorí"
        usbSelectedText.textSize = 12f
        usbSelectedText.setTextColor(C_ORANGE)
        usbSelectedText.setPadding(dp(4), 0, dp(4), dp(2))
        usbContainer.addView(usbSelectedText)

        usbStatusText = TextView(this)
        usbStatusText.text = if (usbTreeCache.isNotEmpty())
            "Obsah USB je načítaný v pamäti (${usbTreeCache.values.sumOf { it.size }} položiek)."
        else
            "Klikni na 🔄 Načítať USB pre zoskenovanie kľúča."
        usbStatusText.textSize = 11f
        usbStatusText.setTextColor(C_SLATE)
        usbStatusText.setPadding(dp(4), 0, dp(4), dp(6))
        usbContainer.addView(usbStatusText)

        usbListLayout = LinearLayout(this)
        usbListLayout.orientation = LinearLayout.VERTICAL
        usbContainer.addView(usbListLayout)
        renderUsbCachedFolder()

        page.addView(usbContainer)

        // =================================================================
        // KONTAJNER 2: 🌐 DLNA SERVER
        // =================================================================
        dlnaContainer = LinearLayout(this)
        dlnaContainer.orientation = LinearLayout.VERTICAL

        val srvBtn = flatBtn("🔍 SERVERY") { scanMediaServers() }
        val backBtn = flatBtn("⟨ SPÄŤ") { browseBack() }
        val albumBtn = flatBtn("▶ ALBUM") { playCurrentFolder() }
        dlnaContainer.addView(rowOf(listOf(srvBtn, backBtn, albumBtn)))

        mediaServerText = TextView(this)
        mediaServerText.text = if (mediaServerName.isNotEmpty())
            "Server: $mediaServerName" else "Server: nevybraný — klikni 🔍 SERVERY"
        mediaServerText.textSize = 12f
        mediaServerText.setTextColor(C_SLATE)
        mediaServerText.setPadding(0, dp(8), 0, 0)
        dlnaContainer.addView(mediaServerText)

        mediaPathText = TextView(this)
        mediaPathText.text = "📚 /"
        mediaPathText.textSize = 12f
        mediaPathText.setTextColor(C_SLATE)
        dlnaContainer.addView(mediaPathText)

        mediaStatusText = TextView(this)
        mediaStatusText.text = ""
        mediaStatusText.textSize = 13f
        mediaStatusText.setTextColor(C_CYAN)
        mediaStatusText.setPadding(0, dp(8), 0, dp(8))
        dlnaContainer.addView(mediaStatusText)

        mediaList = LinearLayout(this)
        mediaList.orientation = LinearLayout.VERTICAL
        dlnaContainer.addView(mediaList)

        page.addView(dlnaContainer)

        switchMediaMode(usbMode = isUsbMediaMode)
        page.addView(hint("← swipe na rádio →"))
        return sv
    }

    private fun switchMediaMode(usbMode: Boolean) {
        isUsbMediaMode = usbMode
        prefs().edit().putBoolean("isUsbMediaMode", usbMode).apply()

        if (::modeUsbBtn.isInitialized && ::modeDlnaBtn.isInitialized) {
            highlightBtn(modeUsbBtn, usbMode, C_CYAN)
            highlightBtn(modeDlnaBtn, !usbMode, C_CYAN)
        }
        if (::usbContainer.isInitialized && ::dlnaContainer.isInitialized) {
            usbContainer.visibility = if (usbMode) View.VISIBLE else View.GONE
            dlnaContainer.visibility = if (!usbMode) View.VISIBLE else View.GONE
        }
    }

    // =====================================================================
    // USB NAVIGÁCIA NA RECEIVERI
    // =====================================================================

    private fun currentUsbPathKey(): String {
        return if (usbCurrentPath.isEmpty()) "/" else "/" + usbCurrentPath.joinToString("/") { it.first.toString() }
    }

    private fun currentUsbPathDisplay(): String {
        return if (usbCurrentPath.isEmpty()) "/ (Koreň USB)" else "/" + usbCurrentPath.joinToString("/") { it.second }
    }

    @Volatile private var rxGbp = 0
    @Volatile private var rxScreenAt = 0L
    private var recvPath: List<Int>? = null
    private var usbUpCmd: String? = null
    private var usbUpFails = 0

    private fun usbOnList(): Boolean = nwScreenType == "01" || nwScreenType == "00"

    private fun usbAtRoot(): Boolean =
        usbOnList() && nwScreenName.trim().equals("USB", ignoreCase = true)

    private fun usbStopBoth() {
        sendSilent("02IP")
        sendSilent("20NW")
    }

    private suspend fun usbSnapshot(timeoutMs: Long = 1400): Boolean {
        val t0 = System.currentTimeMillis()
        sendSilent("?GAP")
        var waited = 0L
        while (waited < timeoutMs) {
            delay(50)
            waited += 50
            if (rxScreenAt >= t0) {
                var extra = 0L
                while (extra < 400) {
                    val count = synchronized(pageLock) { rawPageLines.size }
                    if (!usbOnList() || (rxGbp > 0 && count >= rxGbp)) break
                    delay(40)
                    extra += 40
                }
                delay(60)
                return true
            }
        }
        return false
    }

    private suspend fun usbTryUp(): Boolean {
        val titleBefore = nwScreenName
        val candidates = (listOfNotNull(usbUpCmd) + listOf("18IP", "31NW", "19IP")).distinct()
        for (c in candidates) {
            sendSilent(c)
            delay(450)
            usbSnapshot()
            val changed = usbOnList() && nwScreenName != titleBefore
            logEv("up: $c -> obrazovka=$nwScreenType názov='$nwScreenName' (pred: '$titleBefore') zmena=$changed")
            if (changed) {
                usbUpCmd = c
                return true
            }
            if (!usbOnList()) return false
        }
        return false
    }

    private suspend fun usbGoRoot(): Boolean {
        usbSnapshot()
        logEv("goRoot: obrazovka=$nwScreenType gbp=$rxGbp názov='$nwScreenName'")
        if (usbAtRoot()) return true

        if (!usbOnList()) {
            usbStopBoth()
            delay(300)
            for (c in listOf("19IP", "18IP", "31NW")) {
                sendSilent(c)
                delay(450)
                usbSnapshot()
                logEv("goRoot: po $c obrazovka=$nwScreenType gbp=$rxGbp názov='$nwScreenName'")
                if (usbOnList()) break
            }
        }

        var guard = 0
        while (usbOnList() && !usbAtRoot() && guard < 8 && usbUpFails < 2) {
            if (!usbTryUp()) {
                usbUpFails++
                break
            }
            guard++
        }
        if (usbAtRoot()) return true

        // Prepnutie vstupu (06FN -> 17FN), ktoré 100% spoľahlivo zastaví prehrávanie a vráti do koreňa "USB"
        logEv("goRoot: núdzové prepnutie vstupu (06FN -> 17FN)")
        sendSilent("06FN")
        delay(1000)
        val tSwitch = System.currentTimeMillis()
        sendSilent("17FN")

        var w = 0L
        while (w < 2200 && rxScreenAt < tSwitch) {
            delay(60)
            w += 60
        }
        delay(350)
        usbSnapshot()
        if (!usbOnList()) {
            usbStopBoth()
            delay(300)
            sendSilent("19IP")
            delay(500)
            usbSnapshot()
        }
        logEv("goRoot: po prepnutí obrazovka=$nwScreenType gbp=$rxGbp názov='$nwScreenName'")
        return usbAtRoot()
    }

    private suspend fun exitUsbPlayScreenToRootList(): Boolean {
        logEv("exit→root: vstup=$activeInput obrazovka=$nwScreenType zapnuté=$isOn")
        isPlayingFavorite = false
        isUsbPlayingNow = false
        recvPath = null

        if (!isOn) {
            sendSilent("PO")
            delay(2500)
        }
        if (activeInput != "IPOD/USB" && activeInput != "17FN") {
            ui {
                activeInput = "IPOD/USB"
                updateInfoLine()
                styleInputButtons()
                updateMainDisplayHeader()
            }
            sendSilent("17FN")
            sendSilent("?F")
            delay(1800)
        }
        val ok = usbGoRoot()
        recvPath = if (ok) emptyList() else null
        logEv("exit→root: výsledok=$ok")
        return ok
    }

    private suspend fun usbSelectItem(absIndex: Int, expectedTitle: String? = null): Boolean {
        val folderBefore = nwScreenName

        val tGgp = System.currentTimeMillis()
        sendSilent(String.format("%05dGGP", absIndex))
        var w = 0
        while (w < 1000 && rawGdhUpdatedAt < tGgp) {
            delay(50)
            w += 50
        }
        delay(180)

        val lineInWindow = absIndex - rawRangeStart + 1
        logEv("select: položka $absIndex (očakávaná='${expectedTitle ?: "?"}') okno=$rawRangeStart..$rawRangeEnd -> riadok $lineInWindow")

        if (absIndex in 1..8) {
            val tSel = System.currentTimeMillis()
            sendSilent(String.format("%02dGHP", absIndex))
            var ws = 0
            while (ws < 1200 && rxScreenAt < tSel) {
                delay(50)
                ws += 50
            }
            delay(200)
            return true
        }

        if (absIndex in 9..99) {
            val tAbs = System.currentTimeMillis()
            sendSilent(String.format("%02dGHP", absIndex))
            var ws = 0
            while (ws < 1000 && rxScreenAt < tAbs && lastErrAt < tAbs) {
                delay(50)
                ws += 50
            }
            delay(180)

            if (lastErrAt < tAbs && rxScreenAt >= tAbs) {
                if (!usbOnList() || expectedTitle == null || nwScreenName.equals(expectedTitle, ignoreCase = true)) {
                    logEv("select: úspech cez absolútne ${String.format("%02dGHP", absIndex)} -> '$nwScreenName'")
                    return true
                }
            }
        }

        sendSilent(String.format("%05dGGP", absIndex))
        delay(220)
        for (enterCmd in listOf("17IP", "30NW", "10NW", "00IP")) {
            val tEnt = System.currentTimeMillis()
            sendSilent(enterCmd)
            var ws = 0
            while (ws < 900 && rxScreenAt < tEnt && lastErrAt < tEnt) {
                delay(50)
                ws += 50
            }
            delay(150)
            if (lastErrAt < tEnt && rxScreenAt >= tEnt && nwScreenName != folderBefore) {
                if (!usbOnList() || expectedTitle == null || nwScreenName.equals(expectedTitle, ignoreCase = true)) {
                    logEv("select: úspech cez GGP + $enterCmd -> '$nwScreenName'")
                    return true
                }
            }
        }

        logEv("select: skúšam posun okna kurzorom pre položku $absIndex")
        val targetPage = (absIndex - 1) / 8
        for (p in 0 until targetPage) {
            sendSilent("27NW")
            delay(150)
        }
        usbSnapshot(800)
        if (absIndex in rawRangeStart..rawRangeEnd) {
            val row = absIndex - rawRangeStart + 1
            val tRow = System.currentTimeMillis()
            sendSilent(String.format("%02dGHP", row))
            var ws = 0
            while (ws < 1000 && rxScreenAt < tRow) {
                delay(50)
                ws += 50
            }
            delay(200)
            return true
        }

        return false
    }

    private suspend fun usbEnsureAt(target: List<Int>): Boolean {
        val cur = recvPath
        if (cur != null && cur == target) return true

        if (cur != null && cur.size == target.size + 1 &&
            cur.subList(0, target.size) == target && usbUpFails < 2) {
            if (usbTryUp()) {
                recvPath = target
                return true
            }
            usbUpFails++
        }

        if (!usbGoRoot()) {
            recvPath = null
            return false
        }
        recvPath = emptyList()
        val walked = mutableListOf<Int>()
        for (idx in target) {
            currentCoroutineContext().ensureActive()
            val parentKey = if (walked.isEmpty()) "/" else "/" + walked.joinToString("/")
            val expectedNode = usbTreeCache[parentKey]?.firstOrNull { it.indexInParent == idx }
            if (!usbSelectItem(idx, expectedNode?.title)) {
                recvPath = null
                return false
            }
            usbSnapshot()
            if (!usbOnList()) {
                recvPath = null
                return false
            }
            walked.add(idx)
            recvPath = walked.toList()
        }
        return true
    }

    private suspend fun fetchAllItemsInCurrentReceiverFolder(parentKey: String): MutableList<UsbNode> {
        val result = mutableListOf<UsbNode>()
        usbSnapshot()
        if (!usbOnList()) {
            logEv("fetch: nie sme v zozname (obrazovka=$nwScreenType)")
            return result
        }
        val total = rawTotalItems
        logEv("fetch: $parentKey celkom=$total")
        if (total <= 0) return result

        var pageStart = 1
        while (pageStart <= total) {
            currentCoroutineContext().ensureActive()
            val expectedCountOnPage = (total - pageStart + 1).coerceIn(1, 8)

            synchronized(pageLock) {
                rawPageLines.clear()
            }
            val t0 = System.currentTimeMillis()
            sendSilent(String.format("%05dGGP", pageStart))

            var w = 0
            while (w < 1600) {
                val count = synchronized(pageLock) { rawPageLines.size }
                if (rawGdhUpdatedAt >= t0 && rawRangeStart == pageStart && count >= expectedCountOnPage) {
                    break
                }
                delay(40)
                w += 40
            }
            delay(80)

            val start = rawRangeStart
            val snapshot = synchronized(pageLock) { LinkedHashMap(rawPageLines) }
            for (lineIdx in 1..8) {
                val pair = snapshot[lineIdx] ?: continue
                val absIdx = start + lineIdx - 1
                if (absIdx > total) break
                val typeCode = pair.first
                val title = pair.second.trim()
                if (title.isEmpty()) continue
                val isFolder = isLikelyUsbFolder(typeCode, title)
                val nodeKey = if (parentKey == "/") "/$absIdx" else "$parentKey/$absIdx"
                if (result.none { it.indexInParent == absIdx }) {
                    result.add(UsbNode(absIdx, title, isFolder, nodeKey))
                }
            }
            pageStart += 8
        }
        return result
    }

    // =====================================================================
    // USB: SPRÁVA ÚLOH, SKENOVANIE (S POTVRDENÍM) A PREHRÁVANIE (PLAY / STOP)
    // =====================================================================

    private fun launchUsb(block: suspend CoroutineScope.() -> Unit) {
        val prev = usbJob
        usbJob = scope.launch(Dispatchers.IO) {
            prev?.cancelAndJoin()
            usbBusy = true
            try {
                block(this)
            } finally {
                usbBusy = false
            }
        }
    }

    private fun updateScanButtonUi() {
        if (!::usbScanAllBtn.isInitialized) return
        if (usbScanning) {
            usbScanAllBtn.text = "✖ Zrušiť sken"
            usbScanAllBtn.setTextColor(C_ORANGE)
        } else {
            usbScanAllBtn.text = "🔄 Načítať USB"
            usbScanAllBtn.setTextColor(C_SLATE)
        }
    }

    // Kliknutie na menšie tlačidlo "🔄 Načítať USB" — vždy si vypýta potvrdenie
    private fun onUsbScanButton() {
        if (usbScanning) {
            AlertDialog.Builder(this)
                .setTitle("Zrušiť načítavanie USB?")
                .setMessage("Naozaj chcete prerušiť prebiehajúce načítavanie USB kľúča?")
                .setPositiveButton("Áno, zrušiť") { _, _ ->
                    usbJob?.cancel()
                    usbScanning = false
                    updateScanButtonUi()
                    usbStatusText.text = "Načítavanie zrušené. Pôvodný obsah v pamäti ostal nezmenený."
                }
                .setNegativeButton("Pokračovať", null)
                .show()
        } else {
            val msg = if (usbTreeCache.isNotEmpty()) {
                "V pamäti je už uložených ${usbTreeCache.size} adresárov. Naozaj chcete znova načítať celý USB kľúč? (Prehrávanie sa zastaví a sken potrvá cca 1–2 minúty.)"
            } else {
                "Chcete spustiť načítanie celého obsahu USB kľúča do pamäte?"
            }
            AlertDialog.Builder(this)
                .setTitle("Načítať USB kľúč nanovo?")
                .setMessage(msg)
                .setPositiveButton("Áno, načítať") { _, _ ->
                    scanEntireUsbTree()
                }
                .setNegativeButton("Zrušiť", null)
                .show()
        }
    }

    private suspend fun scanUsbFolderRecursive(
        cache: MutableMap<String, MutableList<UsbNode>>,
        path: List<Int>,
        parentKey: String,
        parentTitle: String,
        depth: Int,
        counter: IntArray
    ) {
        val items = fetchAllItemsInCurrentReceiverFolder(parentKey)
        logEv("scan: adresár $parentKey ('$parentTitle') -> ${items.size} položiek: " +
            items.joinToString(" | ") { "${it.indexInParent}:${if (it.isFolder) "D" else "S"}:${it.title}" })
        cache[parentKey] = items
        counter[0] += items.size
        ui {
            usbStatusText.text = "⏳ Načítavam USB… adresárov: ${counter[1] + 1}, položiek: ${counter[0]}" +
                "  (teraz: $parentTitle)"
        }
        if (depth >= 8) return

        for (i in items.indices) {
            currentCoroutineContext().ensureActive()
            val node = items[i]
            if (!node.isFolder) continue

            if (!usbEnsureAt(path)) {
                logEv("scan: nepodarilo sa vrátiť do $parentKey, preskakujem ${node.pathKey}")
                continue
            }
            logEv("scan: otváram ${node.pathKey} '${node.title}'")
            if (!usbSelectItem(node.indexInParent, node.title)) {
                logEv("scan: nepodarilo sa otvoriť ${node.pathKey} '${node.title}'")
                recvPath = null
                continue
            }
            usbSnapshot()
            logEv("scan: po otvorení ${node.pathKey} obrazovka=$nwScreenType názov='$nwScreenName'")

            if (usbOnList() && !nwScreenName.trim().equals(node.title.trim(), ignoreCase = true)) {
                logEv("scan: POZOR! Chceli sme '${node.title}', ale receiver otvoril '$nwScreenName'! Preskakujem.")
                recvPath = null
                continue
            }

            if (!usbOnList()) {
                usbStopBoth()
                items[i] = node.copy(isFolder = false)
                recvPath = null
                continue
            }
            val childPath = path + node.indexInParent
            recvPath = childPath
            counter[1]++
            scanUsbFolderRecursive(cache, childPath, node.pathKey, node.title, depth + 1, counter)
        }
    }

    private fun scanEntireUsbTree() {
        if (usbScanning) return
        logEv("SCAN štart")
        usbScanning = true
        usbState = UsbState.STOPPED
        updateScanButtonUi()
        usbStatusText.text = "⏳ Zastavujem prehrávanie a načítavam celý USB kľúč…"

        launchUsb {
            try {
                val atRoot = exitUsbPlayScreenToRootList()
                if (!atRoot) {
                    ui { usbStatusText.text = "⚠️ Nepodarilo sa dostať do koreňa USB (pozri log). Skús znova." }
                    return@launchUsb
                }
                val newCache = linkedMapOf<String, MutableList<UsbNode>>()
                val counter = IntArray(2)
                scanUsbFolderRecursive(newCache, emptyList(), "/", "USB", 0, counter)
                ui {
                    usbTreeCache.clear()
                    usbTreeCache.putAll(newCache)
                    usbCurrentPath.clear()
                    usbSelectedPathKey = null
                    saveCachedUsbTree()
                    renderUsbCachedFolder()
                    usbStatusText.text = "✓ USB načítaný: ${counter[1] + 1} adresárov, ${counter[0]} položiek."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui { usbStatusText.text = "Chyba pri načítaní USB: ${e.message ?: "neznáma"}" }
            } finally {
                usbScanning = false
                ui { updateScanButtonUi() }
            }
        }
    }

    private fun usbNodeLabel(node: UsbNode): String {
        val icon = if (node.isFolder) "📁" else "🎵"
        var cnt = ""
        if (node.isFolder) {
            val n = usbTreeCache[node.pathKey]?.size
            if (n != null) cnt = "  ($n)"
        }
        return "${node.indexInParent}.  $icon  ${node.title}$cnt"
    }

    private fun updateUsbSelectionStyles() {
        val sel = findUsbNodeByKey(usbSelectedPathKey)
        if (::usbSelectedText.isInitialized) {
            usbSelectedText.text = if (sel != null) {
                val lbl = if (sel.isFolder) "celý adresár" else "súbor"
                "✓ Označené ($lbl): ${sel.title} — stlač ▶ PLAY"
            } else {
                "✓ Označené: (nič) — ☐ označí adresár/súbor, klik na adresár ho otvorí"
            }
        }
        usbTreeCache[currentUsbPathKey()].orEmpty().forEach { node ->
            val isSel = node.pathKey == usbSelectedPathKey
            usbCheckButtons[node.pathKey]?.let { c ->
                c.text = if (isSel) "☑" else "☐"
                highlightBtn(c, isSel, C_ORANGE)
            }
            usbNodeButtons[node.pathKey]?.let { b ->
                b.text = usbNodeLabel(node)
                if (isSel) {
                    highlightBtn(b, true, C_ORANGE)
                } else {
                    b.background = roundedBg(
                        if (node.isFolder) C_BTN else C_CARD2, 12,
                        if (node.isFolder) C_CYAN_D else C_STROKE
                    )
                    b.setTextColor(C_WHITE)
                    b.typeface = Typeface.DEFAULT
                }
            }
        }
    }

    private fun renderUsbCachedFolder() {
        if (!::usbListLayout.isInitialized) return
        usbListLayout.removeAllViews()
        usbNodeButtons.clear()
        usbCheckButtons.clear()

        usbPathText.text = "📂 Adresár: ${currentUsbPathDisplay()}"

        if (usbCurrentPath.isNotEmpty()) {
            val upBtn = flatBtn("📁  ..  (o úroveň vyššie)") { navigateUpOneUsbFolder() }
            upBtn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            upBtn.textSize = 13f
            upBtn.setPadding(dp(14), dp(10), dp(14), dp(10))
            upBtn.background = roundedBg(C_CARD, 12, C_ORANGE)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            usbListLayout.addView(upBtn, lp)
        }

        val items = usbTreeCache[currentUsbPathKey()]
        if (items.isNullOrEmpty()) {
            val emptyTv = TextView(this)
            emptyTv.text = "(Adresár nie je v pamäti alebo je prázdny — stlač 🔄 Načítať USB)"
            emptyTv.textSize = 11f
            emptyTv.setTextColor(C_DIM)
            emptyTv.setPadding(dp(4), dp(8), dp(4), dp(8))
            usbListLayout.addView(emptyTv)
            updateUsbSelectionStyles()
            return
        }

        items.forEach { node ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val chk = flatBtn("☐") { toggleUsbSelection(node) }
            chk.textSize = 18f
            val b = flatBtn(usbNodeLabel(node)) { onUsbNodeClicked(node) }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 13f
            b.setPadding(dp(14), dp(10), dp(14), dp(10))

            usbCheckButtons[node.pathKey] = chk
            usbNodeButtons[node.pathKey] = b

            row.addView(chk, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
            val lpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lpB.leftMargin = dp(4)
            row.addView(b, lpB)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            usbListLayout.addView(row, lp)
        }
        updateUsbSelectionStyles()
    }

    private fun navigateUpOneUsbFolder() {
        if (usbCurrentPath.isNotEmpty()) {
            usbCurrentPath.removeAt(usbCurrentPath.size - 1)
            renderUsbCachedFolder()
        }
    }

    private fun enterUsbFolderNode(node: UsbNode) {
        usbCurrentPath.add(node.indexInParent to node.title)
        renderUsbCachedFolder()
        val n = usbTreeCache[node.pathKey]?.size
        usbStatusText.text = if (n == null)
            "⚠️ Obsah '${node.title}' nie je v pamäti — stlač 🔄 Načítať USB."
        else
            "📂 '${node.title}' — $n položiek."
    }

    private fun toggleUsbSelection(node: UsbNode) {
        usbSelectedPathKey = if (usbSelectedPathKey == node.pathKey) null else node.pathKey
        updateUsbSelectionStyles()
    }

    private fun onUsbNodeClicked(node: UsbNode) {
        if (node.isFolder) {
            enterUsbFolderNode(node)
        } else {
            usbSelectedPathKey = node.pathKey
            updateUsbSelectionStyles()
            usbStatusText.text = "Označený súbor '${node.title}' — stlač ▶ PLAY."
        }
    }

    private fun findUsbNodeByKey(pathKey: String?): UsbNode? {
        if (pathKey.isNullOrBlank()) return null
        usbTreeCache.values.forEach { list ->
            list.firstOrNull { it.pathKey == pathKey }?.let { return it }
        }
        return null
    }

    private fun isPlayableTitle(t: String): Boolean {
        val l = t.lowercase()
        return listOf(".m3u", ".pls", ".cue", ".jpg", ".jpeg", ".png", ".txt", ".nfo", ".log").none { l.endsWith(it) }
    }

    private fun firstPlayableFile(node: UsbNode, depth: Int = 0): UsbNode? {
        if (!node.isFolder) return node
        if (depth > 8) return null
        val children = usbTreeCache[node.pathKey].orEmpty()
        children.firstOrNull { !it.isFolder && isPlayableTitle(it.title) }?.let { return it }
        for (c in children) {
            firstPlayableFile(c, depth + 1)?.let { return it }
        }
        return null
    }

    // ▶ PLAY: okamžite na prvé kliknutie zastaví predchádzajúce prehrávanie a spustí označenú položku
    private fun onUsbPlayPressed() {
        val sel = findUsbNodeByKey(usbSelectedPathKey)
        if (sel == null) {
            usbStatusText.text = "⚠️ Najprv označ adresár alebo súbor (☐)."
            return
        }
        startUsbSelection(sel)
    }

    private fun startUsbSelection(sel: UsbNode) {
        val target = firstPlayableFile(sel)
        if (target == null) {
            usbStatusText.text = "⚠️ V '${sel.title}' som nenašiel žiadny súbor. Skús 🔄 Načítať USB."
            return
        }
        val steps = target.pathKey.trim('/').split('/').mapNotNull { it.toIntOrNull() }
        if (steps.isEmpty()) return

        logEv("PLAY: výber=${sel.pathKey} '${sel.title}' cieľový súbor=${target.pathKey} '${target.title}' kroky=$steps")
        usbStartedKey = sel.pathKey
        usbState = UsbState.PLAYING
        activeStationName = sel.title
        playMetaLines.clear()
        playTimeText = ""
        updateMainDisplayHeader()
        usbStatusText.text = "⏳ Spúšťam '${sel.title}'…"

        launchUsb {
            isPlayingFavorite = false
            val atRoot = exitUsbPlayScreenToRootList()
            if (!atRoot) {
                ui {
                    usbState = UsbState.STOPPED
                    usbStatusText.text = "⚠️ Nepodarilo sa dostať do koreňa USB (pozri log). Skús znova."
                }
                return@launchUsb
            }
            if (!usbEnsureAt(steps.dropLast(1))) {
                ui {
                    usbState = UsbState.STOPPED
                    usbStatusText.text = "⚠️ Nepodarilo sa otvoriť adresár na receiveri (pozri log)."
                }
                return@launchUsb
            }
            usbSelectItem(steps.last(), target.title)
            usbSnapshot()
            logEv("PLAY: po výbere obrazovka=$nwScreenType gbp=$rxGbp názov='$nwScreenName'")
            if (usbOnList()) {
                logEv("PLAY: stále zoznam, posielam Enter (17IP)")
                sendSilent("17IP")
                delay(800)
                usbSnapshot()
                logEv("PLAY: po Enter obrazovka=$nwScreenType gbp=$rxGbp")
            }
            recvPath = null
            val what = if (sel.isFolder) "adresár ${sel.title} (od: ${target.title})" else sel.title
            ui { usbStatusText.text = "▶ Hrá: $what" }
            delay(1200)
            sendSilent("?GAP")
            sendSilent("?GAH")
            sendSilent("?FL")
        }
    }

    // ⏹ STOP: Opravené! Keďže VSX-S510 ignoruje 02IP/20NW počas prehrávania USB súboru,
    // vykonáme garantovaný návrat do koreňa (06FN -> 17FN), ktorý prehrávanie okamžite zastaví.
    private fun stopUsbPlayback() {
        logEv("STOP stlačené (stav bol $usbState)")
        usbScanning = false
        updateScanButtonUi()
        recvPath = null
        isUsbPlayingNow = false
        usbState = UsbState.STOPPED
        usbStartedKey = null
        playMetaLines.clear()
        playTimeText = ""
        activeStationName = ""
        updateMainDisplayHeader()
        usbStatusText.text = "⏳ Zastavujem prehrávanie…"

        launchUsb {
            usbStopBoth()
            delay(200)
            // Garantované zastavenie prehrávania rovnakým spôsobom, akým ho prerušuje PLAY:
            sendSilent("06FN")
            delay(900)
            val tSwitch = System.currentTimeMillis()
            sendSilent("17FN")
            var w = 0L
            while (w < 2000 && rxScreenAt < tSwitch) {
                delay(60)
                w += 60
            }
            delay(300)
            sendSilent("?FL")
            usbSnapshot()
            recvPath = if (usbAtRoot()) emptyList() else null
            ui {
                playMetaLines.clear()
                playTimeText = ""
                activeStationName = ""
                updateMainDisplayHeader()
                usbStatusText.text = "⏹ Prehrávanie zastavené."
            }
        }
    }

    // =====================================================================
    // AKTUALIZÁCIE HLAVNÉHO DISPLEJA
    // =====================================================================

    private fun volShortText(v: Int): String {
        val dB = v - 80
        return "🔊 $v (" + (if (dB >= 0) "+" else "") + dB + " dB)"
    }

    private fun updateVolumeCard() {
        if (!::volumeLabel.isInitialized || !::volBar.isInitialized) return
        volBar.progress = currentVol.coerceIn(0, volMax)
        if (isMuted) {
            volumeLabel.text = "🔇 MUTE"
            volumeLabel.setTextColor(C_RED)
            volBar.progressTintList = ColorStateList.valueOf(C_RED)
        } else {
            volumeLabel.text = volShortText(currentVol)
            volumeLabel.setTextColor(C_GREEN)
            volBar.progressTintList = ColorStateList.valueOf(C_GREEN)
        }
    }

    private fun updateInfoLine() {
        val i = activeInput ?: "—"
        val m = activeMode ?: "—"
        infoLine.text = "Vstup: $i   ·   Režim: $m"
    }

    private fun updateMainDisplayHeader() {
        if (!::mainStationBig.isInitialized) return
        val presetName = if (activeFavIndex > 0 && activeInput == "FAVORITES") favStations[activeFavIndex] else null
        val l1 = playMetaLines[1]?.second.orEmpty()
        val l2 = playMetaLines[2]?.second.orEmpty()
        val l3 = playMetaLines[3]?.second.orEmpty()
        val l4 = playMetaLines[4]?.second.orEmpty()
        val l5 = playMetaLines[5]?.second.orEmpty()
        val l6 = playMetaLines[6]?.second.orEmpty()

        val icon = if (activeInput == "IPOD/USB") "🔌" else "📻"

        val topTitle = when {
            !presetName.isNullOrBlank() -> "$icon  $presetName"
            activeStationName.isNotBlank() -> "$icon  $activeStationName"
            l1.isNotBlank() -> "$icon  $l1"
            lastFlText.isNotBlank() -> "📟  $lastFlText"
            !activeInput.isNullOrBlank() -> "🎚  ${activeInput!!}"
            else -> "📻  —"
        }
        mainStationBig.text = topTitle

        val showParts = listOf(l1, l2, l3).filter {
            it.isNotBlank() &&
                !it.equals(presetName, ignoreCase = true) &&
                !it.equals(activeStationName, ignoreCase = true)
        }.distinct()
        val bitrateParts = listOf(l4, l5, l6).filter { it.isNotBlank() }.distinct()

        val combinedSub = mutableListOf<String>()
        if (showParts.isNotEmpty()) combinedSub.add(showParts.joinToString(" · "))
        if (bitrateParts.isNotEmpty()) combinedSub.add(bitrateParts.joinToString(" "))
        if (playTimeText.isNotBlank()) combinedSub.add(playTimeText)

        mainShowBitrate.text = if (combinedSub.isNotEmpty()) {
            combinedSub.joinToString("   |   ")
        } else if (lastFlText.isNotBlank()) {
            "Displej: $lastFlText"
        } else {
            "Relácia / Bitrate: —"
        }
    }

    private fun updateMuteButton() {
        muteBtn.text = if (isMuted) "🔊 ODMUTOVAŤ" else "🔇 MUTE"
        highlightBtn(muteBtn, isMuted, C_RED)
    }

    private fun updatePowerButton() {
        val bg = if (isOn) C_GREEN else C_RED
        val fg = if (isOn) C_ON_ACCENT else C_WHITE
        powerBtn.text = if (isOn) "ON" else "OFF"
        powerBtn.setTextColor(fg)
        powerBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
            setStroke(dp(1), C_WHITE)
        }
    }

    private fun togglePower() {
        send(if (isOn) "PF" else "PO")
        scope.launch(Dispatchers.IO) {
            delay(800)
            sendSilent("?P")
        }
    }

    private fun styleInputButtons() {
        inputButtons.forEach { (name, b) -> highlightBtn(b, name == activeInput) }
    }

    private fun styleModeButtons() {
        modeButtons.forEach { (name, b) -> highlightBtn(b, name == activeMode) }
    }

    private fun decodeFlHex(hexStr: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hexStr.length) {
            val code = hexStr.substring(i, i + 2).toIntOrNull(16) ?: 32
            if (code in 32..126) sb.append(code.toChar())
            else if (code > 127) sb.append('·')
            i += 2
        }
        return sb.toString().trim()
    }

    private fun toggleMute() {
        send(if (isMuted) "MF" else "MO")
        sendSilent("?M")
    }

    // =====================================================================
    // AUTOMATICKÉ VYHĽADANIE A PRIPOJENIE RECEIVERA PRI ŠTARTE
    // =====================================================================

    private fun autoConnectOrDiscoverOnStartup() {
        ui {
            connLine.text = "⏳ Pripájam na uloženú IP $host…"
            connLine.setTextColor(C_ORANGE)
            statusText.text = ""
        }
        scope.launch(Dispatchers.IO) {
            if (probe(host)) {
                saveHostIp(host)
                connect(silentFail = false)
                return@launch
            }
            ui {
                connLine.text = "○ Uložená IP $host neodpovedá"
                connLine.setTextColor(C_RED)
                statusText.text = "🔍 Automaticky hľadám receiver v sieti…"
            }
            discoverReceivers(autoConnectFirst = true)
        }
    }

    private fun phoneIp(): String? {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces() ?: return null
            for (n in nets) {
                if (n.isLoopback || !n.isUp) continue
                for (a in n.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun probe(ip: String): Boolean {
        return try {
            Socket().use { s ->
                s.soTimeout = 1000
                s.connect(InetSocketAddress(ip, port), 450)
                val w = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                w.write("?P\r\n?V\r\n")
                w.flush()
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val line = r.readLine() ?: return false
                line.startsWith("VOL") || line.startsWith("PWR") || line.startsWith("FN") ||
                    line.startsWith("MUT") || line.startsWith("LM") || line.startsWith("SR") ||
                    line.startsWith("FL")
            }
        } catch (_: Exception) { false }
    }

    private fun discoverReceivers(autoConnectFirst: Boolean) {
        ui { statusText.text = "🔍 Hľadám Pioneer receiver v Wi-Fi sieti (port $port)…" }
        scope.launch(Dispatchers.IO) {
            val myIp = phoneIp()
            if (myIp == null) {
                ui {
                    connLine.text = "○ Odpojené (Wi-Fi nedostupná)"
                    connLine.setTextColor(C_RED)
                    statusText.text = "Nepodarilo sa zistiť IP telefónu — skontroluj pripojenie na Wi-Fi."
                }
                return@launch
            }
            val prefix = myIp.substringBeforeLast(".")
            val found = Collections.synchronizedList(mutableListOf<String>())
            withTimeoutOrNull(18000) {
                coroutineScope {
                    (1..254).forEach { i ->
                        launch {
                            val ip = "$prefix.$i"
                            if (probe(ip)) found.add(ip)
                        }
                    }
                }
            }
            val sorted = found.toList().sorted()
            ui {
                if (sorted.isEmpty()) {
                    connLine.text = "○ Odpojené ($host)"
                    connLine.setTextColor(C_RED)
                    statusText.text = "V sieti $prefix.0/24 sa nenašiel receiver. Skontroluj napájanie / Network Standby."
                } else if (autoConnectFirst || sorted.size == 1) {
                    val chosenIp = sorted.first()
                    saveHostIp(chosenIp)
                    statusText.text = "✓ Nájdený receiver na $chosenIp — pripájam…"
                    connect(silentFail = false)
                } else {
                    statusText.text = ""
                    pickReceiver(sorted)
                }
            }
        }
    }

    private fun pickReceiver(found: List<String>) {
        if (found.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Nájdené prijímače")
            .setItems(found.toTypedArray()) { _, i ->
                saveHostIp(found[i])
                avtControlUrl = null
                connect(silentFail = false)
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    // =====================================================================
    // DLNA SERVERY
    // =====================================================================

    private fun scanMediaServers() {
        ui { mediaStatusText.text = "Hľadám DLNA servery…" }
        scope.launch(Dispatchers.IO) {
            val found = ssdpFindAll("urn:schemas-upnp-org:device:MediaServer:1")
            if (found.isEmpty()) {
                ui { mediaStatusText.text = "Nenašiel som žiadny DLNA server v sieti." }
                return@launch
            }
            val names = found.map { loc ->
                val xml = httpGet(loc)
                Triple(
                    loc,
                    Regex("(?is)<friendlyName>(.*?)</friendlyName>")
                        .find(xml ?: "")?.groupValues?.get(1) ?: loc,
                    xml)
            }
            ui {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Vyber DLNA server")
                    .setItems(names.map { it.second }.toTypedArray()) { _, i ->
                        mediaServerDesc = names[i].first
                        mediaServerName = names[i].second
                        prefs().edit()
                            .putString("dlnaServer", mediaServerDesc)
                            .putString("dlnaName", mediaServerName)
                            .apply()
                        initMediaServer(mediaServerDesc!!)
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            }
        }
    }

    private fun initMediaServer(descUrl: String) {
        scope.launch(Dispatchers.IO) {
            val control = readServiceControlUrl(descUrl,
                "urn:schemas-upnp-org:service:ContentDirectory")
            if (control == null) {
                ui { mediaStatusText.text = "Server neponúka ContentDirectory." }
                return@launch
            }
            cdsControlUrl = control
            ui { mediaServerText.text = "Server: $mediaServerName" }
            browseStack.clear()
            browseFolder("0", "Knižnica")
        }
    }

    private fun browseBack() {
        if (browseStack.size > 1) {
            browseStack.removeAt(browseStack.size - 1)
            val parent = browseStack[browseStack.size - 1]
            browseFolder(parent.first, parent.second, pushStack = false)
        }
    }

    private fun browseFolder(objectId: String, title: String, pushStack: Boolean = true) {
        val control = cdsControlUrl ?: return
        if (pushStack) browseStack.add(objectId to title)
        scope.launch(Dispatchers.IO) {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                "<ObjectID>" + objectId + "</ObjectID>" +
                "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                "<Filter>*</Filter><StartingIndex>0</StartingIndex>" +
                "<RequestedCount>0</RequestedCount><SortCriteria></SortCriteria>" +
                "</u:Browse></s:Body></s:Envelope>"
            val response = soapRequest(control, "Browse",
                "urn:schemas-upnp-org:service:ContentDirectory:1#Browse", body) ?: ""
            val result = Regex("(?is)<Result>(.*?)</Result>")
                .find(response)?.groupValues?.get(1) ?: ""
            val entries = parseDidl(result)
            currentListing = entries
            ui {
                mediaPathText.text = "📚 " + browseStack.joinToString(" / ") { it.second }
                renderMediaList(entries)
            }
        }
    }

    private fun parseDidl(xml: String): List<MediaEntry> {
        val decoded = xml
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
        val containers = Regex("(?is)<container[^>]*?id=\"([^\"]+)\"[^>]*>(.*?)</container>")
            .findAll(decoded).map { c ->
                val title = Regex("(?i)<dc:title>(.*?)</dc:title>")
                    .find(c.groupValues[2])?.groupValues?.get(1) ?: "(priečinok)"
                MediaEntry(c.groupValues[1], title.trim(), true, null)
            }.toList()
        val items = Regex("(?is)<item[^>]*?id=\"([^\"]+)\"[^>]*>(.*?)</item>")
            .findAll(decoded).map { i ->
                val title = Regex("(?i)<dc:title>(.*?)</dc:title>")
                    .find(i.groupValues[2])?.groupValues?.get(1) ?: "(skladba)"
                val res = Regex("(?is)<res[^>]*>(.*?)</res>")
                    .find(i.groupValues[2])?.groupValues?.get(1)?.trim()
                MediaEntry(i.groupValues[1], title.trim(), false, res)
            }.toList()
        return containers + items
    }

    private fun renderMediaList(entries: List<MediaEntry>) {
        mediaList.removeAllViews()
        entries.forEach { e ->
            val btn = flatBtn((if (e.isContainer) "📁 " else "🎵 ") + e.title) {
                if (e.isContainer) {
                    browseFolder(e.id, e.title)
                } else {
                    playQueue(listOf(e), 0)
                }
            }
            btn.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            if (!e.isContainer) btn.background = roundedBg(C_CARD2, 12, C_STROKE)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(3)
            mediaList.addView(btn, lp)
        }
        if (entries.isEmpty()) {
            val t = TextView(this)
            t.text = "(prázdne)"
            t.setTextColor(C_DIM)
            mediaList.addView(t)
        }
    }

    private fun playCurrentFolder() {
        val tracks = currentListing.filter { !it.isContainer && it.url != null }
        if (tracks.isEmpty()) {
            ui { mediaStatusText.text = "V tomto priečinku nie sú skladby." }
            return
        }
        playQueue(tracks, 0)
    }

    private fun playQueue(tracks: List<MediaEntry>, startIndex: Int) {
        queueJob?.cancel()
        playQueue = tracks
        queueIdx = startIndex
        queueJob = scope.launch(Dispatchers.IO) {
            try {
                if (activeInput != "MEDIA SRV" && activeInput != "44FN" && activeInput != "47FN") {
                    sendSilent("44FN")
                    delay(1000)
                }
                while (isActive && queueIdx < playQueue.size) {
                    val track = playQueue[queueIdx]
                    val control = ensureAvtControl() ?: run {
                        ui { mediaStatusText.text = "UPnP: receiver nedostupný." }
                        return@launch
                    }
                    ui {
                        mediaStatusText.text = "▶ " + track.title +
                            "  (" + (queueIdx + 1) + "/" + playQueue.size + ")"
                    }
                    runCatching { soapStop(control) }
                    delay(250)
                    soapSetUri(control, track.url!!, track.title, isBroadcast = false)
                    delay(250)
                    soapPlay(control)

                    var started = false
                    var stoppedCount = 0
                    var noStart = 0
                    var trackFinished = false
                    while (isActive && !trackFinished) {
                        delay(2500)
                        val st = transportState(control)
                        when {
                            st == "PLAYING" -> { started = true; stoppedCount = 0 }
                            st == "TRANSITIONING" -> { started = true }
                            started && (st == "STOPPED" || st == "NO_MEDIA_PRESENT") -> {
                                stoppedCount++
                                if (stoppedCount >= 2) trackFinished = true
                            }
                            !started -> {
                                noStart++
                                if (noStart > 8) trackFinished = true
                            }
                        }
                    }
                    queueIdx++
                }
                ui { mediaStatusText.text = "✓ Prehrávanie dokončené" }
            } catch (_: Exception) { }
        }
    }

    // =====================================================================
    // UPnP NÍZKOÚROVŇOVÉ VOLANIA
    // =====================================================================

    private fun ssdpFindAll(st: String, timeoutMs: Int = 3500): List<String> {
        val result = linkedSetOf<String>()
        val msearch = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: " + st + "\r\n\r\n"
        runCatching {
            MulticastSocket().use { sock ->
                sock.soTimeout = timeoutMs
                val addr = InetAddress.getByName("239.255.255.250")
                val pktOut = DatagramPacket(msearch.toByteArray(), msearch.length, addr, 1900)
                sock.send(pktOut)
                sock.send(pktOut)
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: Exception) { break }
                    val resp = String(pkt.data, 0, pkt.length, Charsets.US_ASCII)
                    Regex("(?i)LOCATION:\\s*(\\S+)").find(resp)?.groupValues?.get(1)
                        ?.let { result.add(it) }
                }
            }
        }
        return result.toList()
    }

    private fun ensureAvtControl(): String? {
        avtControlUrl?.let { return it }

        val allRenderers = ssdpFindAll("urn:schemas-upnp-org:device:MediaRenderer:1", 2500)
        val matched = allRenderers.firstOrNull { it.contains(host) }
        if (matched != null) {
            readServiceControlUrl(matched, "urn:schemas-upnp-org:service:AVTransport")
                ?.let { avtControlUrl = it; return it }
        }

        val fallbackLocations = listOf(
            "http://$host:49152/description.xml",
            "http://$host:49153/description.xml",
            "http://$host:49154/description.xml",
            "http://$host:8080/description.xml",
            "http://$host:60006/upnp/desc/aios_device/aios_device.xml"
        )
        for (loc in fallbackLocations) {
            val ctrl = readServiceControlUrl(loc, "urn:schemas-upnp-org:service:AVTransport")
            if (ctrl != null) {
                avtControlUrl = ctrl
                return ctrl
            }
        }
        return null
    }

    private fun readServiceControlUrl(descUrl: String, serviceUrn: String): String? {
        val xml = httpGet(descUrl) ?: return null
        val shortName = serviceUrn.substringAfterLast(':')
        val serviceBlock = Regex("(?is)<service>.*?" + shortName + ".*?</service>")
            .find(xml)?.value ?: return null
        val controlPath = Regex("(?is)<controlURL>(.*?)</controlURL>")
            .find(serviceBlock)?.groupValues?.get(1)?.trim() ?: return null
        if (controlPath.startsWith("http://", ignoreCase = true) ||
            controlPath.startsWith("https://", ignoreCase = true)) {
            return controlPath
        }
        val base = Regex("(?is)<URLBase>(.*?)</URLBase>")
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: URL(descUrl).let {
                val p = if (it.port != -1) ":${it.port}" else ""
                "${it.protocol}://${it.host}$p"
            }
        return base.trimEnd('/') + "/" + controlPath.trimStart('/')
    }

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 3500; conn.readTimeout = 3500
        BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    }.getOrNull()

    private fun soapRequest(controlUrl: String, action: String,
                            soapAction: String, body: String): String? {
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$soapAction\"")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
            it.write(body); it.flush()
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream ?: return null)).use { it.readText() }
    }

    private fun soap(controlUrl: String, action: String, body: String): String? {
        return soapRequest(controlUrl, action,
            "urn:schemas-upnp-org:service:AVTransport:1#" + action, body)
    }

    private fun transportState(controlUrl: String): String {
        val resp = soapRequest(controlUrl, "GetTransportInfo",
            "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:GetTransportInfo></s:Body></s:Envelope>") ?: return ""
        return Regex("(?i)<CurrentTransportState>(.*?)</CurrentTransportState>")
            .find(resp)?.groupValues?.get(1) ?: ""
    }

    private fun soapSetUri(controlUrl: String, streamUrl: String,
                           title: String = "Media",
                           isBroadcast: Boolean = false): String? {
        val escUrl = streamUrl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val escTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val lower = streamUrl.lowercase()
        val mime = when {
            lower.endsWith(".aac") || lower.contains("aac") -> "audio/aac"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".wav") -> "audio/wav"
            else -> "audio/mpeg"
        }
        val dlnaPn = if (mime == "audio/aac") "AAC_ISO" else "MP3"
        val upnpClass = if (isBroadcast)
            "object.item.audioItem.audioBroadcast"
        else
            "object.item.audioItem.musicTrack"
        val dlnaOp = if (isBroadcast) "00" else "01"
        val dlnaFlags = if (isBroadcast)
            "01700000000000000000000000000000"
        else
            "01500000000000000000000000000000"

        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>$escTitle</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:DLNA.ORG_PN=$dlnaPn;DLNA.ORG_OP=$dlnaOp;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$dlnaFlags\">$escUrl</res>" +
            "</item></DIDL-Lite>"

        val escDidl = didl.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        return soap(controlUrl, "SetAVTransportURI",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID><CurrentURI>" + escUrl + "</CurrentURI>" +
            "<CurrentURIMetaData>" + escDidl + "</CurrentURIMetaData>" +
            "</u:SetAVTransportURI></s:Body></s:Envelope>")
    }

    private fun soapPlay(controlUrl: String): String? {
        return soap(controlUrl, "Play",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID><Speed>1</Speed>" +
            "</u:Play></s:Body></s:Envelope>")
    }

    private fun soapStop(controlUrl: String): String? {
        return soap(controlUrl, "Stop",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:Stop></s:Body></s:Envelope>")
    }

    // =====================================================================
    // PRIPOJENIE A SPRÁVA PRÍKAZOV (SYNCHRÓNNE PARSOVANIE DÁT NA IO VLÁKNE)
    // =====================================================================

    private fun connect(silentFail: Boolean = false) {
        runCatching { socketRef.get()?.close() }
        queueJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 3000)
                sock.keepAlive = true
                outWriter = OutputStreamWriter(sock.getOutputStream(), Charsets.US_ASCII)
                socketRef.set(sock)
                logEv("pripojené $host:$port")
                saveHostIp(host)
                ui {
                    connLine.text = "●  Pripojené: $host:$port"
                    connLine.setTextColor(C_GREEN)
                    statusText.text = ""
                }
                sendSilent("?P")
                sendSilent("?V")
                sendSilent("?F")
                sendSilent("?M")
                sendSilent("?S")
                sendSilent("?L")
                sendSilent("?FL")
                sendSilent("?GAP")
                listen(sock)
            } catch (e: Exception) {
                if (!silentFail) {
                    ui {
                        connLine.text = "○  Odpojené ($host)"
                        connLine.setTextColor(C_RED)
                        statusText.text = "Chyba pripojenia: ${e.message ?: "neznáma"} — klikni 🔍 pre vyhľadanie."
                    }
                }
            }
        }
    }

    private fun listen(sock: Socket) {
        scope.launch(Dispatchers.IO) {
            try {
                val reader =
                    BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                while (isActive && sock.isConnected) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    logRaw("RX", trimmed)
                    handleResponse(trimmed)
                }
            } catch (e: Exception) {
                ui {
                    connLine.text = "○  Odpojené ($host)"
                    connLine.setTextColor(C_RED)
                    statusText.text = "Spojenie prerušené (${e.message ?: "odpojené"})"
                }
            }
        }
    }

    private fun handleResponse(line: String) {
        when {
            line.startsWith("E0") -> {
                lastErrAt = System.currentTimeMillis()
                val errDesc = when (line) {
                    "E02" -> "Príkaz teraz nie je dostupný (E02)"
                    "E03" -> "Neplatný príkaz (E03)"
                    "E04" -> "Chyba príkazu (E04)"
                    "E06" -> "Neplatný parameter (E06)"
                    else -> "Odpoveď receivera: $line"
                }
                ui { statusText.text = errDesc }
            }
            line.startsWith("GBP") -> {
                rxGbp = line.substring(3).trim().toIntOrNull() ?: 0
            }
            line.startsWith("GCH") || line.startsWith("GCP") -> {
                if (line.length >= 5) {
                    nwScreenType = line.substring(3, 5)
                }
                val newName = line.substringAfter("\"", "").substringBeforeLast("\"", "")
                if (newName != nwScreenName) {
                    synchronized(pageLock) {
                        rawPageLines.clear()
                    }
                }
                nwScreenName = newName
                rxScreenAt = System.currentTimeMillis()
                val usbCtx = activeInput == "IPOD/USB" || usbBusy || usbState != UsbState.STOPPED
                if (usbCtx) {
                    if (nwScreenType == "01" || nwScreenType == "00") {
                        listScreenReady = true
                        isPlayingFavorite = false
                        isUsbPlayingNow = false
                    } else {
                        listScreenReady = false
                        isUsbPlayingNow = true
                    }
                } else if (nwScreenType == "01" || nwScreenType == "02" || nwScreenType == "00") {
                    listScreenReady = true
                    isPlayingFavorite = false
                    isUsbPlayingNow = false
                } else if (nwScreenType == "04" || nwScreenType == "05") {
                    listScreenReady = false
                    if (activeInput == "FAVORITES") isPlayingFavorite = true
                }
            }
            line.startsWith("GDH") || line.startsWith("GDP") -> {
                listScreenReady = true
                if (line.length >= 18) {
                    val newStart = line.substring(3, 8).toIntOrNull() ?: 1
                    val newEnd = line.substring(8, 13).toIntOrNull() ?: 0
                    val newTotal = line.substring(13, 18).toIntOrNull() ?: 0
                    if (newStart != rawRangeStart) {
                        synchronized(pageLock) {
                            rawPageLines.clear()
                        }
                    }
                    rawRangeStart = newStart
                    rawRangeEnd = newEnd
                    rawTotalItems = newTotal
                    rawGdhUpdatedAt = System.currentTimeMillis()
                    rawPageUpdatedAt = rawGdhUpdatedAt
                }
            }
            line.startsWith("GEH") || line.startsWith("GEP") -> {
                val idx = line.substring(3, 5).toIntOrNull()
                val title = line.substringAfter("\"", "").substringBeforeLast("\"", "")
                val typeCode = if (line.length >= 8) line.substring(6, 8) else "01"
                val typeNum = typeCode.toIntOrNull() ?: 1

                if (idx != null) {
                    val isPlayScreen = nwScreenType == "04" || nwScreenType == "05" || isUsbPlayingNow || isPlayingFavorite
                    val isMetadataLine = typeNum >= 10 || isPlayScreen

                    if (isMetadataLine) {
                        if (title.isNotEmpty()) {
                            playMetaLines[idx] = typeCode to title
                        } else {
                            playMetaLines.remove(idx)
                        }
                        ui { updateMainDisplayHeader() }
                    } else {
                        listScreenReady = true
                        synchronized(pageLock) {
                            if (title.isNotEmpty()) {
                                rawPageLines[idx] = typeCode to title
                            } else {
                                rawPageLines.remove(idx)
                            }
                        }
                        rawPageUpdatedAt = System.currentTimeMillis()

                        if (isSyncingFavorites || (activeInput == "FAVORITES" && favStations.size < 8)) {
                            if (title.isNotEmpty() && !title.startsWith("[") && !looksLikeMetadata(title)) {
                                ui {
                                    favStations[idx] = title
                                    saveCachedFavorites()
                                    renderFavoriteTable()
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                ui {
                    when {
                        line.startsWith("VOL") -> {
                            val v = line.substring(3).trim().toIntOrNull() ?: return@ui
                            currentVol = v
                            updateVolumeCard()
                        }
                        line.startsWith("MUT") -> {
                            isMuted = line.startsWith("MUT0")
                            updateVolumeCard()
                            updateMuteButton()
                        }
                        line.startsWith("FN") && line.length == 4 -> {
                            val code = line.substring(2) + "FN"
                            activeInput = inputs.entries.firstOrNull { it.value == code }?.key
                                ?: when (code) {
                                    "19FN" -> "HDMI 1"
                                    "20FN" -> "HDMI 2"
                                    "21FN" -> "HDMI 3"
                                    "22FN" -> "HDMI 4"
                                    "23FN" -> "HDMI 5"
                                    "24FN" -> "HDMI 6"
                                    "47FN" -> "DMR"
                                    "26FN" -> "NETWORK"
                                    else -> code
                                }
                            updateInfoLine()
                            styleInputButtons()
                            updateMainDisplayHeader()
                        }
                        line.startsWith("SR") && line.length >= 6 -> {
                            val code = line.substring(2, 6) + "SR"
                            val m = modes.entries.firstOrNull { it.value == code }
                            if (m != null) {
                                activeMode = m.key
                                updateInfoLine()
                                styleModeButtons()
                            }
                        }
                        line.startsWith("LM") && line.length >= 6 -> {
                            val lmHex = line.substring(2).trim()
                            val matched = when {
                                lmHex.endsWith("01") -> "STEREO"
                                lmHex.endsWith("05") -> "AUTO SURR."
                                lmHex.endsWith("06") || lmHex.endsWith("07") -> "DIRECT"
                                lmHex.endsWith("51") -> "ALC / PURE"
                                lmHex.contains("13") || lmHex.contains("02") -> "PLII MOVIE"
                                lmHex.contains("14") || lmHex.contains("03") -> "PLII MUSIC"
                                else -> activeMode
                            }
                            if (matched != null) {
                                activeMode = matched
                                updateInfoLine()
                                styleModeButtons()
                            }
                        }
                        line == "PWR0" -> { isOn = true; updatePowerButton() }
                        line == "PWR2" || line == "PWR1" -> { isOn = false; updatePowerButton() }
                        line.startsWith("FL") && line.length >= 6 -> {
                            val txt = decodeFlHex(line.substring(4))
                            if (txt.isNotEmpty()) {
                                lastFlText = txt
                                updateMainDisplayHeader()
                            }
                        }
                        line.startsWith("GHH") || line.startsWith("GHP") || line.startsWith("GIH") || line.startsWith("GIP") -> {
                            val raw = line.substring(3).trim()
                            if (raw.length >= 4) {
                                playTimeText = raw
                                updateMainDisplayHeader()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun send(cmd: String) = sendSilent(cmd)

    private fun sendSilent(cmd: String) {
        scope.launch(sendDispatcher) {
            try {
                logRaw("TX", cmd)
                outWriter?.let { it.write(cmd + "\r\n"); it.flush() }
                Thread.sleep(60)
            } catch (e: Exception) {
                ui { statusText.text = "Chyba odosielania: " + e.message }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { block() }
}
