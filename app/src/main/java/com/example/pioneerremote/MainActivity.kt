// ============================================================================
// Pioneer VSX-S510 Remote — verzia 34
// Novinky oproti v33:
// 1. Karta RÁDIO / DLNA server: možnosť vybrať a trvalo si zapamätať adresár
//    s médiami v telefóne (SAF + trvalé oprávnenie čítania).
// 2. Pri zapnutí servera (Server: VYP -> ZAP) ponúkne výber adresára, ak ešte
//    nie je zvolený. Ak už zvolený je, použije sa bez pýtania.
// 3. Zapamätaný adresár ostáva uložený aj po vypnutí DLNA servera a reštarte appky.
// 4. Vstavaný DLNA server sprístupňuje aj obsah zvoleného adresára (vrátane
//    podadresárov a streamovania audio súborov s podporou HTTP Range).
// ============================================================================

package com.example.pioneerremote

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
    private val connectGen = AtomicInteger(0)
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

    private var autoReconnectAllowed = true
    @Volatile private var appInForeground = false

    // Jedno vlákno na odosielanie => príkazy idú v poradí a nikdy sa neprelínajú
    private val sendDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

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
    private lateinit var rootLayout: LinearLayout
    private lateinit var topScroll: ScrollView
    private lateinit var mainPager: ViewPager2

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
    @Volatile private var playTimeText: String = ""
    private var lastFlText: String = ""

    // ===== KARTA MÉDIÁ: PREPÍNAČ USB KĽÚČ / DLNA SERVER =====
    private var isUsbMediaMode: Boolean = true
    private lateinit var modeUsbBtn: Button
    private lateinit var modeDlnaBtn: Button
    private lateinit var usbContainer: LinearLayout
    private lateinit var dlnaContainer: LinearLayout

    // ===== DYNAMICKÉ PLAYLISTY PRE USB KĽÚČE =====
    private data class UsbNode(
        val indexInParent: Int,
        val title: String,
        val isFolder: Boolean,
        val pathKey: String
    )

    private var savedPlaylists = mutableListOf<Pair<String, String>>() // <ID, Názov>
    private var activePlaylistId: String? = null
    private var activePlaylistName: String = "Neuložený kľúč"

    private val usbTreeCache = linkedMapOf<String, MutableList<UsbNode>>()
    private val usbCurrentPath = mutableListOf<Pair<Int, String>>()
    private val usbSelectedPathKeys = linkedSetOf<String>()   // zachováva poradie označovania
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
    private lateinit var usbTransportBar: LinearLayout

    // DLNA: označovanie (albumy viacnásobne, súbor iba jeden)
    private val dlnaSelectedContainers = linkedMapOf<String, MediaEntry>()
    private var dlnaSelectedFile: MediaEntry? = null
    private var dlnaFileSiblings = listOf<MediaEntry>()
    private val dlnaNodeButtons = mutableMapOf<String, Button>()
    private val dlnaCheckButtons = mutableMapOf<String, Button>()
    private lateinit var mediaSelectedText: TextView

    // ===== USB prehrávanie =====
    private enum class UsbState { STOPPED, PLAYING }
    @Volatile private var usbState = UsbState.STOPPED
    private var usbStartedKey: String? = null
    @Volatile private var usbBusy = false
    @Volatile private var usbScanning = false
    @Volatile private var lastErrAt = 0L

    // Fronta (Queue) pre prehrávanie označených adresárov
    @Volatile private var usbPlayQueue = listOf<UsbNode>()
    @Volatile private var usbPlayQueueIndex = 0

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
    // SQLite DATABÁZA USB KĽÚČOV (jeden súbor pioneer_usb.db pre všetky kľúče)
    // =====================================================================

    companion object {
        const val DB_NAME = "pioneer_usb.db"
        const val REQ_EXPORT_DB = 4101
        const val REQ_IMPORT_DB = 4102
        const val REQ_PICK_MEDIA_DIR = 4103
    }

    private lateinit var usbDb: UsbDatabase

    private class UsbDatabase(context: Context) :
        SQLiteOpenHelper(context, MainActivity.DB_NAME, null, 2) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.disableWriteAheadLogging()   // jeden samostatný súbor bez -wal / -shm
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE usb (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL UNIQUE, saved_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE node (usb_id INTEGER NOT NULL, parent_key TEXT NOT NULL, " +
                "idx INTEGER NOT NULL, title TEXT NOT NULL, is_folder INTEGER NOT NULL, " +
                "path_key TEXT NOT NULL, PRIMARY KEY (usb_id, path_key))")
            db.execSQL("CREATE INDEX node_parent ON node (usb_id, parent_key, idx)")
            db.execSQL("CREATE TABLE IF NOT EXISTS station (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, url TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("CREATE TABLE IF NOT EXISTS station (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, url TEXT NOT NULL)")
            }
        }

        fun listStations(): List<RadioStation> {
            val out = mutableListOf<RadioStation>()
            readableDatabase.rawQuery("SELECT id, name, url FROM station ORDER BY name COLLATE NOCASE", null).use { c ->
                while (c.moveToNext()) out.add(RadioStation(c.getLong(0), c.getString(1), c.getString(2)))
            }
            return out
        }

        fun saveStation(id: Long?, name: String, url: String) {
            val cv = ContentValues()
            cv.put("name", name)
            cv.put("url", url)
            if (id == null) {
                writableDatabase.insertOrThrow("station", null, cv)
            } else {
                writableDatabase.update("station", cv, "id = ?", arrayOf(id.toString()))
            }
        }

        fun deleteStation(id: Long) {
            writableDatabase.delete("station", "id = ?", arrayOf(id.toString()))
        }

        fun listSticks(): List<Pair<Long, String>> {
            val out = mutableListOf<Pair<Long, String>>()
            readableDatabase.rawQuery("SELECT id, name FROM usb ORDER BY name COLLATE NOCASE", null).use { c ->
                while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
            }
            return out
        }

        fun hasStick(name: String): Boolean {
            var found = false
            readableDatabase.rawQuery("SELECT 1 FROM usb WHERE name = ?", arrayOf(name)).use { c ->
                found = c.moveToFirst()
            }
            return found
        }

        fun deleteStick(id: Long) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("node", "usb_id = ?", arrayOf(id.toString()))
                db.delete("usb", "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        // Uloží (alebo prepíše) kľúč s daným názvom; vráti jeho id
        fun saveStick(name: String, tree: Map<String, List<UsbNode>>): Long {
            val db = writableDatabase
            db.beginTransaction()
            try {
                var id = -1L
                db.rawQuery("SELECT id FROM usb WHERE name = ?", arrayOf(name)).use { c ->
                    if (c.moveToFirst()) id = c.getLong(0)
                }
                val cv = ContentValues()
                cv.put("saved_at", System.currentTimeMillis())
                if (id >= 0) {
                    db.delete("node", "usb_id = ?", arrayOf(id.toString()))
                    db.update("usb", cv, "id = ?", arrayOf(id.toString()))
                } else {
                    cv.put("name", name)
                    id = db.insertOrThrow("usb", null, cv)
                }
                val stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO node (usb_id, parent_key, idx, title, is_folder, path_key) " +
                        "VALUES (?, ?, ?, ?, ?, ?)")
                tree.forEach { (parentKey, list) ->
                    list.forEach { n ->
                        stmt.clearBindings()
                        stmt.bindLong(1, id)
                        stmt.bindString(2, parentKey)
                        stmt.bindLong(3, n.indexInParent.toLong())
                        stmt.bindString(4, n.title)
                        stmt.bindLong(5, if (n.isFolder) 1L else 0L)
                        stmt.bindString(6, n.pathKey)
                        stmt.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
                return id
            } finally {
                db.endTransaction()
            }
        }

        fun loadTree(usbId: Long): LinkedHashMap<String, MutableList<UsbNode>> {
            val out = linkedMapOf<String, MutableList<UsbNode>>()
            readableDatabase.rawQuery(
                "SELECT parent_key, idx, title, is_folder, path_key FROM node " +
                    "WHERE usb_id = ? ORDER BY parent_key, idx", arrayOf(usbId.toString())).use { c ->
                while (c.moveToNext()) {
                    out.getOrPut(c.getString(0)) { mutableListOf() }
                        .add(UsbNode(c.getInt(1), c.getString(2), c.getInt(3) == 1, c.getString(4)))
                }
            }
            val folderKeys = out.values.flatten().filter { it.isFolder }.map { it.pathKey }
            folderKeys.forEach { out.getOrPut(it) { mutableListOf() } }
            return out
        }
    }

    // =====================================================================
    // ŽIVOTNÝ CYKLUS A SPRÁVA PRIPOJENIA
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        val p = prefs()
        host = p.getString("host", host) ?: host
        mediaServerDesc = p.getString("dlnaServer", null)
        mediaServerName = p.getString("dlnaName", "") ?: ""
        isUsbMediaMode = p.getBoolean("isUsbMediaMode", true)
        usbDb = UsbDatabase(applicationContext)
        refreshRadioStations()
        loadSavedMediaDir()
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

    override fun onStart() {
        super.onStart()
        appInForeground = true
        if (autoReconnectAllowed && socketRef.get() == null) {
            connect(silentFail = true)
        }
    }

    override fun onStop() {
        super.onStop()
        appInForeground = false
        if (usbScanning || usbState == UsbState.PLAYING) return
        disconnectSocket()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
        disconnectSocket()
        multicastLock?.release()
        sendDispatcher.close()
        val srv = radioServer
        radioServer = null
        if (srv != null) Thread { srv.stop() }.start()
        if (::usbDb.isInitialized) usbDb.close()
    }

    private fun disconnectSocket() {
        val sock = socketRef.getAndSet(null)
        runCatching { sock?.close() }
        outWriter = null
        ui {
            if (::connLine.isInitialized) {
                connLine.text = "○  Odpojené (na pozadí)"
                connLine.setTextColor(C_DIM)
            }
        }
    }

    private fun disconnectIfBackground() {
        if (!appInForeground && !usbScanning && usbState != UsbState.PLAYING) {
            disconnectSocket()
        }
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
        migrateLegacyPlaylistsToDb()
        refreshPlaylistList()
        val activeName = prefs().getString("active_playlist_name", null)
        if (activeName != null) {
            val pl = savedPlaylists.find { it.second == activeName }
            if (pl != null) {
                loadPlaylist(pl.first, pl.second)
            }
        }
    }

    private fun refreshPlaylistList() {
        savedPlaylists.clear()
        try {
            usbDb.listSticks().forEach { savedPlaylists.add(it.first.toString() to it.second) }
        } catch (_: Exception) {}
    }

    private fun parseTreeJson(raw: String): LinkedHashMap<String, MutableList<UsbNode>> {
        val out = linkedMapOf<String, MutableList<UsbNode>>()
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
                out[k] = list
            }
        } catch (_: Exception) {}
        return out
    }

    private fun migrateLegacyPlaylistsToDb() {
        if (prefs().getBoolean("playlists_migrated_to_db", false)) return
        try {
            val oldActiveId = prefs().getString("active_playlist_id", null)
            val arr = JSONArray(prefs().getString("playlists_metadata", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val raw = prefs().getString("playlist_$id", null) ?: continue
                val tree = parseTreeJson(raw)
                if (tree.isNotEmpty() && !usbDb.hasStick(name)) usbDb.saveStick(name, tree)
                if (id == oldActiveId) prefs().edit().putString("active_playlist_name", name).apply()
            }
            val legacy = prefs().getString("usbTreeSlot_0", null) ?: prefs().getString("usbTreeJsonV22", null)
            if (legacy != null && !usbDb.hasStick("Môj prvý kľúč")) {
                val tree = parseTreeJson(legacy)
                if (tree.isNotEmpty()) usbDb.saveStick("Môj prvý kľúč", tree)
            }
        } catch (_: Exception) {}
        prefs().edit().putBoolean("playlists_migrated_to_db", true).apply()
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
        prefs().edit().putString("active_playlist_name", name).apply()

        usbTreeCache.clear()
        usbCurrentPath.clear()
        usbSelectedPathKeys.clear()

        val tree = try { usbDb.loadTree(id.toLong()) } catch (_: Exception) { null }
        if (tree != null) usbTreeCache.putAll(tree)

        if (::usbStatusText.isInitialized) {
            renderUsbCachedFolder()
            val totalItems = usbTreeCache.values.sumOf { it.size }
            usbStatusText.text = if (tree != null && tree.isNotEmpty())
                "💾 Kľúč '$name' z databázy: ${usbTreeCache.size} adresárov ($totalItems položiek)."
            else
                "⚠️ Kľúč '$name' sa nepodarilo načítať z databázy."
            updateInfoLine()
            updateMainDisplayHeader()
        }
    }

    // ---------- záloha / obnova súboru databázy + výber adresára médií ----------

    private fun startExportDb() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/octet-stream"
        i.putExtra(Intent.EXTRA_TITLE, MainActivity.DB_NAME)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(i, MainActivity.REQ_EXPORT_DB)
    }

    private fun startImportDb() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(i, MainActivity.REQ_IMPORT_DB)
    }

    private fun startPickMediaDir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, MainActivity.REQ_PICK_MEDIA_DIR)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MainActivity.REQ_PICK_MEDIA_DIR) {
            val shouldStart = pendingStartRadioServerAfterDirPick
            pendingStartRadioServerAfterDirPick = false
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        if (takeFlags != 0) takeFlags else Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val name = resolveTreeDisplayName(uri)
                mediaDirUri = uri
                mediaDirName = name
                dlnaUpdateId++
                prefs().edit()
                    .putString("dlna_media_dir_uri", uri.toString())
                    .putString("dlna_media_dir_name", name)
                    .apply()
                updateRadioServerUi()
                if (shouldStart) {
                    startRadioServerNow()
                }
            } else if (shouldStart) {
                if (::radioServerStatus.isInitialized) {
                    radioServerStatus.text = "Výber adresára zrušený. Server ostal vypnutý."
                }
            }
            return
        }

        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        if (requestCode == MainActivity.REQ_EXPORT_DB || requestCode == MainActivity.REQ_IMPORT_DB) {
            val takeFlags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(uri, takeFlags) }
            prefs().edit().putString("db_backup_uri", uri.toString()).apply()
        }
        when (requestCode) {
            MainActivity.REQ_EXPORT_DB -> backupDbNow(showStatus = true)
            MainActivity.REQ_IMPORT_DB -> importDbFrom(uri)
        }
    }

    private fun backupDbNow(showStatus: Boolean) {
        val uriStr = prefs().getString("db_backup_uri", null) ?: return
        val uri = Uri.parse(uriStr)
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val dbFile = applicationContext.getDatabasePath(MainActivity.DB_NAME)
                contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    FileInputStream(dbFile).use { it.copyTo(out) }
                }
            }.isSuccess
            ui {
                if (::usbStatusText.isInitialized && (showStatus || !ok)) {
                    usbStatusText.text = if (ok)
                        "💾 Databáza zálohovaná do súboru (${savedPlaylists.size} kľúčov)."
                    else
                        "⚠️ Zálohu databázy sa nepodarilo zapísať — vyber súbor znova (⬆ Export databázy)."
                }
            }
        }
    }

    private fun importDbFrom(uri: Uri) {
        if (usbScanning) {
            usbStatusText.text = "⚠️ Počkaj na dokončenie skenovania."
            return
        }
        usbStatusText.text = "⏳ Importujem databázu…"
        scope.launch(Dispatchers.IO) {
            var err: String? = null
            val tmp = File(cacheDir, "import_usb.db")
            try {
                contentResolver.openInputStream(uri)!!.use { inp ->
                    FileOutputStream(tmp).use { inp.copyTo(it) }
                }
                val head = ByteArray(16)
                FileInputStream(tmp).use { it.read(head) }
                if (String(head, Charsets.US_ASCII) != "SQLite format 3\u0000") {
                    throw IllegalStateException("súbor nie je SQLite databáza")
                }
                val check = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    check.rawQuery("SELECT COUNT(*) FROM usb", null).use { it.moveToFirst() }
                    check.rawQuery("SELECT COUNT(*) FROM node", null).use { it.moveToFirst() }
                } finally {
                    check.close()
                }
                usbDb.close()
                val dest = applicationContext.getDatabasePath(MainActivity.DB_NAME)
                dest.parentFile?.mkdirs()
                tmp.copyTo(dest, overwrite = true)
                File(dest.path + "-journal").delete()
                File(dest.path + "-wal").delete()
                File(dest.path + "-shm").delete()
            } catch (e: Exception) {
                err = e.message ?: "neznáma chyba"
            } finally {
                tmp.delete()
            }
            ui {
                if (err != null) {
                    usbStatusText.text = "⚠️ Import databázy zlyhal: $err"
                } else {
                    refreshPlaylistList()
                    refreshRadioStations()
                    renderRadioStations()
                    val activeName = prefs().getString("active_playlist_name", null)
                    val pl = savedPlaylists.find { it.second == activeName }
                    if (pl != null) loadPlaylist(pl.first, pl.second)
                    usbStatusText.text = "✓ Databáza importovaná: ${savedPlaylists.size} kľúčov. Otvor ho cez 📂 Otvor playlist."
                }
            }
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
                val id = try { usbDb.saveStick(name, usbTreeCache) } catch (_: Exception) { -1L }
                if (id < 0) {
                    usbStatusText.text = "⚠️ Uloženie do databázy zlyhalo."
                } else {
                    refreshPlaylistList()
                    activePlaylistId = id.toString()
                    activePlaylistName = name
                    prefs().edit().putString("active_playlist_name", name).apply()
                    renderUsbCachedFolder()
                    updateInfoLine()
                    updateMainDisplayHeader()
                    usbStatusText.text = "✓ Uložené do databázy ako '$name' (${savedPlaylists.size} kľúčov v databáze)."
                    backupDbNow(showStatus = false)
                }
            }
            .setNegativeButton(if (afterScan) "Neukladať" else "Zrušiť", null)
            .show()
    }

    // ---------- rozbaľovacie ponuky ----------

    private fun showPlaylistMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Načítať playlist")
        pm.menu.add(0, 2, 1, "Uložiť playlist")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showOpenPlaylistDialog()
                2 -> showSavePlaylistDialog(afterScan = false)
            }
            true
        }
        pm.show()
    }

    private fun showDatabaseMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Načítať databázu (zo súboru)")
        pm.menu.add(0, 2, 1, "Uložiť USB do databázy")
        pm.menu.add(0, 3, 2, "Vymazať USB z databázy")
        pm.menu.add(0, 4, 3, "Zálohovať databázu do súboru")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmImportDb()
                2 -> showAddUsbToDbDialog(afterScan = false)
                3 -> showDeleteUsbDialog()
                4 -> startExportDb()
            }
            true
        }
        pm.show()
    }

    private fun confirmImportDb() {
        AlertDialog.Builder(this)
            .setTitle("Načítať databázu zo súboru?")
            .setMessage("Databáza v aplikácii (všetky uložené USB kľúče) sa nahradí obsahom vybraného súboru. Pokračovať?")
            .setPositiveButton("Vybrať súbor") { _, _ -> startImportDb() }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun uniqueUsbName(base: String): String {
        var name = base
        var n = 2
        while (savedPlaylists.any { it.second.equals(name, ignoreCase = true) }) {
            name = "$base ($n)"
            n++
        }
        return name
    }

    private fun showAddUsbToDbDialog(afterScan: Boolean) {
        if (usbTreeCache.isEmpty()) {
            usbStatusText.text = "⚠️ Nie je načítaný žiadny USB kľúč na uloženie."
            return
        }
        val input = EditText(this).apply {
            hint = "Názov USB kľúča"
            setText(uniqueUsbName("USB kľúč ${savedPlaylists.size + 1}"))
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(if (afterScan) "Nové USB načítané — pridať do databázy?" else "Uložiť USB do databázy")
            .setMessage("USB sa pridá do databázy ako nový záznam. Už uložené kľúče sa neprepíšu.")
            .setView(input)
            .setPositiveButton("Pridať") { _, _ ->
                val base = input.text.toString().trim().ifEmpty { "USB kľúč ${savedPlaylists.size + 1}" }
                val name = uniqueUsbName(base)
                val id = try { usbDb.saveStick(name, usbTreeCache) } catch (_: Exception) { -1L }
                if (id < 0) {
                    usbStatusText.text = "⚠️ Uloženie do databázy zlyhalo."
                } else {
                    refreshPlaylistList()
                    activePlaylistId = id.toString()
                    activePlaylistName = name
                    prefs().edit().putString("active_playlist_name", name).apply()
                    renderUsbCachedFolder()
                    updateInfoLine()
                    updateMainDisplayHeader()
                    usbStatusText.text = "✓ USB pridané do databázy ako '$name' (${savedPlaylists.size} kľúčov v databáze)."
                    backupDbNow(showStatus = false)
                }
            }
            .setNegativeButton(if (afterScan) "Neukladať" else "Zrušiť", null)
            .show()
    }

    private fun showDeleteUsbDialog() {
        if (savedPlaylists.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Databáza je prázdna")
                .setMessage("Nie je čo vymazať — v databáze nie je uložený žiadny USB kľúč.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = savedPlaylists.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vymazať USB z databázy")
            .setItems(names) { _, which ->
                val pl = savedPlaylists[which]
                AlertDialog.Builder(this)
                    .setTitle("Vymazať '${pl.second}'?")
                    .setMessage("USB kľúč sa natrvalo odstráni z databázy.")
                    .setPositiveButton("Vymazať") { _, _ -> deleteUsbFromDb(pl.first.toLong(), pl.second) }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun deleteUsbFromDb(id: Long, name: String) {
        if (usbScanning) {
            usbStatusText.text = "⚠️ Počkaj na dokončenie skenovania."
            return
        }
        val ok = try { usbDb.deleteStick(id); true } catch (_: Exception) { false }
        if (!ok) {
            usbStatusText.text = "⚠️ Vymazanie z databázy zlyhalo."
            return
        }
        refreshPlaylistList()
        if (activePlaylistName == name) {
            usbTreeCache.clear()
            usbCurrentPath.clear()
            usbSelectedPathKeys.clear()
            activePlaylistId = null
            activePlaylistName = "Neuložený kľúč"
            prefs().edit().remove("active_playlist_name").apply()
            renderUsbCachedFolder()
            updateInfoLine()
            updateMainDisplayHeader()
        }
        usbStatusText.text = "🗑 USB '$name' vymazané z databázy (v databáze ostáva ${savedPlaylists.size})."
        backupDbNow(showStatus = false)
    }

    // =====================================================================
    // VLASTNÝ DLNA SERVER (RÁDIOSTANICE + ZVOLENÝ ADRESÁR S MÉDIAMI)
    // =====================================================================

    private data class RadioStation(val id: Long, val name: String, val url: String)
    private data class LocalDocEntry(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val isDir: Boolean
    )

    @Volatile private var radioStations = listOf<RadioStation>()
    @Volatile private var radioServer: RadioDlnaServer? = null
    @Volatile private var mediaDirUri: Uri? = null
    @Volatile private var mediaDirName: String = ""
    @Volatile private var dlnaUpdateId: Int = 1
    private var pendingStartRadioServerAfterDirPick = false

    private lateinit var radioServerBtn: Button
    private lateinit var radioMediaDirBtn: Button
    private lateinit var radioMediaDirText: TextView
    private lateinit var radioServerStatus: TextView
    private lateinit var radioListLayout: LinearLayout

    private fun wifiIp(): String? {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (n in nets) {
                if (n.isLoopback || !n.isUp) continue
                for (a in n.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        if (n.name.startsWith("wlan") || n.name.startsWith("eth")) return a.hostAddress
                        if (fallback == null) fallback = a.hostAddress
                    }
                }
            }
            fallback
        } catch (_: Exception) { null }
    }

    private fun refreshRadioStations() {
        radioStations = try { usbDb.listStations() } catch (_: Exception) { emptyList() }
        dlnaUpdateId++
    }

    private fun resolveTreeDisplayName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (docId != null) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val queried = runCatching {
                contentResolver.query(
                    docUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            if (!queried.isNullOrBlank()) return queried
            val fallback = docId.substringAfterLast(':').substringAfterLast('/')
            if (fallback.isNotBlank()) return fallback
        }
        return "Médiá"
    }

    private fun loadSavedMediaDir() {
        val uriStr = prefs().getString("dlna_media_dir_uri", null) ?: return
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return
        val hasPerm = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (hasPerm) {
            mediaDirUri = uri
            mediaDirName = prefs().getString("dlna_media_dir_name", null)
                ?.takeIf { it.isNotBlank() }
                ?: resolveTreeDisplayName(uri)
        } else {
            mediaDirUri = null
            mediaDirName = ""
            prefs().edit().remove("dlna_media_dir_uri").remove("dlna_media_dir_name").apply()
        }
    }

    private fun clearSavedMediaDir() {
        val old = mediaDirUri
        mediaDirUri = null
        mediaDirName = ""
        dlnaUpdateId++
        prefs().edit().remove("dlna_media_dir_uri").remove("dlna_media_dir_name").apply()
        if (old != null) {
            runCatching {
                contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        updateRadioServerUi()
    }

    private fun onMediaDirButtonClicked() {
        if (mediaDirUri == null) {
            pendingStartRadioServerAfterDirPick = false
            startPickMediaDir()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Adresár s médiami")
            .setMessage("Aktuálne zapamätaný adresár: $mediaDirName")
            .setPositiveButton("Zmeniť adresár") { _, _ ->
                pendingStartRadioServerAfterDirPick = false
                startPickMediaDir()
            }
            .setNeutralButton("Odobrať adresár") { _, _ ->
                clearSavedMediaDir()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun isSupportedDlnaAudio(name: String, mime: String): Boolean {
        val l = name.lowercase()
        val exts = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".wma", ".ogg", ".alac", ".aif", ".aiff")
        if (exts.any { l.endsWith(it) }) return true
        return mime.startsWith("audio/", ignoreCase = true)
    }

    private fun resolveAudioMime(name: String, mime: String): String {
        val l = name.lowercase()
        return when {
            l.endsWith(".mp3") -> "audio/mpeg"
            l.endsWith(".flac") -> "audio/flac"
            l.endsWith(".wav") -> "audio/wav"
            l.endsWith(".m4a") || l.endsWith(".alac") -> "audio/mp4"
            l.endsWith(".aac") -> "audio/aac"
            l.endsWith(".wma") -> "audio/x-ms-wma"
            l.endsWith(".ogg") -> "audio/ogg"
            l.endsWith(".aif") || l.endsWith(".aiff") -> "audio/aiff"
            mime.startsWith("audio/", ignoreCase = true) -> mime
            else -> "audio/mpeg"
        }
    }

    private fun listMediaDirChildren(treeUri: Uri, parentDocId: String): List<LocalDocEntry> {
        val out = mutableListOf<LocalDocEntry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        runCatching {
            contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
                val iId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val docId = if (iId >= 0) c.getString(iId) else continue
                    val name = if (iName >= 0) (c.getString(iName) ?: "") else ""
                    if (name.isEmpty() || name.startsWith(".")) continue
                    val mime = if (iMime >= 0) (c.getString(iMime) ?: "") else ""
                    val size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else -1L
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir || isSupportedDlnaAudio(name, mime)) {
                        out.add(LocalDocEntry(docId, name, resolveAudioMime(name, mime), size, isDir))
                    }
                }
            }
        }
        return out.sortedWith(compareBy<LocalDocEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }

    private fun queryMediaDoc(treeUri: Uri, docId: String): LocalDocEntry? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        return runCatching {
            contentResolver.query(docUri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0) ?: docId.substringAfterLast('/')
                val mime = c.getString(1) ?: ""
                val size = if (!c.isNull(2)) c.getLong(2) else -1L
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                LocalDocEntry(docId, name, resolveAudioMime(name, mime), size, isDir)
            }
        }.getOrNull()
    }

    private fun buildRadioServerSection(page: LinearLayout) {
        page.addView(sectionLabel("VLASTNÉ STANICE A MÉDIÁ (DLNA SERVER)"))

        radioServerBtn = flatBtn("📡 Server: VYP") { toggleRadioServer() }
        val addBtn = flatBtn("➕ Pridať stanicu") { showStationDialog(null) }
        radioServerBtn.textSize = 13f
        addBtn.textSize = 13f
        page.addView(rowOf(listOf(radioServerBtn, addBtn)))

        radioMediaDirBtn = flatBtn("📁 Adresár médií") { onMediaDirButtonClicked() }
        radioMediaDirBtn.textSize = 12f
        val toMediaBtn = flatBtn("↪ Prepnúť na MEDIA SRV") {
            abandonUsbPlayback()
            send("44FN")
            sendSilent("?F")
            activeInput = "MEDIA SRV"
            updateInfoLine()
            styleInputButtons()
            updateMainDisplayHeader()
        }
        toMediaBtn.textSize = 12f
        page.addView(rowOf(listOf(radioMediaDirBtn, toMediaBtn)))

        radioMediaDirText = TextView(this)
        radioMediaDirText.textSize = 11f
        radioMediaDirText.setTextColor(C_CYAN)
        radioMediaDirText.setPadding(dp(4), dp(4), dp(4), dp(2))
        page.addView(radioMediaDirText)

        radioServerStatus = TextView(this)
        radioServerStatus.textSize = 11f
        radioServerStatus.setTextColor(C_SLATE)
        radioServerStatus.setPadding(dp(4), dp(2), dp(4), dp(4))
        page.addView(radioServerStatus)

        radioListLayout = LinearLayout(this)
        radioListLayout.orientation = LinearLayout.VERTICAL
        page.addView(radioListLayout)

        updateRadioServerUi()
        renderRadioStations()
    }

    private fun updateRadioServerUi() {
        if (!::radioServerBtn.isInitialized) return
        val srv = radioServer
        val hasDir = mediaDirUri != null
        radioServerBtn.text = if (srv != null) "📡 Server: ZAP" else "📡 Server: VYP"
        highlightBtn(radioServerBtn, srv != null, C_GREEN)

        if (::radioMediaDirBtn.isInitialized) {
            radioMediaDirBtn.text = if (hasDir) "📁 Adresár: $mediaDirName" else "📁 Vybrať adresár médií"
            highlightBtn(radioMediaDirBtn, hasDir, C_CYAN)
        }
        if (::radioMediaDirText.isInitialized) {
            radioMediaDirText.text = if (hasDir)
                "📁 Zapamätaný adresár médií: $mediaDirName"
            else
                "📁 Adresár médií: (nevybraný)"
        }
        radioServerStatus.text = if (srv != null) {
            val extra = if (hasDir) " + adresár „$mediaDirName“" else ""
            "Server beží (${srv.ip}:${srv.port})$extra. Na receiveri: vstup MEDIA SERVER → „Pioneer Remote Radio“. Apku nechaj otvorenú."
        } else {
            "Server je vypnutý. Zapni ho — na receiveri (MEDIA SERVER) sa zobrazia stanice aj zvolený adresár médií."
        }
    }

    private fun renderRadioStations() {
        if (!::radioListLayout.isInitialized) return
        radioListLayout.removeAllViews()
        if (radioStations.isEmpty()) {
            val t = TextView(this)
            t.text = "(Zatiaľ žiadne stanice — pridaj ich tlačidlom ➕ Pridať stanicu)"
            t.textSize = 11f
            t.setTextColor(C_DIM)
            t.setPadding(dp(4), dp(8), dp(4), dp(8))
            radioListLayout.addView(t)
            return
        }
        radioStations.forEach { st ->
            val b = flatBtn("📻  ${st.name}\n${st.url}") { }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 12f
            b.ellipsize = TextUtils.TruncateAt.END
            b.setPadding(dp(14), dp(8), dp(14), dp(8))
            b.setOnClickListener { showStationMenu(b, st) }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            radioListLayout.addView(b, lp)
        }
    }

    private fun showStationMenu(anchor: View, st: RadioStation) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Upraviť")
        pm.menu.add(0, 2, 1, "Zmazať")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showStationDialog(st)
                2 -> AlertDialog.Builder(this)
                    .setTitle("Zmazať '${st.name}'?")
                    .setPositiveButton("Zmazať") { _, _ ->
                        runCatching { usbDb.deleteStation(st.id) }
                        refreshRadioStations()
                        renderRadioStations()
                        backupDbNow(showStatus = false)
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            }
            true
        }
        pm.show()
    }

    private fun showStationDialog(existing: RadioStation?) {
        val nameIn = EditText(this).apply {
            hint = "Názov stanice"
            setText(existing?.name ?: "")
        }
        val urlIn = EditText(this).apply {
            hint = "URL streamu (http://…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url ?: "http://")
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        box.addView(nameIn)
        box.addView(urlIn)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Pridať stanicu" else "Upraviť stanicu")
            .setMessage("Receiver od Pioneer spoľahlivo prehrá HTTP streamy (MP3 / AAC). HTTPS streamy pravdepodobne nie.")
            .setView(box)
            .setPositiveButton("Uložiť") { _, _ ->
                val name = nameIn.text.toString().trim()
                val url = urlIn.text.toString().trim()
                if (name.isEmpty() || !(url.startsWith("http://", true) || url.startsWith("https://", true))) {
                    if (::radioServerStatus.isInitialized) {
                        radioServerStatus.text = "⚠️ Zadaj názov a URL začínajúce na http://"
                    }
                } else {
                    try { usbDb.saveStation(existing?.id, name, url) } catch (_: Exception) {}
                    refreshRadioStations()
                    renderRadioStations()
                    backupDbNow(showStatus = false)
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun toggleRadioServer() {
        if (radioServer != null) {
            val old = radioServer
            radioServer = null
            scope.launch(Dispatchers.IO) { old?.stop() }
            updateRadioServerUi()
            return
        }
        // Ak ešte nie je vybraný adresár s médiami, ponúkneme jeho výber.
        // Ak už vybraný je, zapamätaný adresár sa použije automaticky bez pýtania.
        if (mediaDirUri == null) {
            AlertDialog.Builder(this)
                .setTitle("Adresár s médiami pre DLNA server")
                .setMessage("Chceš vybrať adresár s médiami (hudbou), ktorý sa sprístupní cez DLNA server? Aplikácia si ho zapamätá aj po vypnutí servera a nabudúce sa už pýtať nebude.")
                .setPositiveButton("Vybrať adresár") { _, _ ->
                    pendingStartRadioServerAfterDirPick = true
                    startPickMediaDir()
                }
                .setNeutralButton("Len rádiá") { _, _ ->
                    startRadioServerNow()
                }
                .setNegativeButton("Zrušiť", null)
                .show()
            return
        }
        startRadioServerNow()
    }

    private fun startRadioServerNow() {
        if (radioServer != null) return
        val ip = wifiIp()
        if (ip == null) {
            radioServerStatus.text = "⚠️ Telefón nie je pripojený do Wi-Fi siete."
            return
        }
        radioServerStatus.text = "⏳ Spúšťam server…"
        scope.launch(Dispatchers.IO) {
            try {
                var uuid = prefs().getString("radio_server_uuid", null)
                if (uuid == null) {
                    uuid = UUID.randomUUID().toString()
                    prefs().edit().putString("radio_server_uuid", uuid).apply()
                }
                val server = RadioDlnaServer(ip, uuid)
                server.start()
                radioServer = server
                ui { updateRadioServerUi() }
            } catch (e: Exception) {
                ui { radioServerStatus.text = "⚠️ Server sa nepodarilo spustiť: ${e.message ?: "neznáma chyba"}" }
            }
        }
    }

    // DLNA MediaServer: SSDP + popis zariadenia + ContentDirectory + ConnectionManager.
    // Ponúka vlastné rádiostanice (m3u + priamy stream) a zvolený lokálny adresár s médiami.
    private inner class RadioDlnaServer(val ip: String, uuid: String) {
        private val usn = "uuid:$uuid"
        private val pool = Executors.newCachedThreadPool()
        private var httpSock: ServerSocket? = null
        private var recvSock: MulticastSocket? = null
        private var sendSock: MulticastSocket? = null
        @Volatile private var running = false
        var port = 0
            private set
        private val groupAddr = InetAddress.getByName("239.255.255.250")
        private val serverHeader = "Linux/3.0 UPnP/1.0 PioneerRemote/1.0"
        private val xmlType = "text/xml; charset=\"utf-8\""
        private val targets = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            usn
        )

        private fun usnFor(nt: String) = if (nt == usn) usn else "$usn::$nt"
        private fun location() = "http://$ip:$port/desc.xml"

        private fun b64Enc(s: String): String =
            Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        private fun b64Dec(s: String): String? = runCatching {
            String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull()

        private fun dlnaProtocolInfo(mime: String): String = when (mime) {
            "audio/mpeg" -> "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01500000000000000000000000000000"
            "audio/aac" -> "http-get:*:audio/aac:DLNA.ORG_PN=AAC_ISO;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01500000000000000000000000000000"
            else -> "http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01500000000000000000000000000000"
        }

        @Suppress("DEPRECATION")
        fun start() {
            val ss = ServerSocket(0)
            httpSock = ss
            port = ss.localPort
            running = true
            pool.execute { acceptLoop(ss) }
            try {
                val rs = MulticastSocket(null)
                rs.reuseAddress = true
                rs.bind(InetSocketAddress(1900))
                rs.setInterface(InetAddress.getByName(ip))
                rs.joinGroup(groupAddr)
                recvSock = rs
                pool.execute { ssdpListen(rs) }
            } catch (_: Exception) {
                recvSock = null
            }
            try {
                val ms = MulticastSocket()
                ms.setInterface(InetAddress.getByName(ip))
                ms.timeToLive = 4
                sendSock = ms
            } catch (_: Exception) {
                sendSock = null
            }
            pool.execute { notifyLoop() }
        }

        fun stop() {
            running = false
            sendNotify("ssdp:byebye")
            runCatching { httpSock?.close() }
            runCatching { recvSock?.close() }
            runCatching { sendSock?.close() }
            pool.shutdownNow()
        }

        // ---------- SSDP ----------

        private fun sendNotify(nts: String) {
            val s = sendSock ?: return
            targets.forEach { nt ->
                val msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n" +
                    (if (nts == "ssdp:alive") "LOCATION: ${location()}\r\n" else "") +
                    "NT: $nt\r\nNTS: $nts\r\nSERVER: $serverHeader\r\nUSN: ${usnFor(nt)}\r\n\r\n"
                val b = msg.toByteArray(Charsets.US_ASCII)
                try { s.send(DatagramPacket(b, b.size, groupAddr, 1900)) } catch (_: Exception) {}
            }
        }

        private fun notifyLoop() {
            try {
                repeat(3) {
                    if (running) {
                        sendNotify("ssdp:alive")
                        Thread.sleep(300)
                    }
                }
                while (running) {
                    Thread.sleep(30000)
                    if (running) sendNotify("ssdp:alive")
                }
            } catch (_: InterruptedException) {}
        }

        private fun ssdpListen(rs: MulticastSocket) {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    rs.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.US_ASCII)
                    if (!text.startsWith("M-SEARCH")) continue
                    val st = Regex("(?im)^ST:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1) ?: continue
                    val answers = if (st.equals("ssdp:all", true)) targets else targets.filter { it.equals(st, true) }
                    for (nt in answers) {
                        val msg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
                            "LOCATION: ${location()}\r\nSERVER: $serverHeader\r\nST: $nt\r\nUSN: ${usnFor(nt)}\r\n\r\n"
                        val b = msg.toByteArray(Charsets.US_ASCII)
                        (sendSock ?: rs).send(DatagramPacket(b, b.size, pkt.address, pkt.port))
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }

        // ---------- HTTP ----------

        private fun acceptLoop(ss: ServerSocket) {
            while (running) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }

        private fun readHttpLine(inp: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == 10) break
                if (b != 13) sb.append(b.toChar())
            }
            return sb.toString()
        }

        private fun handle(sock: Socket) {
            sock.use { s ->
                try {
                    s.soTimeout = 8000
                    val inp = BufferedInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    val reqLine = readHttpLine(inp) ?: return
                    val parts = reqLine.split(" ")
                    if (parts.size < 2) return
                    val method = parts[0].uppercase()
                    val path = parts[1].substringBefore('?')
                    val headers = HashMap<String, String>()
                    while (true) {
                        val l = readHttpLine(inp) ?: break
                        if (l.isEmpty()) break
                        val i = l.indexOf(':')
                        if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                    }
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    var body = ""
                    if (len > 0) {
                        val bytes = ByteArray(len)
                        var off = 0
                        while (off < len) {
                            val r = inp.read(bytes, off, len - off)
                            if (r < 0) break
                            off += r
                        }
                        body = String(bytes, 0, off, Charsets.UTF_8)
                    }
                    route(s, method, path, headers, body, out)
                } catch (_: Exception) {}
            }
        }

        private fun reply(out: OutputStream, method: String, code: Int, type: String, body: String,
                          extra: Map<String, String> = emptyMap()) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val status = when (code) {
                200 -> "OK"
                404 -> "Not Found"
                416 -> "Range Not Satisfiable"
                500 -> "Internal Server Error"
                else -> "OK"
            }
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $code $status\r\n")
            sb.append("CONTENT-TYPE: $type\r\n")
            sb.append("CONTENT-LENGTH: ${bytes.size}\r\n")
            sb.append("SERVER: $serverHeader\r\n")
            sb.append("EXT:\r\n")
            extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (method != "HEAD") out.write(bytes)
            out.flush()
        }

        private fun skipFully(inp: InputStream, bytesToSkip: Long) {
            var rem = bytesToSkip
            val buf = ByteArray(8192)
            while (rem > 0) {
                val skipped = inp.skip(rem)
                if (skipped > 0) {
                    rem -= skipped
                } else {
                    val r = inp.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                    if (r < 0) break
                    rem -= r
                }
            }
        }

        private fun streamMediaFile(sock: Socket, method: String, path: String, headers: Map<String, String>, out: OutputStream) {
            val treeUri = mediaDirUri
            if (treeUri == null) {
                reply(out, method, 404, "text/plain", "No media directory")
                return
            }
            val token = path.removePrefix("/media/").substringBeforeLast('.')
            val docId = b64Dec(token)
            if (docId == null) {
                reply(out, method, 404, "text/plain", "Invalid id")
                return
            }
            val meta = queryMediaDoc(treeUri, docId)
            if (meta == null || meta.isDir) {
                reply(out, method, 404, "text/plain", "File not found")
                return
            }
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val fileSize = if (meta.size > 0) meta.size else runCatching {
                contentResolver.openAssetFileDescriptor(docUri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)

            var start = 0L
            var end = if (fileSize > 0) fileSize - 1 else -1L
            var partial = false

            val rangeHdr = headers["range"]
            if (fileSize > 0 && rangeHdr != null && rangeHdr.startsWith("bytes=", ignoreCase = true)) {
                val spec = rangeHdr.substring(6).substringBefore(',').trim()
                val dash = spec.indexOf('-')
                if (dash >= 0) {
                    val sStr = spec.substring(0, dash).trim()
                    val eStr = spec.substring(dash + 1).trim()
                    val parsedStart = sStr.toLongOrNull()
                    val parsedEnd = eStr.toLongOrNull()
                    if (parsedStart != null) {
                        start = parsedStart
                        end = if (parsedEnd != null) parsedEnd.coerceAtMost(fileSize - 1) else fileSize - 1
                        if (start >= fileSize || start > end) {
                            reply(out, method, 416, "text/plain", "",
                                mapOf("Content-Range" to "bytes */$fileSize"))
                            return
                        }
                        partial = true
                    }
                }
            }

            val sb = StringBuilder()
            if (partial) {
                val contentLen = end - start + 1
                sb.append("HTTP/1.1 206 Partial Content\r\n")
                sb.append("Content-Range: bytes $start-$end/$fileSize\r\n")
                sb.append("Content-Length: $contentLen\r\n")
                sb.append("Accept-Ranges: bytes\r\n")
            } else {
                sb.append("HTTP/1.1 200 OK\r\n")
                if (fileSize > 0) {
                    sb.append("Content-Length: $fileSize\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                }
            }
            sb.append("Content-Type: ${meta.mime}\r\n")
            sb.append("transferMode.dlna.org: Streaming\r\n")
            sb.append("SERVER: $serverHeader\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.flush()

            if (method == "HEAD") return

            sock.soTimeout = 0
            contentResolver.openInputStream(docUri)?.use { inp ->
                if (partial && start > 0) skipFully(inp, start)
                val buf = ByteArray(16384)
                if (partial) {
                    var rem = end - start + 1
                    while (running && rem > 0) {
                        val r = inp.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                        if (r < 0) break
                        out.write(buf, 0, r)
                        rem -= r
                    }
                } else {
                    while (running) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                }
                out.flush()
            }
        }

        private fun route(sock: Socket, method: String, path: String, headers: Map<String, String>, body: String, out: OutputStream) {
            when {
                path == "/desc.xml" -> reply(out, method, 200, xmlType, descXml())
                path == "/cds.xml" -> reply(out, method, 200, xmlType, cdsScpd())
                path == "/cm.xml" -> reply(out, method, 200, xmlType, cmScpd())
                path == "/cds/control" && method == "POST" -> soapCds(out, headers, body)
                path == "/cm/control" && method == "POST" -> soapCm(out, headers)
                path == "/cds/event" || path == "/cm/event" -> {
                    if (method == "SUBSCRIBE") {
                        reply(out, method, 200, "text/plain", "",
                            mapOf("SID" to "uuid:${UUID.randomUUID()}", "TIMEOUT" to "Second-1800"))
                    } else {
                        reply(out, method, 200, "text/plain", "")
                    }
                }
                path.startsWith("/radio/") && path.endsWith(".m3u") -> {
                    val id = path.removePrefix("/radio/").removeSuffix(".m3u").toLongOrNull()
                    val st = radioStations.firstOrNull { it.id == id }
                    if (st == null) {
                        reply(out, method, 404, "text/plain", "Not found")
                    } else {
                        reply(out, method, 200, "audio/x-mpegurl", "#EXTM3U\r\n#EXTINF:-1,${st.name}\r\n${st.url}\r\n")
                    }
                }
                path.startsWith("/media/") -> streamMediaFile(sock, method, path, headers, out)
                else -> reply(out, method, 404, "text/plain", "Not found")
            }
        }

        // ---------- popis zariadenia a služieb ----------

        private fun descXml(): String =
            "<?xml version=\"1.0\"?>" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>" +
            "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>" +
            "<friendlyName>Pioneer Remote Radio</friendlyName>" +
            "<manufacturer>Pioneer Remote</manufacturer><modelName>Radio &amp; Media Server</modelName>" +
            "<UDN>$usn</UDN><serviceList>" +
            "<service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId><SCPDURL>/cds.xml</SCPDURL>" +
            "<controlURL>/cds/control</controlURL><eventSubURL>/cds/event</eventSubURL></service>" +
            "<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/cm.xml</SCPDURL>" +
            "<controlURL>/cm/control</controlURL><eventSubURL>/cm/event</eventSubURL></service>" +
            "</serviceList></device></root>"

        private fun arg(n: String, d: String, r: String) =
            "<argument><name>$n</name><direction>$d</direction><relatedStateVariable>$r</relatedStateVariable></argument>"

        private fun act(n: String, args: String) =
            "<action><name>$n</name><argumentList>$args</argumentList></action>"

        private fun sv(n: String, t: String, ev: Boolean = false, allowed: List<String> = emptyList()) =
            "<stateVariable sendEvents=\"" + (if (ev) "yes" else "no") + "\"><name>$n</name><dataType>$t</dataType>" +
                (if (allowed.isEmpty()) "" else "<allowedValueList>" +
                    allowed.joinToString("") { "<allowedValue>$it</allowedValue>" } + "</allowedValueList>") +
                "</stateVariable>"

        private fun scpd(actions: String, vars: String) =
            "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion>" +
                "<actionList>$actions</actionList><serviceStateTable>$vars</serviceStateTable></scpd>"

        private fun cdsScpd(): String = scpd(
            act("GetSearchCapabilities", arg("SearchCaps", "out", "SearchCapabilities")) +
                act("GetSortCapabilities", arg("SortCaps", "out", "SortCapabilities")) +
                act("GetSystemUpdateID", arg("Id", "out", "SystemUpdateID")) +
                act("Browse",
                    arg("ObjectID", "in", "A_ARG_TYPE_ObjectID") +
                        arg("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag") +
                        arg("Filter", "in", "A_ARG_TYPE_Filter") +
                        arg("StartingIndex", "in", "A_ARG_TYPE_Index") +
                        arg("RequestedCount", "in", "A_ARG_TYPE_Count") +
                        arg("SortCriteria", "in", "A_ARG_TYPE_SortCriteria") +
                        arg("Result", "out", "A_ARG_TYPE_Result") +
                        arg("NumberReturned", "out", "A_ARG_TYPE_Count") +
                        arg("TotalMatches", "out", "A_ARG_TYPE_Count") +
                        arg("UpdateID", "out", "A_ARG_TYPE_UpdateID")),
            sv("A_ARG_TYPE_ObjectID", "string") +
                sv("A_ARG_TYPE_Result", "string") +
                sv("A_ARG_TYPE_BrowseFlag", "string", false, listOf("BrowseMetadata", "BrowseDirectChildren")) +
                sv("A_ARG_TYPE_Filter", "string") +
                sv("A_ARG_TYPE_SortCriteria", "string") +
                sv("A_ARG_TYPE_Index", "ui4") +
                sv("A_ARG_TYPE_Count", "ui4") +
                sv("A_ARG_TYPE_UpdateID", "ui4") +
                sv("SearchCapabilities", "string") +
                sv("SortCapabilities", "string") +
                sv("SystemUpdateID", "ui4", true))

        private fun cmScpd(): String = scpd(
            act("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo") + arg("Sink", "out", "SinkProtocolInfo")) +
                act("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs")) +
                act("GetCurrentConnectionInfo",
                    arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID") +
                        arg("RcsID", "out", "A_ARG_TYPE_RcsID") +
                        arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID") +
                        arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo") +
                        arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager") +
                        arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID") +
                        arg("Direction", "out", "A_ARG_TYPE_Direction") +
                        arg("Status", "out", "A_ARG_TYPE_ConnectionStatus")),
            sv("SourceProtocolInfo", "string", true) +
                sv("SinkProtocolInfo", "string", true) +
                sv("CurrentConnectionIDs", "string", true) +
                sv("A_ARG_TYPE_ConnectionStatus", "string", false, listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")) +
                sv("A_ARG_TYPE_ConnectionManager", "string") +
                sv("A_ARG_TYPE_Direction", "string", false, listOf("Input", "Output")) +
                sv("A_ARG_TYPE_ProtocolInfo", "string") +
                sv("A_ARG_TYPE_ConnectionID", "i4") +
                sv("A_ARG_TYPE_AVTransportID", "i4") +
                sv("A_ARG_TYPE_RcsID", "i4"))

        // ---------- SOAP ----------

        private fun xmlEsc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        private fun tag(body: String, name: String): String =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim() ?: ""

        private fun soapActionOf(headers: Map<String, String>): String =
            headers["soapaction"]?.trim()?.trim('"')?.substringAfter('#') ?: ""

        private fun envelope(service: String, action: String, inner: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">$inner" +
                "</u:${action}Response></s:Body></s:Envelope>"

        private fun fault(out: OutputStream, code: Int, msg: String) {
            val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>" +
                "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
                "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
                "<errorDescription>$msg</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
            reply(out, "POST", 500, xmlType, xml)
        }

        private fun didl(inner: String): String =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">$inner</DIDL-Lite>"

        private fun containerXml(id: String, parentId: String, title: String, childCount: Int? = null): String {
            val cntAttr = if (childCount != null) " childCount=\"$childCount\"" else ""
            return "<container id=\"$id\" parentID=\"$parentId\" restricted=\"1\"$cntAttr>" +
                "<dc:title>${xmlEsc(title)}</dc:title>" +
                "<upnp:class>object.container.storageFolder</upnp:class></container>"
        }

        private fun itemXml(id: String, parentId: String, title: String, cls: String, proto: String, url: String, size: Long = -1L): String {
            val sizeAttr = if (size > 0) " size=\"$size\"" else ""
            return "<item id=\"$id\" parentID=\"$parentId\" restricted=\"1\"><dc:title>${xmlEsc(title)}</dc:title>" +
                "<upnp:class>$cls</upnp:class><res protocolInfo=\"$proto\"$sizeAttr>${xmlEsc(url)}</res></item>"
        }

        private fun radioItems(parentId: String): List<Pair<String, String>> {
            val list = mutableListOf<Pair<String, String>>()
            radioStations.forEach { st ->
                val mime = if (st.url.lowercase().contains("aac")) "audio/aac" else "audio/mpeg"
                list.add("m${st.id}" to itemXml("m${st.id}", parentId, st.name, "object.item.audioItem",
                    "http-get:*:audio/x-mpegurl:*", "http://$ip:$port/radio/${st.id}.m3u"))
                list.add("d${st.id}" to itemXml("d${st.id}", parentId, "${st.name} (priamy stream)",
                    "object.item.audioItem.audioBroadcast", "http-get:*:$mime:*", st.url))
            }
            return list
        }

        private fun mediaEntryXml(e: LocalDocEntry, parentId: String): Pair<String, String> {
            val token = b64Enc(e.docId)
            return if (e.isDir) {
                val oid = "dir_$token"
                oid to containerXml(oid, parentId, e.name)
            } else {
                val oid = "file_$token"
                val ext = e.name.substringAfterLast('.', "mp3").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "mp3" }
                val url = "http://$ip:$port/media/$token.$ext"
                oid to itemXml(oid, parentId, e.name, "object.item.audioItem.musicTrack", dlnaProtocolInfo(e.mime), url, e.size)
            }
        }

        private fun mediaChildren(treeUri: Uri, parentDocId: String, parentObjId: String): List<Pair<String, String>> =
            listMediaDirChildren(treeUri, parentDocId).map { mediaEntryXml(it, parentObjId) }

        private fun soapCds(out: OutputStream, headers: Map<String, String>, body: String) {
            val action = soapActionOf(headers)
            val upd = dlnaUpdateId
            when (action) {
                "Browse" -> {
                    val objectId = tag(body, "ObjectID")
                    val flag = tag(body, "BrowseFlag")
                    val start = tag(body, "StartingIndex").toIntOrNull() ?: 0
                    val req = tag(body, "RequestedCount").toIntOrNull() ?: 0
                    val treeUri = mediaDirUri

                    if (flag == "BrowseMetadata") {
                        val frag: String? = when {
                            objectId == "0" -> {
                                val cnt = if (treeUri != null) 2 else radioStations.size * 2
                                containerXml("0", "-1", "Pioneer Remote", cnt)
                            }
                            objectId == "radios" -> containerXml("radios", "0", "Rádiá", radioStations.size * 2)
                            objectId == "media_root" && treeUri != null ->
                                containerXml("media_root", "0", "Médiá: $mediaDirName")
                            objectId.startsWith("dir_") && treeUri != null -> {
                                val docId = b64Dec(objectId.removePrefix("dir_"))
                                val meta = if (docId != null) queryMediaDoc(treeUri, docId) else null
                                if (meta != null && meta.isDir) containerXml(objectId, "media_root", meta.name) else null
                            }
                            objectId.startsWith("file_") && treeUri != null -> {
                                val docId = b64Dec(objectId.removePrefix("file_"))
                                val meta = if (docId != null) queryMediaDoc(treeUri, docId) else null
                                if (meta != null && !meta.isDir) mediaEntryXml(meta, "media_root").second else null
                            }
                            else -> radioItems(if (treeUri != null) "radios" else "0")
                                .firstOrNull { it.first == objectId }?.second
                        }
                        if (frag == null) {
                            fault(out, 701, "No such object")
                        } else {
                            reply(out, "POST", 200, xmlType, envelope("ContentDirectory", action,
                                "<Result>${xmlEsc(didl(frag))}</Result><NumberReturned>1</NumberReturned>" +
                                    "<TotalMatches>1</TotalMatches><UpdateID>$upd</UpdateID>"))
                        }
                    } else {
                        val children: List<Pair<String, String>>? = when {
                            objectId == "0" -> {
                                if (treeUri != null) {
                                    listOf(
                                        "radios" to containerXml("radios", "0", "Rádiá", radioStations.size * 2),
                                        "media_root" to containerXml("media_root", "0", "Médiá: $mediaDirName")
                                    )
                                } else {
                                    radioItems("0")
                                }
                            }
                            objectId == "radios" -> radioItems("radios")
                            objectId == "media_root" && treeUri != null -> {
                                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                                mediaChildren(treeUri, rootDocId, "media_root")
                            }
                            objectId.startsWith("dir_") && treeUri != null -> {
                                val docId = b64Dec(objectId.removePrefix("dir_"))
                                if (docId != null) mediaChildren(treeUri, docId, objectId) else null
                            }
                            else -> null
                        }
                        if (children == null) {
                            fault(out, 701, "No such object")
                        } else {
                            val total = children.size
                            val from = start.coerceIn(0, total)
                            val to = if (req <= 0) total else (from + req).coerceAtMost(total)
                            val slice = children.subList(from, to)
                            reply(out, "POST", 200, xmlType, envelope("ContentDirectory", action,
                                "<Result>${xmlEsc(didl(slice.joinToString("") { it.second }))}</Result>" +
                                    "<NumberReturned>${slice.size}</NumberReturned><TotalMatches>$total</TotalMatches>" +
                                    "<UpdateID>$upd</UpdateID>"))
                        }
                    }
                }
                "GetSearchCapabilities" -> reply(out, "POST", 200, xmlType,
                    envelope("ContentDirectory", action, "<SearchCaps></SearchCaps>"))
                "GetSortCapabilities" -> reply(out, "POST", 200, xmlType,
                    envelope("ContentDirectory", action, "<SortCaps></SortCaps>"))
                "GetSystemUpdateID" -> reply(out, "POST", 200, xmlType,
                    envelope("ContentDirectory", action, "<Id>$upd</Id>"))
                else -> fault(out, 401, "Invalid Action")
            }
        }

        private fun soapCm(out: OutputStream, headers: Map<String, String>) {
            val action = soapActionOf(headers)
            val inner = when (action) {
                "GetProtocolInfo" -> "<Source>http-get:*:audio/x-mpegurl:*,http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/wav:*,http-get:*:audio/mp4:*,http-get:*:audio/aac:*,http-get:*:audio/x-ms-wma:*,http-get:*:audio/ogg:*</Source><Sink></Sink>"
                "GetCurrentConnectionIDs" -> "<ConnectionIDs>0</ConnectionIDs>"
                "GetCurrentConnectionInfo" -> "<RcsID>-1</RcsID><AVTransportID>-1</AVTransportID>" +
                    "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>" +
                    "<PeerConnectionID>-1</PeerConnectionID><Direction>Output</Direction><Status>OK</Status>"
                else -> null
            }
            if (inner == null) {
                fault(out, 401, "Invalid Action")
            } else {
                reply(out, "POST", 200, xmlType, envelope("ConnectionManager", action, inner))
            }
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

        val topPanel = LinearLayout(this)
        topPanel.orientation = LinearLayout.VERTICAL

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

        topPanel.addView(header)

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
        topPanel.addView(top)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(6), dp(10), dp(6))
        card.background = roundedBg(C_CARD, 14, C_CYAN_D)

        mainStationBig = TextView(this)
        mainStationBig.text = "📻  —"
        mainStationBig.textSize = 18f
        mainStationBig.typeface = Typeface.DEFAULT_BOLD
        mainStationBig.setTextColor(C_WHITE)
        mainStationBig.gravity = Gravity.CENTER
        mainStationBig.maxLines = 2
        mainStationBig.ellipsize = TextUtils.TruncateAt.END

        mainShowBitrate = TextView(this)
        mainShowBitrate.text = "Relácia / Skladba: —   ·   Bitrate: —"
        mainShowBitrate.textSize = 11f
        mainShowBitrate.setTextColor(C_CYAN)
        mainShowBitrate.gravity = Gravity.CENTER
        mainShowBitrate.maxLines = 2
        mainShowBitrate.ellipsize = TextUtils.TruncateAt.END
        mainShowBitrate.setPadding(0, dp(1), 0, dp(1))

        infoLine = TextView(this)
        infoLine.text = "Vstup: —   ·   Režim: —"
        infoLine.textSize = 11f
        infoLine.setTextColor(C_SLATE)
        infoLine.gravity = Gravity.CENTER
        infoLine.maxLines = 1
        infoLine.ellipsize = TextUtils.TruncateAt.END

        val connRow = LinearLayout(this)
        connRow.orientation = LinearLayout.HORIZONTAL
        connRow.gravity = Gravity.CENTER_VERTICAL
        connRow.setPadding(0, dp(1), 0, 0)

        connLine = TextView(this)
        connLine.text = "○  Odpojené ($host)"
        connLine.textSize = 10f
        connLine.setTextColor(C_RED)
        connLine.gravity = Gravity.START
        connLine.maxLines = 2
        connLine.ellipsize = TextUtils.TruncateAt.END

        statusText = TextView(this)
        statusText.text = ""
        statusText.textSize = 10f
        statusText.setTextColor(C_ORANGE)
        statusText.gravity = Gravity.END
        statusText.maxLines = 2
        statusText.ellipsize = TextUtils.TruncateAt.END

        connRow.addView(connLine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connRow.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val volBox = LinearLayout(this)
        volBox.orientation = LinearLayout.HORIZONTAL
        volBox.gravity = Gravity.CENTER_VERTICAL
        volBox.setPadding(dp(2), dp(3), dp(2), 0)

        volumeLabel = TextView(this)
        volumeLabel.text = "🔊 —"
        volumeLabel.textSize = 12f
        volumeLabel.typeface = Typeface.DEFAULT_BOLD
        volumeLabel.setTextColor(C_GREEN)
        volumeLabel.setPadding(0, 0, dp(8), 0)

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
        card.addView(connRow)
        card.addView(volBox)

        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.topMargin = dp(4)
        topPanel.addView(card, cardLp)

        val tabDefs = listOf("🎚 OVLÁDANIE", "📻 RÁDIO", "💽 MÉDIÁ")
        val tabBtns = tabDefs.map { t -> pillBtn(t, C_BTN, C_SLATE) { } }
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabBtns.forEach { b ->
            b.textSize = 10f
            b.setPadding(dp(4), 0, dp(4), 0)
            val lp = LinearLayout.LayoutParams(0, dp(24), 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            tabs.addView(b, lp)
        }
        val tabsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tabsLp.topMargin = dp(4)
        topPanel.addView(tabs, tabsLp)

        muteBtn = flatBtn("🔇 MUTE") { toggleMute() }
        val volMinus = flatBtn("VOL −") { send("VD"); sendSilent("?V") }
        val volPlus = flatBtn("VOL +") { send("VU"); sendSilent("?V") }
        listOf(muteBtn, volMinus, volPlus).forEach {
            it.textSize = 14f
            it.setPadding(dp(8), dp(12), dp(8), dp(12))
        }

        val globalVolLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        globalVolLp.topMargin = dp(4)
        globalVolLp.bottomMargin = dp(2)
        topPanel.addView(rowOf(listOf(volMinus, muteBtn, volPlus)), globalVolLp)

        val pager = ViewPager2(this)
        pager.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val screens = listOf<View>(buildScreenHome(), buildScreenRadio(), buildScreenMedia())
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

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(topPanel, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rootLayout = root
        topScroll = scroll
        mainPager = pager
        root.addView(scroll)
        root.addView(pager)
        applyOrientationLayout()
        setContentView(root)
    }

    private fun applyOrientationLayout() {
        if (!::rootLayout.isInitialized) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            rootLayout.orientation = LinearLayout.HORIZONTAL
            rootLayout.setPadding(dp(12), dp(4), dp(12), 0)
            val leftLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f)
            leftLp.rightMargin = dp(10)
            topScroll.layoutParams = leftLp
            mainPager.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f)
        } else {
            rootLayout.orientation = LinearLayout.VERTICAL
            rootLayout.setPadding(dp(12), dp(8), dp(12), 0)
            topScroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            mainPager.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
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
        autoReconnectAllowed = true
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
                if (code != "17FN") abandonUsbPlayback()
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

        buildRadioServerSection(page)

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
        abandonUsbPlayback()
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
        abandonUsbPlayback()
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

    private fun buildScreenMedia(): LinearLayout {
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
        // KONTAJNER 1: 🔌 USB KĽÚČ
        // =================================================================
        usbContainer = LinearLayout(this)
        usbContainer.orientation = LinearLayout.VERTICAL

        val playlistMenuBtn = flatBtn("📂 Playlist ▾") { }
        val dbMenuBtn = flatBtn("🗄 Databáza ▾") { }
        playlistMenuBtn.setOnClickListener { showPlaylistMenu(it) }
        dbMenuBtn.setOnClickListener { showDatabaseMenu(it) }
        playlistMenuBtn.textSize = 13f
        dbMenuBtn.textSize = 13f
        playlistMenuBtn.background = roundedBg(C_CARD2, 12, C_CYAN)
        dbMenuBtn.background = roundedBg(C_CARD2, 12, C_ORANGE)
        usbContainer.addView(rowOf(listOf(playlistMenuBtn, dbMenuBtn)))

        usbPlayBtn = flatBtn("▶ PLAY") { onMediaPlayPressed() }
        usbPlayBtn.background = roundedBg(C_GREEN, 12, C_GREEN)
        usbPlayBtn.setTextColor(C_ON_ACCENT)
        usbPlayBtn.typeface = Typeface.DEFAULT_BOLD
        usbPlayBtn.textSize = 14f
        usbPlayBtn.setPadding(dp(8), dp(12), dp(8), dp(12))

        usbStopBtn = flatBtn("⏹ STOP") { onMediaStopPressed() }
        usbStopBtn.background = roundedBg(C_RED, 12, C_RED)
        usbStopBtn.setTextColor(C_WHITE)
        usbStopBtn.typeface = Typeface.DEFAULT_BOLD
        usbStopBtn.textSize = 14f
        usbStopBtn.setPadding(dp(8), dp(12), dp(8), dp(12))

        usbScanAllBtn = flatBtn("🔄 Načítať USB") {
            onUsbScanButton()
        }
        usbScanAllBtn.textSize = 11f
        usbScanAllBtn.setTextColor(C_SLATE)
        usbScanAllBtn.background = roundedBg(C_CARD2, 10, C_STROKE)
        usbScanAllBtn.setPadding(dp(4), dp(7), dp(4), dp(7))

        val clearSelectionBtn = flatBtn("✖ Zrušiť označenie") {
            clearUsbSelection()
        }
        clearSelectionBtn.textSize = 11f
        clearSelectionBtn.setTextColor(C_SLATE)
        clearSelectionBtn.background = roundedBg(C_CARD2, 10, C_STROKE)
        clearSelectionBtn.setPadding(dp(4), dp(7), dp(4), dp(7))

        usbContainer.addView(rowOf(listOf(usbScanAllBtn, clearSelectionBtn)))

        usbPathText = TextView(this)
        usbPathText.text = "📂 [$activePlaylistName] Adresár: / (Koreň USB)"
        usbPathText.textSize = 12f
        usbPathText.typeface = Typeface.DEFAULT_BOLD
        usbPathText.setTextColor(C_CYAN)
        usbPathText.setPadding(dp(4), dp(8), dp(4), dp(2))
        usbContainer.addView(usbPathText)

        usbSelectedText = TextView(this)
        usbSelectedText.text = "✓ Označené: (nič) — ☐ označí adresáre (viacnásobne) alebo 1 súbor"
        usbSelectedText.textSize = 12f
        usbSelectedText.setTextColor(C_ORANGE)
        usbSelectedText.setPadding(dp(4), 0, dp(4), dp(2))
        usbContainer.addView(usbSelectedText)

        usbStatusText = TextView(this)
        usbStatusText.text = if (usbTreeCache.isNotEmpty())
            "💾 Playlist '$activePlaylistName': ${usbTreeCache.size} adresárov (${usbTreeCache.values.sumOf { it.size }} položiek) v pamäti."
        else
            "💾 Žiadny playlist. Klikni na 📂 Otvor playlist alebo vlož kľúč a daj 🔄 Načítať USB."
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
        dlnaContainer.addView(rowOf(listOf(srvBtn, backBtn)))

        mediaServerText = TextView(this)
        mediaServerText.text = if (mediaServerName.isNotEmpty())
            "Server: $mediaServerName" else "Server: nevybraný — klikni 🔍 SERVERY"
        mediaServerText.textSize = 12f
        mediaServerText.setTextColor(C_SLATE)
        mediaServerText.setPadding(0, dp(8), 0, 0)
        dlnaContainer.addView(mediaServerText)

        mediaPathText = TextView(this)
        mediaPathText.text = "📚 /"
        mediaPathText.setTextColor(C_SLATE)
        mediaPathText.textSize = 12f
        dlnaContainer.addView(mediaPathText)

        mediaSelectedText = TextView(this)
        mediaSelectedText.text = "✓ Označené: (nič) — ☐ označí albumy (viac) alebo 1 súbor"
        mediaSelectedText.textSize = 12f
        mediaSelectedText.setTextColor(C_ORANGE)
        mediaSelectedText.setPadding(0, dp(4), 0, 0)
        dlnaContainer.addView(mediaSelectedText)

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

        page.addView(hint("← swipe na rádio →"))

        usbTransportBar = rowOf(listOf(usbPlayBtn, usbStopBtn))
        val barLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        barLp.bottomMargin = dp(4)

        val mediaRoot = LinearLayout(this)
        mediaRoot.orientation = LinearLayout.VERTICAL
        mediaRoot.addView(usbTransportBar, barLp)
        mediaRoot.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        switchMediaMode(usbMode = isUsbMediaMode)
        return mediaRoot
    }

    private fun onMediaPlayPressed() {
        if (isUsbMediaMode) onUsbPlayPressed() else onDlnaPlayPressed()
    }

    private fun onMediaStopPressed() {
        if (isUsbMediaMode) stopUsbPlayback() else stopDlnaPlayback()
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
        if (usbAtRoot()) return true

        if (!usbOnList()) {
            usbStopBoth()
            delay(300)
            for (c in listOf("19IP", "18IP", "31NW")) {
                sendSilent(c)
                delay(450)
                usbSnapshot()
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
        return usbAtRoot()
    }

    private suspend fun exitUsbPlayScreenToRootList(): Boolean {
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
                    return true
                }
            }
        }

        sendSilent(String.format("%05dGGP", absIndex))
        delay(220)
        for (enterCmd in listOf("17IP", "30NW")) {
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
                    return true
                }
            }
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
            if (expectedNode != null && !nwScreenName.trim().equals(expectedNode.title.trim(), ignoreCase = true)) {
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
            return result
        }
        val total = rawTotalItems
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
    // USB: SPRÁVA ÚLOH, SKENOVANIE A PREHRÁVANIE Z FRONTY
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

    private fun onUsbScanButton() {
        if (usbScanning) {
            AlertDialog.Builder(this)
                .setTitle("Zrušiť načítavanie USB?")
                .setMessage("Naozaj chcete prerušiť prebiehajúce načítavanie aktuálneho USB kľúča?")
                .setPositiveButton("Áno, zrušiť") { _, _ ->
                    usbJob?.cancel()
                    usbScanning = false
                    updateScanButtonUi()
                    usbStatusText.text = "Načítavanie zrušené."
                }
                .setNegativeButton("Pokračovať", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Načítať aktuálny USB kľúč?")
                .setMessage("Chcete načítať obsah aktuálne vloženého USB kľúča z receivera? Sken môže trvať niekoľko minút (podľa veľkosti kľúča) a po jeho dokončení si ho budete môcť uložiť ako nový playlist.")
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
        cache[parentKey] = items
        counter[0] += items.size
        ui {
            usbStatusText.text = "⏳ Načítavam… adresárov: ${counter[1] + 1}, položiek: ${counter[0]}" +
                "  (teraz: $parentTitle)"
        }
        if (depth >= 8) return

        for (i in items.indices) {
            currentCoroutineContext().ensureActive()
            val node = items[i]
            if (!node.isFolder) continue

            if (!usbEnsureAt(path)) {
                continue
            }
            if (!usbSelectItem(node.indexInParent, node.title)) {
                recvPath = null
                continue
            }
            usbSnapshot()

            if (usbOnList() && !nwScreenName.trim().equals(node.title.trim(), ignoreCase = true)) {
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
        usbScanning = true
        usbState = UsbState.STOPPED
        updateScanButtonUi()
        usbStatusText.text = "⏳ Zastavujem prehrávanie a načítavam USB kľúč…"

        launchUsb {
            try {
                val atRoot = exitUsbPlayScreenToRootList()
                if (!atRoot) {
                    ui { usbStatusText.text = "⚠️ Nepodarilo sa dostať do koreňa USB. Skús znova." }
                    return@launchUsb
                }
                val newCache = linkedMapOf<String, MutableList<UsbNode>>()
                val counter = IntArray(2)
                scanUsbFolderRecursive(newCache, emptyList(), "/", "USB", 0, counter)
                ui {
                    usbTreeCache.clear()
                    usbTreeCache.putAll(newCache)
                    usbCurrentPath.clear()
                    usbSelectedPathKeys.clear()
                    activePlaylistId = null
                    activePlaylistName = "Neuložený kľúč"
                    prefs().edit().remove("active_playlist_name").apply()
                    renderUsbCachedFolder()
                    updateInfoLine()
                    updateMainDisplayHeader()
                    usbStatusText.text = "✓ Načítané: ${counter[1] + 1} adresárov, ${counter[0]} položiek. Nezabudnite si tento kľúč uložiť (💾 Ulož playlist)."
                    showAddUsbToDbDialog(afterScan = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui { usbStatusText.text = "Chyba pri načítaní USB: ${e.message ?: "neznáma"}" }
            } finally {
                usbScanning = false
                ui {
                    updateScanButtonUi()
                    disconnectIfBackground()
                }
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

    private fun findUsbNodeByKey(pathKey: String): UsbNode? {
        usbTreeCache.values.forEach { list ->
            list.firstOrNull { it.pathKey == pathKey }?.let { return it }
        }
        return null
    }

    private fun clearUsbSelection() {
        usbSelectedPathKeys.clear()
        updateUsbSelectionStyles()
        if (::usbStatusText.isInitialized) {
            usbStatusText.text = "✖ Označenie zrušené."
        }
    }

    private fun updateUsbSelectionStyles() {
        if (::usbSelectedText.isInitialized) {
            val selectedNodes = getSelectedNodesInOrder()
            if (selectedNodes.isNotEmpty()) {
                val first = selectedNodes.first()
                usbSelectedText.text = if (selectedNodes.size == 1) {
                    val typ = if (first.isFolder) "adresár" else "súbor"
                    "✓ Označený $typ: ${first.title} — stlač ▶ PLAY"
                } else {
                    "✓ Označených adresárov: ${selectedNodes.size} — stlač ▶ PLAY"
                }
            } else {
                usbSelectedText.text = "✓ Označené: (nič) — ☐ označí adresáre (viacnásobne) alebo 1 súbor"
            }
        }
        usbTreeCache[currentUsbPathKey()].orEmpty().forEach { node ->
            val isSel = usbSelectedPathKeys.contains(node.pathKey)
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

        usbPathText.text = "📂 [$activePlaylistName] Adresár: ${currentUsbPathDisplay()}"

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
            emptyTv.text = "(Playlist '$activePlaylistName' nemá načítaný tento adresár — vlož kľúč a stlač 🔄 Načítať USB, alebo otvor iný uložený playlist)"
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
            "📂 [$activePlaylistName] '${node.title}' — $n položiek."
    }

    private fun toggleUsbSelection(node: UsbNode) {
        if (node.isFolder) {
            usbSelectedPathKeys.removeAll { key -> findUsbNodeByKey(key)?.isFolder == false }
            if (usbSelectedPathKeys.contains(node.pathKey)) {
                usbSelectedPathKeys.remove(node.pathKey)
            } else {
                usbSelectedPathKeys.add(node.pathKey)
            }
        } else {
            val wasAlreadySelected = usbSelectedPathKeys.size == 1 && usbSelectedPathKeys.contains(node.pathKey)
            usbSelectedPathKeys.clear()
            if (!wasAlreadySelected) {
                usbSelectedPathKeys.add(node.pathKey)
            }
        }
        updateUsbSelectionStyles()
    }

    private fun onUsbNodeClicked(node: UsbNode) {
        if (node.isFolder) {
            enterUsbFolderNode(node)
        } else {
            toggleUsbSelection(node)
        }
    }

    private fun getSelectedNodesInOrder(): List<UsbNode> {
        return usbSelectedPathKeys.toList().mapNotNull { findUsbNodeByKey(it) }
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

    private fun collectPlayUnits(node: UsbNode, out: MutableList<UsbNode>, depth: Int = 0) {
        if (!node.isFolder) {
            out.add(node)
            return
        }
        if (depth > 8) return
        val children = usbTreeCache[node.pathKey].orEmpty()
        if (children.any { !it.isFolder && isPlayableTitle(it.title) }) {
            out.add(node)
        }
        children.filter { it.isFolder }.forEach { collectPlayUnits(it, out, depth + 1) }
    }

    private fun onUsbPlayPressed() {
        if (usbSelectedPathKeys.isEmpty()) {
            usbStatusText.text = "⚠️ Najprv označ aspoň jeden adresár alebo súbor (☐)."
            return
        }

        val units = mutableListOf<UsbNode>()
        getSelectedNodesInOrder().forEach { collectPlayUnits(it, units) }
        val queue = units.distinctBy { it.pathKey }
        if (queue.isEmpty()) {
            usbStatusText.text = "⚠️ V označených adresároch som nenašiel žiadne skladby. Skús 🔄 Načítať USB."
            return
        }

        usbPlayQueue = queue
        usbPlayQueueIndex = 0
        startUsbSelection(queue[0])
    }

    private fun playNextInUsbQueue() {
        usbPlayQueueIndex++
        val idx = usbPlayQueueIndex
        val queue = usbPlayQueue
        if (idx < queue.size) {
            val nextNode = queue[idx]
            ui {
                usbStatusText.text = "⏭ Ďalší v poradí: ${nextNode.title} (${idx + 1}/${queue.size})"
                startUsbSelection(nextNode)
            }
        } else {
            ui {
                usbSelectedPathKeys.clear()
                updateUsbSelectionStyles()
                stopUsbPlayback()
            }
        }
    }

    private fun startUsbSelection(sel: UsbNode) {
        val target = firstPlayableFile(sel)
        if (target == null) {
            usbStatusText.text = "⚠️ V '${sel.title}' som nenašiel žiadny súbor. Skús 🔄 Načítať USB."
            return
        }
        val steps = target.pathKey.trim('/').split('/').mapNotNull { it.toIntOrNull() }
        if (steps.isEmpty()) return

        val qInfo = if (usbPlayQueue.size > 1) " (${usbPlayQueueIndex + 1}/${usbPlayQueue.size})" else ""

        usbStartedKey = sel.pathKey
        usbState = UsbState.PLAYING
        activeStationName = sel.title
        playMetaLines.clear()
        playTimeText = ""
        updateMainDisplayHeader()
        usbStatusText.text = "⏳ [$activePlaylistName] Spúšťam '${sel.title}'$qInfo…"

        launchUsb {
            isPlayingFavorite = false
            val atRoot = exitUsbPlayScreenToRootList()
            if (!atRoot) {
                ui {
                    usbState = UsbState.STOPPED
                    usbStatusText.text = "⚠️ Nepodarilo sa dostať do koreňa USB (skontroluj, či je kľúč vložený v receiveri)."
                    disconnectIfBackground()
                }
                return@launchUsb
            }
            if (!usbEnsureAt(steps.dropLast(1))) {
                ui {
                    usbState = UsbState.STOPPED
                    usbStatusText.text = "⚠️ Adresár na USB nezodpovedá playlistu '$activePlaylistName'! Skontroluj, či je v receiveri vložený správny USB kľúč."
                    disconnectIfBackground()
                }
                return@launchUsb
            }
            usbSelectItem(steps.last(), target.title)
            usbSnapshot()
            if (usbOnList()) {
                sendSilent("17IP")
                delay(800)
                usbSnapshot()
            }
            recvPath = null
            val what = if (sel.isFolder) "adresár ${sel.title} (od: ${target.title})" else sel.title
            ui { usbStatusText.text = "▶ [$activePlaylistName] Hrá: $what$qInfo" }
            delay(1200)
            sendSilent("?GAP")
            sendSilent("?GAH")
            sendSilent("?FL")

            val hasNext = usbPlayQueueIndex + 1 < usbPlayQueue.size
            var wasPlayingScreen = false
            var lastTime = playTimeText
            var timeChanges = 0
            var frozenPolls = 0
            while (isActive && usbState == UsbState.PLAYING && usbStartedKey == sel.pathKey) {
                delay(3000)
                sendSilent("?F")
                sendSilent("?GAP")
                delay(400)

                val inp = activeInput
                if (inp != null && inp != "IPOD/USB" && inp != "17FN") {
                    usbState = UsbState.STOPPED
                    usbStartedKey = null
                    break
                }

                var finished = false
                if (isUsbPlayingNow) {
                    wasPlayingScreen = true
                } else if (wasPlayingScreen) {
                    finished = true
                }

                val t = playTimeText
                if (t != lastTime) {
                    timeChanges++
                    frozenPolls = 0
                    lastTime = t
                } else if (timeChanges >= 2) {
                    frozenPolls++
                }
                if (hasNext && frozenPolls >= 4) finished = true

                if (finished) {
                    playNextInUsbQueue()
                    break
                }
            }
        }
    }

    private fun abandonUsbPlayback() {
        if (usbState == UsbState.STOPPED && !usbBusy && !usbScanning) return
        usbState = UsbState.STOPPED
        usbStartedKey = null
        usbPlayQueue = emptyList()
        usbJob?.cancel()
        if (usbScanning) {
            usbScanning = false
            ui { updateScanButtonUi() }
        }
    }

    private fun stopUsbPlayback() {
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
                disconnectIfBackground()
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
        val i = if (activeInput == "IPOD/USB") "USB: $activePlaylistName" else (activeInput ?: "—")
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
            !activeInput.isNullOrBlank() -> {
                if (activeInput == "IPOD/USB") "🔌  USB: $activePlaylistName"
                else "🎚  ${activeInput!!}"
            }
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
        if (isOn) {
            abandonUsbPlayback()
            send("PF")
            autoReconnectAllowed = false
        } else {
            send("PO")
            autoReconnectAllowed = true
        }
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
                s.soTimeout =
