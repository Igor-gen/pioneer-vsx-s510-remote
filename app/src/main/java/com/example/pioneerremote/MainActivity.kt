// ============================================================================
// Pioneer VSX-S510 Remote — verzia 28 (UI v2 — vizuálny redesign)
// Úpravy UI:
// - Moderná tmavá téma s jemnými gradientmi a tieňmi
// - Väčšie dotykové plochy (min. 48dp) pre lepšiu ergonómiu
// - Lepšia vizuálna hierarchia a zoskupenie prvkov
// - Vylepšené stavové indikátory a kartový dizajn
// - Funkčná logika nezmenená
// ============================================================================

package com.example.pioneerremote

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
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

    // ===== farby (v2 — rafinovaná tmavá téma) =====
    private val C_BG = 0xFF070D17.toInt()
    private val C_CARD = 0xFF111D2E.toInt()
    private val C_CARD2 = 0xFF0D1825.toInt()
    private val C_CARD_HOVER = 0xFF1A2B42.toInt()
    private val C_BTN = 0xFF152238.toInt()
    private val C_BTN_PRESSED = 0xFF1E3352.toInt()
    private val C_STROKE = 0xFF2A3F5C.toInt()
    private val C_STROKE_SUBTLE = 0xFF1E2F47.toInt()
    private val C_CYAN = 0xFF38BDF8.toInt()
    private val C_CYAN_SOFT = 0xFF0EA5E9.toInt()
    private val C_CYAN_D = 0xFF0C4A6E.toInt()
    private val C_CYAN_GLOW = 0x3338BDF8.toInt()
    private val C_ORANGE = 0xFFFB923C.toInt()
    private val C_ORANGE_D = 0xFF9A3412.toInt()
    private val C_RED = 0xFFEF4444.toInt()
    private val C_RED_SOFT = 0xFFDC2626.toInt()
    private val C_GREEN = 0xFF4ADE80.toInt()
    private val C_GREEN_D = 0xFF166534.toInt()
    private val C_WHITE = 0xFFF1F5F9.toInt()
    private val C_SLATE = 0xFF94A3B8.toInt()
    private val C_DIM = 0xFF64748B.toInt()
    private val C_VDIM = 0xFF475569.toInt()
    private val C_ON_ACCENT = 0xFF020617.toInt()
    private val C_AMBER = 0xFFFBBF24.toInt()
    private val C_PURPLE = 0xFFA78BFA.toInt()

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
                f.appendText("$${logTimeFmt.format(Date())} $$tag $text\n")
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
        val header = "Pioneer Remote v28 | host=$$host | vstup=$$activeInput | playlist='$activePlaylistName'" +
            " | adresárov v pamäti=$${usbTreeCache.size} | $${android.os.Build.MODEL}\n"
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

    // ===== KARTA MÉDIÁ =====
    private var isUsbMediaMode: Boolean = true
    private lateinit var modeUsbBtn: Button
    private lateinit var modeDlnaBtn: Button
    private lateinit var usbContainer: LinearLayout
    private lateinit var dlnaContainer: LinearLayout

    // ===== DYNAMICKÉ PLAYLISTY =====
    private data class UsbNode(
        val indexInParent: Int,
        val title: String,
        val isFolder: Boolean,
        val pathKey: String
    )

    private var savedPlaylists = mutableListOf<Pair<String, String>>()
    private var activePlaylistId: String? = null
    private var activePlaylistName: String = "Neuložený kľúč"

    private val usbTreeCache = linkedMapOf<String, MutableList<UsbNode>>()
    private val usbCurrentPath = mutableListOf<Pair<Int, String>>()
    private var usbSelectedPathKey: String? = null
    private val usbNodeButtons = mutableMapOf<String, Button>()
    private val usbCheckButtons = mutableMapOf<String, Button>()

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
        logEv("=== štart aplikácie v28, host=$host ===")
        loadCachedFavorites()
        loadPlaylistsMetadata()
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

    // =====================================================================
    // DYNAMICKÁ SPRÁVA PLAYLISTOV
    // =====================================================================

    private fun loadPlaylistsMetadata() {
        val raw = prefs().getString("playlists_metadata", "[]") ?: "[]"
        savedPlaylists.clear()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                savedPlaylists.add(obj.getString("id") to obj.getString("name"))
            }
        } catch (_: Exception) {}

        val activeId = prefs().getString("active_playlist_id", null)
        if (activeId != null) {
            val pl = savedPlaylists.find { it.first == activeId }
            if (pl != null) {
                loadPlaylist(pl.first, pl.second)
            }
        } else {
            val legacy = prefs().getString("usbTreeSlot_0", null) ?: prefs().getString("usbTreeJsonV22", null)
            if (legacy != null && savedPlaylists.isEmpty()) {
                val id = "legacy_0"
                prefs().edit().putString("playlist_$id", legacy).apply()
                savedPlaylists.add(id to "Môj prvý kľúč")
                savePlaylistsMetadata()
                loadPlaylist(id, "Môj prvý kľúč")
            }
        }
    }

    private fun savePlaylistsMetadata() {
        val arr = JSONArray()
        savedPlaylists.forEach {
            val obj = JSONObject()
            obj.put("id", it.first)
            obj.put("name", it.second)
            arr.put(obj)
        }
        prefs().edit().putString("playlists_metadata", arr.toString()).apply()
    }

    private fun loadPlaylist(id: String, name: String) {
        if (usbScanning) {
            if (::usbStatusText.isInitialized) {
                usbStatusText.text = "⚠️ Počkaj na dokončenie alebo zruš prebiehajúce skenovanie."
            }
            return
        }
        activePlaylistId = id
        activePlaylistName = name
        prefs().edit().putString("active_playlist_id", id).apply()

        usbTreeCache.clear()
        usbCurrentPath.clear()
        usbSelectedPathKey = null

        val raw = prefs().getString("playlist_$id", null) ?: return
        try {
            val rootObj = JSONObject(raw)
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = rootObj.getJSONArray(k)
                val list = mutableListOf<UsbNode>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(UsbNode(o.getInt("idx"), o.getString("title"), o.getBoolean("isFolder"), o.getString("pathKey")))
                }
                usbTreeCache[k] = list
            }
        } catch (_: Exception) {}

        if (::usbStatusText.isInitialized) {
            renderUsbCachedFolder()
            val totalItems = usbTreeCache.values.sumOf { it.size }
            usbStatusText.text = "💾 Playlist '$$name': načítaných $${usbTreeCache.size} adresárov ($totalItems položiek)."
            logEv("Otvorený playlist '$$name' ($$totalItems položiek).")

            updateInfoLine()
            updateMainDisplayHeader()
        }
    }

    private fun showOpenPlaylistDialog() {
        if (savedPlaylists.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Žiadne playlisty")
                .setMessage("Zatiaľ nemáte uložený žiadny obsah USB kľúča. Najprv naskenujte kľúč a uložte ho.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = savedPlaylists.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Otvoriť playlist (USB kľúč)")
            .setItems(names) { _, which ->
                val pl = savedPlaylists[which]
                loadPlaylist(pl.first, pl.second)
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun showSavePlaylistDialog(afterScan: Boolean = false) {
        if (usbTreeCache.isEmpty()) {
            usbStatusText.text = "⚠️ Nie je načítaný žiadny obsah na uloženie."
            return
        }
        val input = EditText(this).apply {
            hint = "Napr. Rock 32GB, Vianoce, Detské piesne..."
            if (activePlaylistName != "Neuložený kľúč") setText(activePlaylistName)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(if (afterScan) "Nové USB načítané — uložiť ako playlist?" else "Uložiť obsah USB ako playlist")
            .setMessage("Zadaj názov, pod ktorým sa tento obsah uloží do pamäte telefónu pre okamžité prehrávanie:")
            .setView(input)
            .setPositiveButton("Uložiť") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Môj playlist ${savedPlaylists.size + 1}" }

                var id = savedPlaylists.find { it.second == name }?.first
                if (id == null) {
                    id = System.currentTimeMillis().toString()
                    savedPlaylists.add(id to name)
                    savePlaylistsMetadata()
                }

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
                prefs().edit().putString("playlist_$id", rootObj.toString()).apply()

                activePlaylistId = id
                activePlaylistName = name
                prefs().edit().putString("active_playlist_id", id).apply()
                renderUsbCachedFolder()
                updateInfoLine()
                updateMainDisplayHeader()

                usbStatusText.text = "✓ Playlist uložený ako '$name'."
                logEv("Uložený playlist '$$name' (id=$$id)")
            }
            .setNegativeButton(if (afterScan) "Neukladať" else "Zrušiť", null)
            .show()
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
    // ŠTÝLOVÉ POMOCNÍKY (v2 — vylepšené)
    // =====================================================================

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeWidthDp: Int = 1): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(strokeWidthDp), strokeColor)
        return d
    }

    private fun gradientBg(topColor: Int, bottomColor: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        )
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(1), strokeColor)
        return d
    }

    private fun glowBg(accentColor: Int, radiusDp: Int): LayerDrawable {
        val glow = GradientDrawable()
        glow.setColor(accentColor)
        glow.cornerRadius = dp(radiusDp + 2).toFloat()

        val inner = GradientDrawable()
        inner.setColor(C_CARD)
        inner.cornerRadius = dp(radiusDp).toFloat()

        val layer = LayerDrawable(arrayOf(glow, inner))
        layer.setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
        return layer
    }

    private fun flatBtn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 12f
        b.maxLines = 2
        b.setTextColor(C_WHITE)
        b.transformationMethod = null
        b.typeface = Typeface.SANS_SERIF
        b.background = roundedBg(C_BTN, 14, C_STROKE_SUBTLE)
        b.setPadding(dp(8), dp(12), dp(8), dp(12))
        b.minimumHeight = dp(44)
        b.isAllCaps = false
        b.elevation = dp(2).toFloat()
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
        b.typeface = Typeface.SANS_SERIF
        b.isAllCaps = false
        b.background = roundedBg(bg, 20)
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        b.minimumWidth = 0
        b.minimumHeight = dp(36)
        b.setOnClickListener { onClick(b) }
        return b
    }

    private fun iconBtn(emoji: String, size: Int = 18, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = emoji
        b.textSize = size.toFloat()
        b.gravity = Gravity.CENTER
        b.setTextColor(C_WHITE)
        b.transformationMethod = null
        b.isAllCaps = false
        b.background = roundedBg(C_BTN, 14, C_STROKE_SUBTLE)
        b.setPadding(dp(4), dp(4), dp(4), dp(4))
        b.minimumWidth = dp(44)
        b.minimumHeight = dp(44)
        b.elevation = dp(1).toFloat()
        b.setOnClickListener { onClick() }
        return b
    }

    private fun highlightBtn(b: Button, active: Boolean, accent: Int = C_CYAN) {
        if (active) {
            b.background = roundedBg(accent, 14, accent)
            b.setTextColor(C_ON_ACCENT)
            b.typeface = Typeface.DEFAULT_BOLD
            b.elevation = dp(4).toFloat()
        } else {
            b.background = roundedBg(C_BTN, 14, C_STROKE_SUBTLE)
            b.setTextColor(C_WHITE)
            b.typeface = Typeface.SANS_SERIF
            b.elevation = dp(2).toFloat()
        }
    }

    private fun rowOf(buttons: List<Button>, gap: Int = 4): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        buttons.forEach {
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.setMargins(dp(gap), dp(gap), dp(gap), dp(gap))
            r.addView(it, lp)
        }
        return r
    }

    private fun makeGrid(buttons: List<Button>, cols: Int = 3): GridLayout {
        val g = GridLayout(this)
        g.columnCount = cols
        g.useDefaultMargins = false
        buttons.forEach { b ->
            val lp = GridLayout.LayoutParams()
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.width = 0
            lp.height = dp(48)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            g.addView(b, lp)
        }
        return g
    }

    private fun sectionLabel(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 11f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(C_CYAN)
        tv.letterSpacing = 0.12f
        tv.setPadding(dp(4), dp(16), dp(4), dp(6))
        return tv
    }

    private fun hint(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 10f
        tv.setTextColor(C_VDIM)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(16), 0, dp(8))
        return tv
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(C_STROKE_SUBTLE)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        lp.setMargins(dp(4), dp(8), dp(4), dp(8))
        v.layoutParams = lp
        return v
    }

    // =====================================================================
    // VYTVORENIE UI (v2 — redesign)
    // =====================================================================

    private fun buildUI() {
        val pad = dp(14)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)
        root.setPadding(pad, dp(6), pad, 0)

        // === HEADER: title + power ===
        val header = FrameLayout(this)
        header.setPadding(0, dp(4), 0, dp(8))

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.gravity = Gravity.CENTER_HORIZONTAL

        val title = TextView(this)
        title.text = "Pioneer Remote"
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(C_WHITE)
        title.gravity = Gravity.CENTER
        title.letterSpacing = 0.02f
        titleBox.addView(title)

        val sub = TextView(this)
        sub.text = "VSX-S510"
        sub.textSize = 11f
        sub.setTextColor(C_CYAN)
        sub.gravity = Gravity.CENTER
        sub.typeface = Typeface.MONOSPACE
        titleBox.addView(sub)

        header.addView(titleBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        val circleSize = dp(48)
        powerBtn = Button(this)
        powerBtn.textSize = 12f
        powerBtn.typeface = Typeface.DEFAULT_BOLD
        powerBtn.gravity = Gravity.CENTER
        powerBtn.transformationMethod = null
        powerBtn.setPadding(0, 0, 0, 0)
        powerBtn.minimumWidth = 0
        powerBtn.minimumHeight = 0
        powerBtn.isAllCaps = false
        powerBtn.setOnClickListener { togglePower() }
        updatePowerButton()

        header.addView(powerBtn, FrameLayout.LayoutParams(
            circleSize, circleSize, Gravity.END or Gravity.CENTER_VERTICAL
        ))

        root.addView(header)

        // === IP + CONN ROW ===
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        ipEdit = EditText(this)
        ipEdit.setText(host)
        ipEdit.hint = "IP adresa receivera"
        ipEdit.inputType = InputType.TYPE_CLASS_PHONE
        ipEdit.setTextColor(C_WHITE)
        ipEdit.setHintTextColor(C_VDIM)
        ipEdit.textSize = 13f
        ipEdit.typeface = Typeface.MONOSPACE
        ipEdit.background = roundedBg(C_CARD2, 14, C_STROKE_SUBTLE)
        ipEdit.setPadding(dp(14), dp(10), dp(14), dp(10))
        ipEdit.minimumHeight = dp(44)

        val ipLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ipLp.marginEnd = dp(6)
        top.addView(ipEdit, ipLp)

        val scanBtn = iconBtn("🔍", 16) { discoverReceivers(autoConnectFirst = false) }
        val scanLp = LinearLayout.LayoutParams(dp(44), dp(44))
        scanLp.marginEnd = dp(6)
        top.addView(scanBtn, scanLp)

        val connBtn = pillBtn("Pripojiť", C_CYAN, C_ON_ACCENT) { connectPressed() }
        connBtn.textSize = 13f
        connBtn.typeface = Typeface.DEFAULT_BOLD
        connBtn.minimumHeight = dp(44)
        top.addView(connBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))

        root.addView(top)

        // === DISPLAY CARD ===
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(12))
        card.background = gradientBg(C_CARD, C_CARD2, 20, C_CYAN_D)
        card.elevation = dp(6).toFloat()

        mainStationBig = TextView(this)
        mainStationBig.text = "📻  —"
        mainStationBig.textSize = 21f
        mainStationBig.typeface = Typeface.DEFAULT_BOLD
        mainStationBig.setTextColor(C_WHITE)
        mainStationBig.gravity = Gravity.CENTER
        mainStationBig.maxLines = 2
        mainStationBig.letterSpacing = 0.01f

        mainShowBitrate = TextView(this)
        mainShowBitrate.text = "Relácia / Skladba: —   ·   Bitrate: —"
        mainShowBitrate.textSize = 11f
        mainShowBitrate.setTextColor(C_CYAN)
        mainShowBitrate.gravity = Gravity.CENTER
        mainShowBitrate.setPadding(0, dp(4), 0, dp(2))

        infoLine = TextView(this)
        infoLine.text = "Vstup: —   ·   Režim: —"
        infoLine.textSize = 11f
        infoLine.setTextColor(C_SLATE)
        infoLine.gravity = Gravity.CENTER
        infoLine.setPadding(0, dp(2), 0, dp(2))

        connLine = TextView(this)
        connLine.text = "○  Odpojené ($host)"
        connLine.textSize = 10f
        connLine.setTextColor(C_RED)
        connLine.gravity = Gravity.CENTER
        connLine.typeface = Typeface.MONOSPACE

        statusText = TextView(this)
        statusText.text = ""
        statusText.textSize = 11f
        statusText.setTextColor(C_ORANGE)
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(2), 0, dp(2))

        // Volume row
        val volBox = LinearLayout(this)
        volBox.orientation = LinearLayout.HORIZONTAL
        volBox.gravity = Gravity.CENTER_VERTICAL
        volBox.setPadding(dp(4), dp(10), dp(4), dp(4))

        volumeLabel = TextView(this)
        volumeLabel.text = "🔊 —"
        volumeLabel.textSize = 13f
        volumeLabel.typeface = Typeface.DEFAULT_BOLD
        volumeLabel.setTextColor(C_GREEN)
        volumeLabel.setPadding(0, 0, dp(12), 0)

        volBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        volBar.max = volMax
        volBar.progress = 0
        volBar.progressTintList = ColorStateList.valueOf(C_GREEN)
        volBar.progressBackgroundTintList = ColorStateList.valueOf(C_STROKE)
        volBar.isClickable = false
        volBar.isFocusable = false

        val barLp = LinearLayout.LayoutParams(0, dp(8), 1f)
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
        cardLp.topMargin = dp(8)
        root.addView(card, cardLp)

        // === TAB BAR (segmented control style) ===
        val tabDefs = listOf("🎚  Ovládanie", "📻  Rádio", "💽  Médiá")
        val tabBtns = tabDefs.map { t -> pillBtn(t, C_BTN, C_SLATE) { } }

        val tabsContainer = LinearLayout(this)
        tabsContainer.orientation = LinearLayout.HORIZONTAL
        tabsContainer.gravity = Gravity.CENTER
        tabsContainer.setPadding(dp(2), dp(2), dp(2), dp(2))
        tabsContainer.background = roundedBg(C_CARD2, 22, C_STROKE_SUBTLE)

        tabBtns.forEachIndexed { i, b ->
            b.textSize = 12f
            b.setPadding(dp(8), dp(10), dp(8), dp(10))
            b.minimumHeight = dp(40)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = dp(2)
            tabsContainer.addView(b, lp)
        }

        val tabsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tabsLp.topMargin = dp(10)
        tabsLp.bottomMargin = dp(2)
        root.addView(tabsContainer, tabsLp)

        // === GLOBAL VOLUME CONTROLS ===
        muteBtn = flatBtn("🔇  Mute") { toggleMute() }
        val volMinus = flatBtn("➖  Vol") { send("VD"); sendSilent("?V") }
        val volPlus = flatBtn("Vol  ➕") { send("VU"); sendSilent("?V") }
        muteBtn.textSize = 13f
        volMinus.textSize = 13f
        volPlus.textSize = 13f
        volMinus.typeface = Typeface.DEFAULT_BOLD
        volPlus.typeface = Typeface.DEFAULT_BOLD

        val globalVolLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        globalVolLp.topMargin = dp(6)
        globalVolLp.bottomMargin = dp(2)
        root.addView(rowOf(listOf(volMinus, muteBtn, volPlus), gap = 5), globalVolLp)

        // === VIEWPAGER ===
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

        root.addView(pager)
        setContentView(root)
    }

    private fun styleTab(btn: Button, active: Boolean) {
        if (active) {
            btn.background = roundedBg(C_ORANGE, 20, C_ORANGE)
            btn.setTextColor(C_ON_ACCENT)
