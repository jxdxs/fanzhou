package com.kevin.fanzhou.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.kevin.fanzhou.R
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.CloudFile
import com.kevin.fanzhou.cloud.Resolved
import com.kevin.fanzhou.data.PlayItem
import com.kevin.fanzhou.subtitle.OnlineSubtitles
import android.widget.EditText
import java.io.File
import com.kevin.fanzhou.data.PlayQueue
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.SmbClient
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Second player core backed by libmpv (FFmpeg software/MediaCodec decoding): plays containers and
 * codecs ExoPlayer cannot (ISO, RMVB, WMV, VC-1, DTS…). UI mirrors [PlayerActivity].
 */
@OptIn(UnstableApi::class)
class MpvPlayerActivity : ComponentActivity(), MPVLib.EventObserver, SurfaceHolder.Callback {

    private var mpv: MPVLib? = null
    private var mediaSession: MediaSession? = null
    private lateinit var surface: SurfaceView
    private lateinit var controls: View
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var loading: ProgressBar
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var posView: TextView
    private lateinit var durView: TextView
    private lateinit var playPause: ImageButton
    private lateinit var rotateBtn: ImageButton
    private lateinit var lockBtn: ImageButton
    private lateinit var touch: View
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var isEnteringPip = false
    private var activeAbSegment: AbSegment? = null

    private var queue: MutableList<PlayItem> = mutableListOf()
    private var currentIndex = -1
    private val originals = HashMap<Int, PlayItem>()
    private val qualityLabel = HashMap<Int, String>()
    /** Cloud quality renditions (best first) fetched per queue index, reused by the 画质 menu. */
    private val qualityCache = HashMap<Int, List<Pair<String, Resolved>>>()
    /** Queue indexes whose remembered per-folder quality has already been applied. */
    private val folderQualityApplied = HashSet<Int>()
    /** Queue indexes that already used the one-shot Jellyfin transcode fallback. */
    private val jfFallbackTried = HashSet<Int>()
    /** Next cloud-ladder position to try per index after a load failure. */
    private val cloudLadderStep = HashMap<Int, Int>()
    private var cloudFallbackBusy = false
    /** "idx:path" of online subtitles already added to mpv. */
    private val addedOnlineSubs = HashSet<String>()
    private var coreForced = false
    private var switching = false
    private var isHandoff = false

    // mpv state (written from the mpv event thread, read on the main thread)
    @Volatile private var posSec = 0.0
    @Volatile private var durSec = 0.0
    @Volatile private var cacheSec = 0.0
    @Volatile private var paused = false
    @Volatile private var buffering = false
    private var fileLoaded = false
    private var fileStarted = false
    private var surfaceReady = false
    private var pendingLoad: (() -> Unit)? = null
    private var scrubbing = false
    private var speed = 1.0
    private var aspectMode = 0
    private val aspectNames = arrayOf("适应", "裁剪填充", "拉伸")
    private var longPressActive = false
    private var speedBeforeLongPress = 1.0
    private var isInitialPlay = true
    private val posMs get() = (posSec * 1000).toLong()
    private val durMs get() = (durSec * 1000).toLong()

    private val pickSubLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) importLocalSub(uri)
    }

    private fun importLocalSub(uri: Uri) {
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        showHint("正在导入本地字幕…", sticky = true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取选中的字幕文件")
                val name = runCatching {
                    var n: String? = null
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) n = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    }
                    n
                }.getOrNull() ?: uri.lastPathSegment ?: "local.srt"
                val (data, rawExt) = OnlineSubtitles.unwrap(bytes, name, File(cacheDir, "subs"))
                val utf8Data = OnlineSubtitles.toUtf8(data)
                val ext = OnlineSubtitles.hintExt(utf8Data, rawExt)
                val safe = p.title.replace(Regex("""[^\p{L}\p{N}]+"""), "_").take(40).trim('_').ifEmpty { "sub" }
                val out = File(File(cacheDir, "subs"), "${safe}_本地.$ext")
                out.writeBytes(utf8Data)
                withContext(Dispatchers.Main) {
                    hintView.visibility = View.GONE
                    val key = p.effectiveResumeKey
                    val titleKey = "title:" + p.title.trim()
                    Repo.store.saveSub(key, out.absolutePath)
                    Repo.store.saveSub(titleKey, out.absolutePath)
                    if (idx == currentIndex) {
                        addOnlineSub(out)
                    }
                    toast("本地字幕已加载")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hintView.visibility = View.GONE
                    toast("导入失败：${e.message ?: "读取错误"}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        PlayerOrientation.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        coreForced = intent?.getBooleanExtra(PlayerCore.EXTRA_FORCED, false) == true
        isHandoff = intent?.getBooleanExtra(PlayerCore.EXTRA_HANDOFF, false) == true
        hideSystemBars()
        setContentView(R.layout.mpv_player)
        bindViews()
        PlayerOrientation.compactButtons(this, window.decorView, mpvTransportIds); stretchMpvRow()
        queue = PlayQueue.items.toMutableList()
        intent?.data?.let { d ->
            val url = d.toString()
            val resumeKey = "url:${url.substringBefore('?')}"
            val savedPos = Repo.store.position(resumeKey)
            queue = mutableListOf(
                PlayItem(
                    url = url,
                    title = d.lastPathSegment ?: "视频",
                    startMs = savedPos,
                    resumeKey = resumeKey
                )
            )
            PlayQueue.set(queue, 0)
        }
        if (queue.isEmpty()) { finish(); return }
        if (!initMpv()) {
            toast("MPV 内核加载失败，已改用 ExoPlayer")
            PlayerCore.launch(this, mpv = false, forced = true)
            finish(); return
        }
        initMediaSession()
        surface.holder.addCallback(this)
        playIndex(PlayQueue.index.coerceIn(0, queue.size - 1))
        handler.postDelayed(uiTick, 500)
        handler.postDelayed(progressTick, 10_000)
        showControls()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        coreForced = intent.getBooleanExtra(PlayerCore.EXTRA_FORCED, false)
        isHandoff = intent.getBooleanExtra(PlayerCore.EXTRA_HANDOFF, false)
        saveCurrent(stopped = true)
        originals.clear(); qualityLabel.clear()
        qualityCache.clear(); folderQualityApplied.clear(); jfFallbackTried.clear(); cloudLadderStep.clear()
        queue = PlayQueue.items.toMutableList()
        if (queue.isEmpty()) { finish(); return }
        isInitialPlay = true
        playIndex(PlayQueue.index.coerceIn(0, queue.size - 1))
    }

    // ---------------- mpv ----------------

    private fun initMpv(): Boolean = try {
        val m = MPVLib.create(this) ?: throw IllegalStateException("libmpv create failed")
        val hw = Repo.store.mpvHwdec
        m.setOptionString("vo", "gpu")
        m.setOptionString("gpu-context", "android")
        m.setOptionString("opengl-es", "yes")
        m.setOptionString("hwdec", if (hw) "mediacodec-copy" else "no")
        m.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        m.setOptionString("ao", "audiotrack,opensles")
        m.setOptionString("tls-verify", "no")
        val bufMb = Repo.store.bufferSizeMb
        val safeMpvMb = bufMb.coerceIn(32, 256)
        val maxBytes = safeMpvMb * 1024L * 1024L
        val backBytes = (maxBytes / 4).coerceIn(16L * 1024 * 1024, 48L * 1024 * 1024)
        m.setOptionString("cache", "yes")
        m.setOptionString("demuxer-max-bytes", "$maxBytes")
        m.setOptionString("demuxer-max-back-bytes", "$backBytes")
        m.setOptionString("network-timeout", "30")
        m.setOptionString("keep-open", "yes")
        m.setOptionString("input-default-bindings", "no")
        m.setOptionString("save-position-on-quit", "no")
        m.setOptionString("alang", "chi,zho,zh,chs,cht,cmn,jpn,ja,eng,en")
        m.setOptionString("slang", "chi,zho,zh,chs,cht,sc,tc,zh-hans,zh-hant")
        // libass on Android has no font provider: let it scan the system fonts (CJK included).
        m.setOptionString("sub-fonts-dir", "/system/fonts")
        m.setOptionString("osd-fonts-dir", "/system/fonts")
        m.setOptionString("sub-font", "Noto Sans CJK SC")
        m.setOptionString("sub-font-size", "46")
        val subColorHex = when (Repo.store.subtitleColor) {
            "yellow" -> "#FFE600"
            "cyan" -> "#00FFFF"
            else -> "#FFFFFF"
        }
        m.setOptionString("sub-color", subColorHex)
        m.setOptionString("sub-border-color", "#000000")
        m.setOptionString("sub-border-size", "2.5")
        m.setOptionString("sub-scale", "${Repo.store.subtitleScale}")
        m.setOptionString("user-agent", "Fanzhou/2.0 (Android) libmpv")
        m.init()
        m.setOptionString("force-window", "no")
        m.setOptionString("idle", "yes")
        m.addObserver(this)
        m.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        m.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        m.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        m.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        m.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        m.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv = m
        applyAudioBoost()
        applySubtitleStyle()
        true
    } catch (t: Throwable) {
        mpv = null
        false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val m = mpv ?: return
        m.attachSurface(holder.surface)
        m.setOptionString("force-window", "yes")
        surfaceReady = true
        val p = pendingLoad
        pendingLoad = null
        if (p != null) p() else m.setPropertyString("vo", "gpu")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mpv?.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val m = mpv ?: return
        surfaceReady = false
        m.setPropertyString("vo", "null")
        m.setOptionString("force-window", "no")
        m.detachSurface()
    }

    // EventObserver — called on mpv's thread.
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: String) {}

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> posSec = value
            "duration" -> durSec = value
            "demuxer-cache-time" -> cacheSec = value
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> { paused = value; handler.post { updatePlayPause() } }
            "paused-for-cache" -> { buffering = value; handler.post { loading.visibility = if (value) View.VISIBLE else View.GONE } }
            "eof-reached" -> if (value) handler.post { onEnded() }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> handler.post { fileStarted = true; fileLoaded = false; loading.visibility = View.VISIBLE }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> handler.post { onFileLoaded() }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> handler.post { if (!buffering) loading.visibility = View.GONE }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> handler.post {
                // END_FILE after START_FILE but before FILE_LOADED means the file failed to open.
                if (fileStarted && !fileLoaded) onLoadFailed()
                fileStarted = false
            }
        }
    }

    // ---------------- loading ----------------

    private fun playIndex(i: Int) {
        if (i !in queue.indices) return
        activeAbSegment = null
        updateSpeedLabel()
        if (currentIndex >= 0 && currentIndex != i) saveCurrent(stopped = true)
        currentIndex = i
        PlayQueue.index = i
        val p = queue[i]
        val savedPos = p.startMs
        val isHandoffPlayback = isHandoff
        isHandoff = false
        val isDirectResume = isInitialPlay && savedPos > 3000L
        isInitialPlay = false
        val initialSeek = if (isDirectResume || isHandoffPlayback) savedPos else 0L

        PlayerCore.bumpCloudRecent(p)
        titleView.text = listOfNotNull(p.title, p.subtitle).joinToString("  ·  ")
        posSec = initialSeek / 1000.0
        durSec = 0.0
        skipController.onIndexChanged(i, initialSeek)
        updateChapterMarkers()
        if (isDirectResume) {
            dismissResumePrompt(immediate = true)
        } else {
            dismissResumePrompt()
        }
        if (!isHandoffPlayback && !isDirectResume && savedPos > 3000L) {
            showResumePrompt(savedPos)
        }
        updateMediaSessionMetadata(p)
        updateMediaSessionState()
        reportStart(p)

        if (qualityLabel[i] != null) {
            load(p, initialSeek)
            val curLabel = qualityLabel[i]
            if (!curLabel.isNullOrEmpty() && curLabel != "原画") {
                preResolveUpcomingMpv(i + 1, curLabel)
            }
            return
        }

        val orig = originals[i] ?: p
        val remembered = Repo.store.qualityForSeries(orig.seriesKey)
        if (remembered.isNullOrEmpty() || remembered == "原画") {
            load(p, initialSeek)
            return
        }

        val jfId = orig.jfItemId
        val sid = orig.sourceId
        val cf = orig.cloudFile

        if (jfId != null && sid != null) {
            val client = Repo.jellyfin(sid)
            val jfMatch = PlayerCore.matchQuality(PlayerCore.JF_PRESETS.map { it.first to Resolved("", emptyMap(), null) }, remembered)
            val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == jfMatch?.first } ?: PlayerCore.JF_PRESETS.firstOrNull { it.first == remembered }
            if (client != null && preset != null) {
                originals.getOrPut(i) { orig }
                val newItem = orig.copy(
                    url = client.transcodeUrl(jfId, orig.jfMediaSourceId, preset.second, preset.third),
                    headers = emptyMap(),
                    mime = null
                )
                queue[i] = newItem
                qualityLabel[i] = preset.first
                folderQualityApplied.add(i)
                load(newItem, initialSeek)
                showHint("画质：${preset.first}（已记住本剧设置）")
                preResolveUpcomingMpv(i + 1, remembered)
                return
            }
        }

        if (cf != null && sid != null) {
            val cached = qualityCache[i]
            if (!cached.isNullOrEmpty()) {
                val hit = PlayerCore.matchQuality(cached, remembered)
                if (hit != null) {
                    originals.getOrPut(i) { orig }
                    val newItem = orig.copy(
                        url = hit.second.url,
                        headers = hit.second.headers,
                        mime = hit.second.mime,
                        cloudFile = cf,
                        subtitles = orig.subtitles
                    )
                    queue[i] = newItem
                    qualityLabel[i] = hit.first
                    folderQualityApplied.add(i)
                    load(newItem, initialSeek)
                    showHint("画质：${hit.first}（已记住本剧设置）")
                    preResolveUpcomingMpv(i + 1, remembered)
                    return
                }
            }

            loading.visibility = View.VISIBLE
            lifecycleScope.launch {
                val qs = runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                if (qs.isNotEmpty()) qualityCache[i] = qs
                if (currentIndex != i) return@launch
                val hit = PlayerCore.matchQuality(qs, remembered)
                val itemToPlay = if (hit != null) {
                    originals.getOrPut(i) { orig }
                    val newItem = orig.copy(
                        url = hit.second.url,
                        headers = hit.second.headers,
                        mime = hit.second.mime,
                        cloudFile = cf,
                        subtitles = orig.subtitles
                    )
                    queue[i] = newItem
                    qualityLabel[i] = hit.first
                    folderQualityApplied.add(i)
                    showHint("画质：${hit.first}（已记住本剧设置）")
                    newItem
                } else {
                    orig
                }
                load(itemToPlay, initialSeek)
                preResolveUpcomingMpv(i + 1, remembered)
            }
            return
        }

        load(p, initialSeek)
    }

    /** If the user manually picked a quality in this cloud folder/series before, start new episodes at it. */
    private fun applyFolderQuality(idx: Int) {
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val key = orig.seriesKey
        val remembered = Repo.store.qualityForSeries(key) ?: return
        if (qualityLabel[idx] != null || !folderQualityApplied.add(idx)) {
            val cur = qualityLabel[idx] ?: remembered
            if (!cur.isNullOrEmpty() && cur != "原画") {
                preResolveUpcomingMpv(idx + 1, cur)
            }
            return
        }
        val cf = orig.cloudFile
        val jfId = orig.jfItemId
        val sid = orig.sourceId ?: return
        lifecycleScope.launch {
            if (jfId != null) {
                val client = Repo.jellyfin(sid) ?: return@launch
                val jfMatch = PlayerCore.matchQuality(PlayerCore.JF_PRESETS.map { it.first to Resolved("", emptyMap(), null) }, remembered)
                val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == jfMatch?.first } ?: PlayerCore.JF_PRESETS.firstOrNull { it.first == remembered } ?: return@launch
                if (idx != currentIndex) return@launch
                replaceCurrent(orig.copy(url = client.transcodeUrl(jfId, orig.jfMediaSourceId, preset.second, preset.third), headers = emptyMap(), mime = null), preset.first)
                showHint("画质：${preset.first}（已记住本剧设置）")
                preResolveUpcomingMpv(idx + 1, remembered)
            } else if (cf != null) {
                val qs = qualityCache[idx] ?: runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    .also { if (it.isNotEmpty()) qualityCache[idx] = it }
                if (idx != currentIndex) return@launch
                val hit = PlayerCore.matchQuality(qs, remembered) ?: return@launch
                replaceCurrent(orig.copy(url = hit.second.url, headers = hit.second.headers, mime = hit.second.mime, cloudFile = cf, subtitles = p.subtitles), hit.first)
                showHint("画质：${hit.first}（已记住本剧设置）")
                preResolveUpcomingMpv(idx + 1, remembered)
            }
        }
    }

    private fun preResolveUpcomingMpv(fromIndex: Int, preferredLabel: String) {
        if (preferredLabel.isEmpty() || preferredLabel == "原画") return
        lifecycleScope.launch(Dispatchers.IO) {
            val maxIdx = (fromIndex + 5).coerceAtMost(queue.size - 1)
            for (idx in fromIndex..maxIdx) {
                if (qualityLabel[idx] != null) continue
                val item = originals[idx] ?: queue[idx]
                val jfId = item.jfItemId
                val sid = item.sourceId
                if (jfId != null && sid != null) {
                    val client = Repo.jellyfin(sid) ?: continue
                    val jfMatch = PlayerCore.matchQuality(PlayerCore.JF_PRESETS.map { it.first to Resolved("", emptyMap(), null) }, preferredLabel)
                    val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == jfMatch?.first } ?: PlayerCore.JF_PRESETS.firstOrNull { it.first == preferredLabel } ?: continue
                    val updated = item.copy(
                        url = client.transcodeUrl(jfId, item.jfMediaSourceId, preset.second, preset.third),
                        headers = emptyMap(),
                        mime = null
                    )
                    withContext(Dispatchers.Main) {
                        originals.getOrPut(idx) { item }
                        queue[idx] = updated
                        qualityLabel[idx] = preset.first
                    }
                } else {
                    val cf = item.cloudFile ?: continue
                    if (sid == null) continue
                    val qs = qualityCache[idx] ?: runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    if (qs.isNotEmpty()) {
                        qualityCache[idx] = qs
                        val hit = PlayerCore.matchQuality(qs, preferredLabel)
                        if (hit != null) {
                            withContext(Dispatchers.Main) {
                                val updated = item.copy(
                                    url = hit.second.url,
                                    headers = hit.second.headers,
                                    mime = hit.second.mime,
                                    cloudFile = cf,
                                    subtitles = item.subtitles
                                )
                                originals.getOrPut(idx) { item }
                                queue[idx] = updated
                                qualityLabel[idx] = hit.first
                            }
                        }
                    }
                }
            }
        }
    }

    private fun load(p: PlayItem, startMs: Long) {
        val m = mpv ?: return
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val target = try { resolveTarget(p) } catch (e: Exception) {
                loading.visibility = View.GONE
                toast("获取播放地址失败：${e.message}")
                return@launch
            }
            val (url, headers) = target
            val doLoad = {
                applyHeaders(headers)
                m.setPropertyString("start", if (startMs > 0) "%.3f".format(java.util.Locale.US, startMs / 1000.0) else "none")
                // fileLoaded is reset on START_FILE, so the old file's END_FILE is not mistaken for a failure.
                m.command(arrayOf("loadfile", url, "replace"))
                m.setPropertyDouble("speed", speed)
                m.setPropertyBoolean("pause", false)
            }
            if (surfaceReady) doLoad() else pendingLoad = doLoad
        }
    }

    /** cloud:// → signed link, smb:// → local HTTP proxy, content:// → file descriptor. */
    private suspend fun resolveTarget(p: PlayItem): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(p.url)
        when (uri.scheme) {
            "cloud" -> {
                val ref = CloudDrive.fromUri(uri) ?: throw IllegalStateException("无效的网盘地址")
                val r = CloudDrive.resolveCached(ref.first, ref.second)
                r.url to r.headers
            }
            "smb" -> LocalProxy.httpUrl(p.url) to emptyMap()
            "content" -> {
                val fd = contentResolver.openFileDescriptor(uri, "r")?.detachFd() ?: throw IllegalStateException("无法打开文件")
                "fdclose://$fd" to emptyMap()
            }
            else -> p.url to p.headers
        }
    }

    private fun applyHeaders(h: Map<String, String>) {
        val m = mpv ?: return
        val ua = h.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        m.setPropertyString("user-agent", ua ?: "Fanzhou/2.0 (Android) libmpv")
        m.command(arrayOf("change-list", "http-header-fields", "clr", ""))
        h.filterKeys { !it.equals("User-Agent", true) }.forEach { (k, v) ->
            m.command(arrayOf("change-list", "http-header-fields", "append", "$k: $v"))
        }
    }

    private fun onFileLoaded() {
        fileLoaded = true
        loading.visibility = View.GONE
        val m = mpv ?: return
        m.setPropertyString("start", "none")
        val p = queue.getOrNull(currentIndex) ?: return
        updateMediaSessionMetadata(p)
        updateMediaSessionState()
        loadMpvChapters()
        checkAutoSkip()
        attachSavedSub(p)
        // External subtitles (sibling files / Jellyfin / cloud).
        p.subtitles.forEach { s ->
            lifecycleScope.launch {
                val url = runCatching {
                    val u = Uri.parse(s.url)
                    withContext(Dispatchers.IO) {
                        when (u.scheme) {
                            "cloud" -> CloudDrive.fromUri(u)?.let { CloudDrive.resolveCached(it.first, it.second).url }
                            "smb" -> LocalProxy.httpUrl(s.url)
                            else -> s.url
                        }
                    }
                }.getOrNull() ?: return@launch
                mpv?.command(arrayOf("sub-add", url, "auto", s.label, s.lang ?: ""))
            }
        }
        applyAspect()
        applyAudioBoost()
        applySubtitleStyle()
        updatePipParams()
    }

    private fun onLoadFailed() {
        loading.visibility = View.GONE
        if (switching) return
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val sid = orig.sourceId
        // Cloud file: automatically step down the drive's transcode ladder.
        val cf = orig.cloudFile
        if (cf != null && sid != null) { cloudFallbackOrElse(idx, orig, cf, sid); return }
        jfFallbackOrToast(idx, orig)
    }

    /** Last resorts after the cloud ladder: Jellyfin server transcode (once), else a toast. */
    private fun jfFallbackOrToast(idx: Int, orig: PlayItem) {
        val sid = orig.sourceId
        val jfId = orig.jfItemId
        if (jfId != null && sid != null && jfFallbackTried.add(idx)) {
            val client = Repo.jellyfin(sid)
            if (client != null) {
                val remembered = Repo.store.qualityForSeries(orig.seriesKey)
                val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == remembered } ?: PlayerCore.JF_PRESETS[1]
                val label = preset.first
                Repo.store.setQualityForSeries(orig.seriesKey, label)
                toast("直连播放失败，已切换为服务器转码（已记住本剧设置）")
                replaceCurrent(orig.copy(url = client.transcodeUrl(jfId, orig.jfMediaSourceId, preset.second, preset.third), headers = client.authHeaders()), label)
                preResolveUpcomingMpv(idx + 1, label)
                return
            }
        }
        toast("MPV 无法播放此文件")
        showControls()
    }

    /**
     * The current cloud rendition failed: continue at the next lower rendition
     * from the drive's transcode ladder, keeping the position. When the ladder
     * is empty or exhausted, fall back to [jfFallbackOrToast].
     */
    private fun cloudFallbackOrElse(idx: Int, orig: PlayItem, cf: CloudFile, sid: String) {
        if (cloudFallbackBusy) return
        cloudFallbackBusy = true
        lifecycleScope.launch {
            try {
                val qs = qualityCache[idx] ?: runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    .also { if (it.isNotEmpty()) qualityCache[idx] = it }
                if (idx != currentIndex) return@launch
                if (qs.isEmpty()) { jfFallbackOrToast(idx, orig); return@launch }

                val remembered = Repo.store.qualityForSeries(orig.seriesKey)
                val matched = if (remembered != null) PlayerCore.matchQuality(qs, remembered) else null
                val (label, r) = if (matched != null && matched.first != qualityLabel[idx]) {
                    matched
                } else {
                    val curLabel = qualityLabel[idx] ?: "原画"
                    val curPos = if (curLabel == "原画") -1 else qs.indexOfFirst { it.first == curLabel }
                    val next = cloudLadderStep[idx] ?: (curPos + 1)
                    if (next !in qs.indices) { jfFallbackOrToast(idx, orig); return@launch }
                    cloudLadderStep[idx] = next + 1
                    qs[next]
                }

                val subs = queue.getOrNull(idx)?.subtitles ?: orig.subtitles
                qualityLabel[idx] = label
                Repo.store.setQualityForSeries(orig.seriesKey, label)
                replaceCurrent(orig.copy(url = r.url, headers = r.headers, mime = r.mime, cloudFile = cf, subtitles = subs), label)
                showHint("原画播放失败，已自动切换：$label（已记住本剧设置）")
                preResolveUpcomingMpv(idx + 1, label)
            } finally { cloudFallbackBusy = false }
        }
    }

    /** Bottom-bar 选集 button: jump within the current queue; popup anchored at the button. */
    private fun showEpisodeMenu(anchor: View) {
        if (queue.size <= 1) { toast("当前只有一个视频"); return }
        val labels = queue.map { item -> listOfNotNull(item.title, item.subtitle).joinToString(" · ") }
        EpisodePopup.show(anchor, labels, currentIndex) { which ->
            if (which != currentIndex) { playIndex(which); showHint("第 ${which + 1} 集") }
        }
    }

    private fun replaceCurrent(item: PlayItem, label: String) {
        val idx = currentIndex
        originals.getOrPut(idx) { queue[idx] }
        queue[idx] = item
        qualityLabel[idx] = label
        load(item, posMs)
    }

    private fun onEnded() {
        saveCurrent(stopped = true, finished = true)
        if (Repo.store.autoPlayNext && currentIndex + 1 < queue.size) playIndex(currentIndex + 1)
        else showControls()
    }

    // ---------------- progress & reporting ----------------

    private fun reportStart(p: PlayItem) {
        val id = p.jfItemId ?: return
        val sid = p.sourceId ?: return
        lifecycleScope.launch { Repo.jellyfin(sid)?.reportStart(id, p.jfMediaSourceId, p.startMs) }
    }

    private var hasResumedFromSaved = false

    @kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun saveCurrent(stopped: Boolean, finished: Boolean = false) {
        val idx = currentIndex
        val p = (originals[idx] ?: queue.getOrNull(idx)) ?: return
        val rawPos = if (finished) durMs else posMs
        val dur = durMs
        val pos = if (!hasResumedFromSaved && resumeTargetPos > 3000L && rawPos < resumeTargetPos && rawPos < 60_000L && !finished) {
            resumeTargetPos
        } else {
            rawPos
        }
        val done = finished || (dur > 0 && pos > dur * 0.95)
        if (pos <= 0 && !done) return
        val key = p.resumeKey ?: p.effectiveResumeKey
        Repo.store.saveProgress(key, pos, dur, done)
        val id = p.jfItemId ?: return
        val client = Repo.jellyfin(p.sourceId ?: return) ?: return
        val isPaused = paused
        kotlinx.coroutines.GlobalScope.launch {
            if (stopped) client.reportStop(id, p.jfMediaSourceId, pos)
            else client.reportProgress(id, p.jfMediaSourceId, pos, isPaused)
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (!paused && fileLoaded) saveCurrent(stopped = false)
            handler.postDelayed(this, 10_000)
        }
    }

    private val skipController = PlayerSkipController()

    private fun updateChapterMarkers() {
        if (!::timeBar.isInitialized) return
        val chs = skipController.chapters
        if (chs.isEmpty()) {
            timeBar.setAdGroupTimesMs(longArrayOf(), booleanArrayOf(), 0)
            return
        }
        val times = chs.map { it.timeMs }.filter { it > 0 }.toLongArray()
        val played = BooleanArray(times.size)
        timeBar.setAdMarkerColor(0xCCFFFFFF.toInt())
        timeBar.setPlayedAdMarkerColor(0x88FFFFFF.toInt())
        timeBar.setAdGroupTimesMs(times, played, times.size)
    }

    private fun loadMpvChapters() {
        val m = mpv ?: return
        val count = m.getPropertyInt("chapter-list/count") ?: 0
        if (count <= 0) {
            skipController.chapters = emptyList()
            updateChapterMarkers()
            return
        }
        val list = ArrayList<PlayerChapter>(count)
        for (i in 0 until count) {
            val timeSec = m.getPropertyDouble("chapter-list/$i/time") ?: 0.0
            val title = m.getPropertyString("chapter-list/$i/title")?.takeIf { it.isNotEmpty() } ?: "第 ${i + 1} 章"
            list.add(PlayerChapter((timeSec * 1000).toLong(), title))
        }
        list.sortBy { it.timeMs }
        skipController.chapters = list
        updateChapterMarkers()
    }

    private fun showChapterDialog() {
        val chs = skipController.chapters
        if (chs.isEmpty()) return
        val curPos = (posSec * 1000).toLong()
        val curIdx = chs.indexOfLast { it.timeMs <= curPos }.coerceAtLeast(0)
        val items = chs.map { ch ->
            val m = ch.timeMs / 1000 / 60
            val s = (ch.timeMs / 1000) % 60
            "%02d:%02d  %s".format(m, s, ch.title)
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选择章节 (${chs.size})")
            .setSingleChoiceItems(items, curIdx) { dialog, which ->
                val target = chs[which]
                seekTo(target.timeMs)
                showHint("跳转章节: ${target.title}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkAutoSkip() {
        if (mpv == null || paused || !fileLoaded || currentIndex !in queue.indices) return
        if (resumePromptView?.visibility == View.VISIBLE) return
        val pos = posMs
        val dur = durMs
        if (dur <= 0) return
        val p = queue.getOrNull(currentIndex)
        skipController.checkAutoSkip(
            posMs = pos,
            durMs = dur,
            currentIndex = currentIndex,
            queueSize = queue.size,
            seriesKey = p?.seriesKey,
            onSeek = { seekTo(it) },
            onNextEpisode = { playIndex(currentIndex + 1) },
            showHint = { showHint(it) },
        )
    }

    private fun applyAudioBoost() {
        AudioBoost.applyMpv(mpv)
    }

    private fun showAudioEnhanceDialog(anchor: View? = null) {
        val targetAnchor = anchor ?: findViewById(R.id.mpv_settings) ?: topBar
        AudioEnhancePopup.show(
            anchor = targetAnchor,
            onChanged = { applyAudioBoost() },
            showHint = { showHint(it) }
        )
    }

    private var resumePromptView: View? = null
    private var resumePromptText: TextView? = null
    private var resumeCountdown: Int = 10
    private var resumeTargetPos: Long = 0L
    private val resumeCountdownRunnable = object : Runnable {
        override fun run() {
            resumeCountdown--
            if (resumeCountdown <= 0) {
                dismissResumePrompt()
            } else {
                resumePromptText?.text = "上次播放至 ${fmt(resumeTargetPos)} (${resumeCountdown}s)"
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun dismissResumePrompt(immediate: Boolean = false) {
        handler.removeCallbacks(resumeCountdownRunnable)
        resumePromptView?.let { prompt ->
            if (immediate) {
                prompt.animate().cancel()
                prompt.alpha = 0f
                prompt.visibility = View.GONE
            } else if (prompt.visibility == View.VISIBLE) {
                prompt.animate().alpha(0f).setDuration(250).withEndAction {
                    prompt.visibility = View.GONE
                }
            }
        }
    }

    private fun showResumePrompt(savedPos: Long) {
        if (savedPos <= 3000L) return
        val prompt = resumePromptView ?: return
        handler.removeCallbacks(resumeCountdownRunnable)
        hasResumedFromSaved = false
        resumeTargetPos = savedPos
        resumeCountdown = 10
        resumePromptText?.text = "上次播放至 ${fmt(savedPos)} (10s)"
        prompt.alpha = 0f
        prompt.visibility = View.VISIBLE
        prompt.animate().alpha(1f).setDuration(250).start()
        handler.postDelayed(resumeCountdownRunnable, 1000)
    }

    private fun buildResumePromptView(root: FrameLayout) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6212121.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x55FFFFFF.toInt())
            }
            setPadding(dp(14), dp(6), dp(10), dp(6))
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }

        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        resumePromptText = tv
        container.addView(tv)

        val btnContinue = TextView(this).apply {
            text = "继续"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF3D7BE6.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(10)
            }
            layoutParams = lp
            setOnClickListener {
                hasResumedFromSaved = true
                seekTo(resumeTargetPos)
                showHint("已跳转至上次播放位置")
                dismissResumePrompt()
            }
        }
        container.addView(btnContinue)

        val btnCancel = TextView(this).apply {
            text = "取消"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(6)
            }
            layoutParams = lp
            setOnClickListener {
                dismissResumePrompt()
            }
        }
        container.addView(btnCancel)

        resumePromptView = container
        val promptLp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(16)
            bottomMargin = dp(84)
        }
        root.addView(container, promptLp)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                if (!paused && fileLoaded) {
                    mpv?.setPropertyBoolean("pause", true)
                    paused = true
                    updatePlayPause()
                    showHint("已暂停 (耳机已断开)")
                }
            }
        }
    }

    private fun checkAbLoop() {
        val seg = activeAbSegment ?: return
        if (!fileLoaded || paused) return
        val pos = posMs
        if (pos >= seg.endMs) {
            seekTo(seg.startMs)
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            if (!scrubbing) {
                timeBar.setDuration(durMs.coerceAtLeast(0))
                timeBar.setPosition(posMs.coerceAtLeast(0))
                timeBar.setBufferedPosition(((posSec + max(0.0, cacheSec - posSec)) * 1000).toLong())
                posView.text = fmt(posMs)
                durView.text = fmt(durMs)
                updateMediaSessionState()
                checkAutoSkip()
                checkAbLoop()
            }
            handler.postDelayed(this, if (activeAbSegment != null) 200 else 500)
        }
    }

    // ---------------- controls ----------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun updateRotateBtnPosition() {
        if (!::rotateBtn.isInitialized) return
        val shortEdge = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val bottomMargin = (shortEdge - dp(44)) / 2
        val lp = rotateBtn.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(dp(44), dp(44))
        lp.gravity = Gravity.START or Gravity.BOTTOM
        lp.marginStart = dp(16)
        lp.bottomMargin = bottomMargin
        rotateBtn.layoutParams = lp
    }

    private fun bindViews() {
        surface = findViewById(R.id.mpv_surface)
        controls = findViewById(R.id.mpv_controls)
        topBar = findViewById(R.id.mpv_top_bar)
        hintView = findViewById(R.id.mpv_hint)
        loading = findViewById(R.id.mpv_loading)
        timeBar = findViewById(R.id.mpv_progress)
        posView = findViewById(R.id.mpv_position)
        durView = findViewById(R.id.mpv_duration)
        playPause = findViewById(R.id.mpv_play_pause)
        rotateBtn = findViewById(R.id.mpv_rotate)
        updateRotateBtnPosition()
        touch = findViewById(R.id.mpv_touch)
        hintView.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x99000000.toInt()) }
        topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))

        fun iconBtn(res: Int, desc: String, onClick: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick(it); bumpControls() }
        }
        topBar.addView(iconBtn(R.drawable.ic_back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(iconBtn(R.drawable.ic_core, "切换到 ExoPlayer 内核") { switchToExo() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_hd, "画质") { v -> showQualityMenu(v) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_aspect, "画面比例") { cycleAspect() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            topBar.addView(iconBtn(R.drawable.ic_pip, "画中画") { enterPip() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        }

        rotateBtn.setOnClickListener {
            val label = PlayerOrientation.toggle(this)
            showHint(label)
            bumpControls()
        }

        lockBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_lock_open)
            contentDescription = "锁定屏幕"
            setBackgroundResource(R.drawable.rotate_fab_bg)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                lockScreen()
            }
        }
        val root = findViewById<FrameLayout>(R.id.mpv_root)
        root.addView(lockBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(16)
        })

        buildResumePromptView(root)

        val step = Repo.store.seekStepSec
        findViewById<View>(R.id.mpv_prev).setOnClickListener { if (currentIndex > 0) playIndex(currentIndex - 1) else seekTo(0); bumpControls() }
        findViewById<View>(R.id.mpv_next).setOnClickListener { if (currentIndex + 1 < queue.size) playIndex(currentIndex + 1); bumpControls() }
        findViewById<View>(R.id.mpv_rew).setOnClickListener { seekRelative(-step); bumpControls() }
        findViewById<View>(R.id.mpv_ffwd).setOnClickListener { seekRelative(step); bumpControls() }
        playPause.setOnClickListener { togglePause(); bumpControls() }
        findViewById<View>(R.id.mpv_audio).setOnClickListener { v -> showTrackMenu("audio", v); bumpControls() }
        findViewById<View>(R.id.mpv_episodes).setOnClickListener { v -> showEpisodeMenu(v); bumpControls() }
        findViewById<View>(R.id.mpv_subtitle).setOnClickListener { v -> showTrackMenu("sub", v); bumpControls() }
        findViewById<View>(R.id.mpv_settings).setOnClickListener { v -> showSettingsMenu(v); bumpControls() }
        findViewById<View>(R.id.mpv_speed)?.setOnClickListener { showSpeedPopup(it); bumpControls() }
        findViewById<View>(R.id.mpv_ab)?.setOnClickListener { showAbLoopDialog(); bumpControls() }
        updateSpeedLabel()

        val consumeAndBump = View.OnClickListener { bumpControls() }
        findViewById<View>(R.id.mpv_bottom_bar).setOnClickListener(consumeAndBump)
        val bottomBarGroup = findViewById<ViewGroup>(R.id.mpv_bottom_bar)
        for (i in 0 until bottomBarGroup.childCount) {
            bottomBarGroup.getChildAt(i).setOnClickListener(consumeAndBump)
        }
        findViewById<View>(R.id.mpv_progress_row).setOnClickListener(consumeAndBump)
        findViewById<View>(R.id.mpv_position).setOnClickListener(consumeAndBump)
        findViewById<View>(R.id.mpv_duration).setOnClickListener(consumeAndBump)
        topBar.setOnClickListener(consumeAndBump)
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                dismissResumePrompt()
                scrubbing = true
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onScrubMove(timeBar: TimeBar, position: Long) { posView.text = fmt(position) }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) {
                    seekTo(position)
                }
                bumpControls()
            }
        })
        attachGestures()

        val pipLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || right != oldRight || top != oldTop || bottom != oldBottom) {
                val w = right - left
                val oldW = oldRight - oldLeft
                val isExpanding = (oldW > 0 && w > oldW * 1.5) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode)
                if (isExpanding) {
                    updatePipParams(force = true)
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
                    updatePipParams(force = false)
                }
            }
        }
        surface.addOnLayoutChangeListener(pipLayoutListener)
        findViewById<View>(R.id.mpv_root)?.addOnLayoutChangeListener(pipLayoutListener)
        updatePipParams()
    }

    private fun updatePlayPause() {
        playPause.setImageResource(if (paused) androidx.media3.ui.R.drawable.exo_styled_controls_play else androidx.media3.ui.R.drawable.exo_styled_controls_pause)
        playPause.contentDescription = if (paused) "播放" else "暂停"
        updateMediaSessionState()
        updatePipParams()
    }

    private val hideControlsRunnable = Runnable { hideControls() }
    private fun showControls() {
        controls.visibility = View.VISIBLE
        if (::lockBtn.isInitialized) lockBtn.visibility = View.VISIBLE
        bumpControls()
    }
    private fun bumpControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!paused) handler.postDelayed(hideControlsRunnable, 10000)
    }
    private fun hideControls() {
        controls.visibility = View.GONE
        if (::lockBtn.isInitialized) lockBtn.visibility = View.GONE
        hideSystemBars()
    }

    private fun togglePause() {
        val m = mpv ?: return
        m.setPropertyBoolean("pause", !paused)
    }

    private fun seekTo(ms: Long) {
        if (resumeTargetPos > 3000L && ms >= resumeTargetPos - 5000L) {
            hasResumedFromSaved = true
        }
        mpv?.command(arrayOf("seek", "%.3f".format(java.util.Locale.US, ms / 1000.0), "absolute"))
        posSec = ms / 1000.0
    }

    private fun seekRelative(sec: Int) {
        dismissResumePrompt()
        mpv?.command(arrayOf("seek", sec.toString(), "relative"))
        showHint(if (sec < 0) "⏪ ${-sec}秒" else "⏩ ${sec}秒")
    }

    private fun setSpeed(s: Double) {
        speed = s
        mpv?.setPropertyDouble("speed", s)
        updateSpeedLabel()
    }

    /**
     * Bottom bar. Portrait keeps only 快退/播放/快进 on the left and 倍速/音轨/选集 + gear on the
     * right; 字幕 and 上一集/下一集 fold into the gear menu (see showSettingsMenu).
     */
    private fun updateSpeedLabel() {
        val portrait = PlayerOrientation.isPortrait(this)
        findViewById<View>(R.id.mpv_prev)?.visibility = if (portrait) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mpv_next)?.visibility = if (portrait) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mpv_audio)?.visibility = View.VISIBLE
        findViewById<View>(R.id.mpv_episodes)?.visibility =
            if (queue.size <= 1) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mpv_subtitle)?.visibility = if (portrait) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mpv_ab)?.visibility = View.VISIBLE
        updateAbButtonState()
        val t = findViewById<TextView>(R.id.mpv_speed) ?: return
        t.visibility = View.VISIBLE
        t.text = fmtSpeed(speed) + "x"
    }

    private fun showAbLoopDialog() {
        val p = queue.getOrNull(currentIndex) ?: return
        val curPos = posMs.coerceAtLeast(0L)
        val dur = durMs.coerceAtLeast(0L)
        val root = findViewById<ViewGroup>(R.id.mpv_root) ?: findViewById(android.R.id.content)
        AbLoopDialog.show(
            activity = this,
            root = root,
            videoKey = p.effectiveResumeKey,
            currentPosMs = curPos,
            durationMs = dur,
            activeSegmentId = activeAbSegment?.id,
            fmt = { fmt(it) },
            onStartLoop = { seg ->
                activeAbSegment = seg
                seekTo(seg.startMs)
                updateAbButtonState()
                showHint("🔁 循环「${seg.name}」")
            },
            onStopLoop = {
                activeAbSegment = null
                updateAbButtonState()
                showHint("已退出 AB 循环")
            }
        )
    }

    private fun updateAbButtonState() {
        val abBtn = findViewById<TextView>(R.id.mpv_ab) ?: return
        val isLooping = activeAbSegment != null
        abBtn.setTextColor(if (isLooping) 0xFF42A5F5.toInt() else Color.WHITE)
    }

    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % aspectNames.size
        applyAspect()
        showHint(aspectNames[aspectMode])
        updatePipParams()
    }

    private fun applyAspect() {
        val m = mpv ?: return
        when (aspectMode) {
            0 -> { m.setPropertyString("keepaspect", "yes"); m.setPropertyDouble("panscan", 0.0) }
            1 -> { m.setPropertyString("keepaspect", "yes"); m.setPropertyDouble("panscan", 1.0) }
            2 -> { m.setPropertyString("keepaspect", "no"); m.setPropertyDouble("panscan", 0.0) }
        }
    }

    private fun hideSystemBars() {
        if (isEnteringPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private val hideHint = Runnable { hintView.visibility = View.GONE }
    private fun showHint(text: String, sticky: Boolean = false) {
        hintView.text = text
        hintView.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint)
        if (!sticky) handler.postDelayed(hideHint, 900)
    }

    // ---------------- tracks / settings ----------------

    private data class Track(val id: Int, val type: String, val label: String, val selected: Boolean)

    private fun tracks(type: String): List<Track> {
        val m = mpv ?: return emptyList()
        val n = m.getPropertyInt("track-list/count") ?: 0
        return (0 until n).mapNotNull { i ->
            if (m.getPropertyString("track-list/$i/type") != type) return@mapNotNull null
            val id = m.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            val title = m.getPropertyString("track-list/$i/title")
            val lang = m.getPropertyString("track-list/$i/lang")
            val codec = m.getPropertyString("track-list/$i/codec")
            val ext = m.getPropertyBoolean("track-list/$i/external") == true
            val ch = if (type == "audio") m.getPropertyInt("track-list/$i/demux-channel-count")?.let { "${it}ch" } else null
            val label = listOfNotNull(title, lang?.let { langName(it) }, codec?.uppercase(), ch, if (ext) "外挂" else null)
                .distinct().joinToString(" · ").ifEmpty { "轨道 $id" }
            Track(id, type, label, m.getPropertyBoolean("track-list/$i/selected") == true)
        }
    }

    private fun langName(l: String) = when (l.lowercase()) {
        "chi", "zho", "zh", "chs", "zh-hans", "sc" -> "中文"
        "cht", "zh-hant", "tc" -> "繁体中文"
        "eng", "en" -> "英语"
        "jpn", "ja" -> "日语"
        "kor", "ko" -> "韩语"
        else -> l
    }

    private fun showTrackMenu(type: String, anchor: View) {
        val list = tracks(type)
        val isSub = type == "sub"
        if (isSub) {
            val items = ArrayList<TextMenu.Item>()
            items += TextMenu.Item("在线搜索字幕…") { showOnlineSubSearch() }
            items += TextMenu.Item("导入本地字幕…") { pickSubLauncher.launch("*/*") }
            items += TextMenu.Item("字幕大小与样式…") { showSubtitleStyleDialog() }
            items += TextMenu.Item("字幕延迟同步…") { showSubDelayMenu() }
            items += TextMenu.Item("关闭字幕") {
                mpv?.setPropertyString("sub-visibility", "no")
                mpv?.setPropertyString("sid", "no")
            }
            list.forEach { t ->
                val display = if (t.selected) "✓ ${t.label}" else t.label
                items += TextMenu.Item(display) {
                    mpv?.setPropertyString("sub-visibility", "yes")
                    mpv?.setPropertyString("sid", t.id.toString())
                }
            }
            TextMenu.show(anchor, items)
            return
        }
        if (list.isEmpty()) { toast("没有可选音轨"); return }
        val labels = list.map { it.label }
        val sel = list.indexOfFirst { it.selected }
        AudioTrackPopup.show(anchor, labels, sel) { which ->
            mpv?.setPropertyString("aid", list[which].id.toString())
            showHint("音轨：${labels[which]}")
        }
    }

    private fun applySubtitleScale() = applySubtitleStyle()

    private fun applySubtitleStyle() {
        val m = mpv ?: return
        m.setPropertyDouble("sub-scale", Repo.store.subtitleScale.toDouble())
        val hex = when (Repo.store.subtitleColor) {
            "yellow" -> "#FFE600"
            "cyan" -> "#00FFFF"
            else -> "#FFFFFF"
        }
        m.setPropertyString("sub-color", hex)
        when (Repo.store.subtitleStyle) {
            "shadow" -> {
                m.setPropertyString("sub-border-size", "1.0")
                m.setPropertyString("sub-border-color", "#000000")
                m.setPropertyString("sub-shadow-offset", "3.0")
                m.setPropertyString("sub-shadow-color", "#000000")
                m.setPropertyString("sub-back-color", "#00000000")
            }
            "box" -> {
                m.setPropertyString("sub-border-size", "0.0")
                m.setPropertyString("sub-shadow-offset", "0.0")
                m.setPropertyString("sub-back-color", "#99000000")
            }
            else -> { // outline
                m.setPropertyString("sub-border-size", "2.8")
                m.setPropertyString("sub-border-color", "#000000")
                m.setPropertyString("sub-shadow-offset", "0.0")
                m.setPropertyString("sub-back-color", "#00000000")
            }
        }
        val marginPx = (Repo.store.subtitleBottomMargin * resources.displayMetrics.density).toInt()
        m.setPropertyString("sub-margin-y", "$marginPx")
    }

    private val subScaleOptions = listOf(
        1.0f to "标准 (1.0x)",
        1.25f to "中等 (1.25x)",
        1.4f to "较大 (1.4x)",
        1.6f to "大 (1.6x)",
        1.85f to "特大 (1.85x)",
        2.1f to "超大 (2.1x)"
    )

    private fun subScaleLabel(scale: Float) =
        subScaleOptions.minByOrNull { kotlin.math.abs(it.first - scale) }?.second?.substringBefore(" ") ?: "较大"

    private val subColorOptions = listOf(
        "white" to "经典白 (白色高对比黑边)",
        "yellow" to "电影黄 (明黄色高对比黑边)",
        "cyan" to "清爽青 (青色高对比黑边)"
    )

    private fun subColorLabel(c: String) =
        subColorOptions.firstOrNull { it.first == c }?.second?.substringBefore(" ") ?: "经典白"

    private val subStyleOptions = listOf(
        "outline" to "经典描边 (黑边包裹)",
        "shadow" to "立体投影 (悬浮阴影)",
        "box" to "半透明底色 (深色衬底)"
    )

    private fun subStyleLabel(s: String) =
        subStyleOptions.firstOrNull { it.first == s }?.second?.substringBefore(" ") ?: "经典描边"

    private val subMarginOptions = listOf(
        10 to "贴近底部 (10dp)",
        20 to "标准居中 (20dp)",
        35 to "略微偏高 (35dp)",
        50 to "较高位置 (50dp)"
    )

    private fun subMarginLabel(m: Int) =
        subMarginOptions.firstOrNull { it.first == m }?.second?.substringBefore(" ") ?: "${m}dp"

    private fun showSubtitleScaleDialog() {
        val cur = Repo.store.subtitleScale
        val sel = subScaleOptions.indexOfFirst { kotlin.math.abs(it.first - cur) < 0.05f }.coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("字幕大小")
            .setSingleChoiceItems(subScaleOptions.map { it.second }.toTypedArray(), sel) { d, which ->
                val chosen = subScaleOptions[which].first
                Repo.store.subtitleScale = chosen
                applySubtitleStyle()
                showHint("字幕大小：${subScaleOptions[which].second}")
                d.dismiss()
            }
            .show()
    }

    private fun showSubtitleColorDialog() {
        val cur = Repo.store.subtitleColor
        val sel = subColorOptions.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("字幕颜色")
            .setSingleChoiceItems(subColorOptions.map { it.second }.toTypedArray(), sel) { d, which ->
                val chosen = subColorOptions[which].first
                Repo.store.subtitleColor = chosen
                applySubtitleStyle()
                showHint("字幕颜色：${subColorOptions[which].second.substringBefore(" ")}")
                d.dismiss()
            }
            .show()
    }

    private fun showSubtitleEffectDialog() {
        val cur = Repo.store.subtitleStyle
        val sel = subStyleOptions.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("字幕特效")
            .setSingleChoiceItems(subStyleOptions.map { it.second }.toTypedArray(), sel) { d, which ->
                val chosen = subStyleOptions[which].first
                Repo.store.subtitleStyle = chosen
                applySubtitleStyle()
                showHint("字幕特效：${subStyleOptions[which].second.substringBefore(" ")}")
                d.dismiss()
            }
            .show()
    }

    private fun showSubtitleMarginDialog() {
        val cur = Repo.store.subtitleBottomMargin
        val sel = subMarginOptions.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("字幕底部边距")
            .setSingleChoiceItems(subMarginOptions.map { it.second }.toTypedArray(), sel) { d, which ->
                val chosen = subMarginOptions[which].first
                Repo.store.subtitleBottomMargin = chosen
                applySubtitleStyle()
                showHint("底部边距：${subMarginOptions[which].second}")
                d.dismiss()
            }
            .show()
    }

    private fun showSubtitleStyleDialog() {
        val items = arrayOf(
            "字幕大小（${subScaleLabel(Repo.store.subtitleScale)}）…",
            "字幕颜色（${subColorLabel(Repo.store.subtitleColor)}）…",
            "字幕特效（${subStyleLabel(Repo.store.subtitleStyle)}）…",
            "底部边距（${subMarginLabel(Repo.store.subtitleBottomMargin)}）…"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("字幕大小与样式")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSubtitleScaleDialog()
                    1 -> showSubtitleColorDialog()
                    2 -> showSubtitleEffectDialog()
                    3 -> showSubtitleMarginDialog()
                }
            }
            .show()
    }

    /** Search the configured online subtitle providers, download a pick and attach it. */
    private fun showOnlineSubSearch() {
        val idx = currentIndex
        val p = originals[idx] ?: queue.getOrNull(idx) ?: return
        if (!OnlineSubtitles.anyConfigured()) {
            showUnconfiguredSubDialog(idx, p)
            return
        }
        showSearchDialog(idx, p)
    }

    private fun showUnconfiguredSubDialog(idx: Int, p: PlayItem) {
        val input = EditText(this).apply {
            hint = "在此粘贴射手网 Token"
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 10)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("未配置在线字幕令牌")
            .setMessage("在线字幕搜索需要配置字幕源 API 令牌（免费）。\n\n• 推荐射手网：在 assrt.net 免费注册登录后，在个人中心复制 Token 即可。\n• 您可以在下方直接粘贴 Token 快速开始，或点击「前往设置」配置其他字幕源。")
            .setView(container)
            .setPositiveButton("保存并搜索") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isEmpty()) {
                    toast("未输入令牌")
                    return@setPositiveButton
                }
                Repo.store.assrtToken = token
                toast("已保存射手网令牌")
                showSearchDialog(idx, p)
            }
            .setNeutralButton("前往设置") { _, _ ->
                val intent = Intent(this, com.kevin.fanzhou.MainActivity::class.java).apply {
                    putExtra("route", "settings")
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSearchDialog(idx: Int, p: PlayItem) {
        val input = EditText(this).apply {
            setText(OnlineSubtitles.cleanQuery(p.title))
            setSingleLine(true)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 10)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("在线搜索字幕")
            .setView(container)
            .setPositiveButton("搜索") { _, _ ->
                val q = input.text.toString().trim()
                if (q.length < 2) { toast("关键词太短"); return@setPositiveButton }
                showHint("正在搜索字幕…", sticky = true)
                lifecycleScope.launch {
                    val r = runCatching { OnlineSubtitles.searchAll(q, p.title) }
                    hintView.visibility = View.GONE
                    if (r.isFailure) { toast("搜索失败：${r.exceptionOrNull()?.message ?: "网络错误"}"); return@launch }
                    val hits = r.getOrDefault(emptyList())
                    if (hits.isEmpty()) { toast("没有搜到字幕，可以换个关键词再试"); return@launch }
                    val labels = hits.map { "【${it.provider}】${it.lang} · ${it.title}" }
                    AlertDialog.Builder(this@MpvPlayerActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("选择字幕（共 ${hits.size} 条）")
                        .setItems(labels.toTypedArray()) { _, w -> downloadOnlineSub(hits[w], idx) }
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadOnlineSub(hit: OnlineSubtitles.Hit, idx: Int) {
        val p = originals[idx] ?: queue.getOrNull(idx) ?: return
        showHint("正在下载字幕…", sticky = true)
        lifecycleScope.launch {
            val file = runCatching {
                OnlineSubtitles.fetch(hit, p.title, File(cacheDir, "subs"))
            }.getOrElse { e ->
                hintView.visibility = View.GONE
                toast("字幕下载失败：${e.message ?: "网络错误"}")
                return@launch
            }
            hintView.visibility = View.GONE
            val key = p.effectiveResumeKey
            val titleKey = "title:" + p.title.trim()
            Repo.store.saveSub(key, file.absolutePath)
            Repo.store.saveSub(titleKey, file.absolutePath)
            if (idx == currentIndex) addOnlineSub(file)
            toast("字幕已加载：${hit.provider}")
        }
    }

    private fun addOnlineSub(file: File) {
        val tag = "$currentIndex:${file.absolutePath}"
        addedOnlineSubs.add(tag)
        mpv?.setPropertyString("sub-visibility", "yes")
        mpv?.command(arrayOf("sub-add", file.absolutePath, "select", "在线字幕"))
    }

    /** Re-attach the subtitle previously downloaded for this item, if any. */
    private fun attachSavedSub(p: PlayItem) {
        val key = p.effectiveResumeKey
        val titleKey = "title:" + p.title.trim()
        val path = Repo.store.savedSub(key) ?: Repo.store.savedSub(titleKey) ?: return
        val file = File(path)
        if (file.exists()) addOnlineSub(file)
    }

    /** Settings (gear) menu: compact icon-button strip anchored above the button. */
    private fun showSettingsMenu(anchor: View) {
        val portrait = PlayerOrientation.isPortrait(this)
        if (portrait) {
            // Portrait gear holds exactly the folded buttons: 字幕 / 上一集 / 下一集 / 章节 / 跳过 / 音频增强 / 防误触.
            val items = ArrayList<IconMenu.Item>()
            items += IconMenu.Item(R.drawable.ic_subtitle, "字幕") { showTrackMenu("sub", anchor) }
            if (currentIndex > 0) items += IconMenu.Item(R.drawable.ic_prev, "上一集") { playIndex(currentIndex - 1) }
            if (currentIndex + 1 < queue.size) items += IconMenu.Item(R.drawable.ic_next, "下一集") { playIndex(currentIndex + 1) }
            if (skipController.chapters.isNotEmpty()) items += IconMenu.Item(R.drawable.ic_view_list, "章节(${skipController.chapters.size})") { showChapterDialog() }
            items += IconMenu.Item(R.drawable.ic_skip_intro, "跳过片头") { showSkipIntroPopup(anchor) }
            items += IconMenu.Item(R.drawable.ic_skip_outro, "跳过片尾") { showSkipOutroPopup(anchor) }
            items += IconMenu.Item(R.drawable.ic_audio, "音频增强") { showAudioEnhanceDialog(anchor) }
            IconMenu.show(anchor, items)
            return
        }
        val items = ArrayList<IconMenu.Item>()
        items += IconMenu.Item(R.drawable.ic_audio, "音轨") { showTrackMenu("audio", anchor) }
        items += IconMenu.Item(R.drawable.ic_speed, "${fmtSpeed(speed)}×") { showSpeedMenu() }
        items += IconMenu.Item(R.drawable.ic_sub_delay, "字幕延迟") { showSubDelayMenu() }
        items += IconMenu.Item(R.drawable.ic_decoder, if (Repo.store.mpvHwdec) "硬解" else "软解") {
            Repo.store.mpvHwdec = !Repo.store.mpvHwdec
            mpv?.setPropertyString("hwdec", if (Repo.store.mpvHwdec) "mediacodec-copy" else "no")
            showHint(if (Repo.store.mpvHwdec) "硬件解码" else "软件解码")
        }
        if (skipController.chapters.isNotEmpty()) items += IconMenu.Item(R.drawable.ic_view_list, "章节(${skipController.chapters.size})") { showChapterDialog() }
        items += IconMenu.Item(R.drawable.ic_skip_intro, "跳过片头") { showSkipIntroPopup(anchor) }
        items += IconMenu.Item(R.drawable.ic_skip_outro, "跳过片尾") { showSkipOutroPopup(anchor) }
        items += IconMenu.Item(R.drawable.ic_audio, "音频增强") { showAudioEnhanceDialog(anchor) }
        IconMenu.show(anchor, items)
    }

    /** 跳过片头：弹窗里直接调秒数（即时保存），点跳过执行。 */
    private fun showSkipIntroPopup(anchor: View) {
        val p = queue.getOrNull(currentIndex)
        skipController.showSkipIntroPopup(
            anchor = anchor,
            currentPosMs = (posSec * 1000).toLong(),
            currentIndex = currentIndex,
            seriesKey = p?.seriesKey,
            onSeek = { seekTo(it) },
            showHint = { showHint(it) },
            toast = { toast(it) },
        )
    }

    /** 跳过片尾：弹窗里直接调秒数（即时保存），有下一集播下一集，否则跳到片尾结束。 */
    private fun showSkipOutroPopup(anchor: View) {
        val p = queue.getOrNull(currentIndex)
        skipController.showSkipOutroPopup(
            anchor = anchor,
            durMs = (durSec * 1000).toLong(),
            currentIndex = currentIndex,
            seriesKey = p?.seriesKey,
            hasNext = currentIndex + 1 < queue.size,
            onNext = { playIndex(currentIndex + 1) },
            onSeekEnd = { seekTo(it) },
            showHint = { showHint(it) },
            toast = { toast(it) },
        )
    }

    /** 防误触：藏起控制条，全屏锁住触摸，只留一个锁图标；点锁图标秒解。 */
    private fun lockScreen() {
        handler.removeCallbacks(hideControlsRunnable)
        hideControls()
        ScreenLock.lock(this) {
            showControls()
        }
    }

    private fun fmtSpeed(s: Double) = "%.2f".format(s).trimEnd('0').trimEnd('.')

    private fun showSpeedPopup(anchor: View) {
        val speeds = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 5.0)
        SpeedPopup.show(anchor, speeds.map { "${fmtSpeed(it)}x" }, speeds.indexOfFirst { abs(it - speed) < 0.01 }) { w ->
            setSpeed(speeds[w]); showHint("${fmtSpeed(speeds[w])}x"); bumpControls()
        }
    }

    private fun showSpeedMenu() {
        val speeds = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 5.0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("倍速")
            .setSingleChoiceItems(speeds.map { "${fmtSpeed(it)}×" }.toTypedArray(), speeds.indexOfFirst { abs(it - speed) < 0.01 }) { d, w ->
                d.dismiss(); setSpeed(speeds[w]); showHint("${fmtSpeed(speeds[w])}×")
            }
            .show()
    }

    private fun showSubDelayMenu() {
        val opts = doubleArrayOf(-2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0)
        val cur = mpv?.getPropertyDouble("sub-delay") ?: 0.0
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("字幕延迟（当前 ${"%+.1f".format(cur)} 秒）")
            .setItems(opts.map { if (it == 0.0) "复位" else "%+.1f 秒".format(it) }.toTypedArray()) { _, w ->
                val v = if (opts[w] == 0.0) 0.0 else cur + opts[w]
                mpv?.setPropertyDouble("sub-delay", v)
                showHint("字幕延迟 ${"%+.1f".format(v)} 秒")
            }
            .show()
    }

    private fun showQualityMenu(anchor: View? = null) {
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val jfId = orig.jfItemId
        val cf = orig.cloudFile
        when {
            jfId != null -> {
                val client = Repo.jellyfin(orig.sourceId ?: return) ?: return
                val presets = PlayerCore.JF_PRESETS
                val opts = listOf<Pair<String, () -> PlayItem>>("原画" to { orig }) + presets.map { (label, br, w) ->
                    label to { orig.copy(url = client.transcodeUrl(jfId, orig.jfMediaSourceId, br, w), headers = emptyMap(), mime = null) }
                }
                pickQuality(idx, opts, anchor)
            }
            cf != null -> {
                val sid = orig.sourceId ?: return
                showHint("正在获取画质…", sticky = true)
                lifecycleScope.launch {
                    val qs = runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    if (qs.isNotEmpty()) qualityCache[idx] = qs
                    hintView.visibility = View.GONE
                    if (idx != currentIndex) return@launch
                    val opts = listOf<Pair<String, () -> PlayItem>>("原画" to { orig }) + qs.map { (label, r) ->
                        label to { orig.copy(url = r.url, headers = r.headers, mime = r.mime, cloudFile = cf) }
                    }
                    if (opts.size == 1) toast("网盘暂时没有这个视频的转码版本，只有原画")
                    pickQuality(idx, opts, anchor)
                }
            }
            else -> toast("该来源只有原画")
        }
    }

    private fun pickQuality(idx: Int, opts: List<Pair<String, () -> PlayItem>>, anchor: View? = null) {
        if (opts.size <= 1) return
        val cur = qualityLabel[idx] ?: "原画"
        val checked = opts.indexOfFirst { it.first == cur }
        val targetAnchor = anchor ?: topBar
        QualityPopup.show(targetAnchor, opts.map { it.first }, checked) { which ->
            val (label, make) = opts[which]
            if (label == cur || idx != currentIndex) return@show
            val orig = originals[idx] ?: queue.getOrNull(idx)
            val key = orig?.seriesKey
            if (key != null) {
                folderQualityApplied.add(idx)
                Repo.store.setQualityForSeries(key, if (label == "原画") null else label)
            }
            cloudLadderStep.remove(idx)
            if (label == "原画") {
                val o = originals.remove(idx) ?: return@show
                queue[idx] = o
                qualityLabel.remove(idx)
                load(o, posMs)
            } else {
                replaceCurrent(make(), label)
                if (key != null) preResolveUpcomingMpv(idx + 1, label)
            }
            showHint("画质：$label" + if (label != "原画") "（已记住本剧设置）" else "")
        }
    }

    /** Hands the queue and position back to ExoPlayer. */
    private fun switchToExo() {
        if (currentIndex < 0) return
        switching = true
        val pos = posMs
        saveCurrent(stopped = true)
        val list = queue.mapIndexed { i, p ->
            val o = originals[i] ?: p
            if (i == currentIndex) o.copy(startMs = pos) else o
        }
        PlayQueue.set(list, currentIndex)
        toast("已切换到 ExoPlayer 内核")
        PlayerCore.launch(this, mpv = false, forced = true, handoff = true)
        finish()
    }

    private fun isTouchInControlArea(rawX: Int, rawY: Int): Boolean {
        // Resume prompt touches take precedence
        val prompt = resumePromptView
        if (prompt != null && prompt.visibility == View.VISIBLE) {
            val rect = Rect()
            prompt.getGlobalVisibleRect(rect)
            if (rect.contains(rawX, rawY)) return true
        }
        if (controls.visibility != View.VISIBLE) return false
        // 1. Top bar
        if (::topBar.isInitialized && topBar.visibility == View.VISIBLE) {
            val rect = Rect()
            topBar.getGlobalVisibleRect(rect)
            if (rawY <= rect.bottom) return true
        }
        // 2. Rotate button
        if (::rotateBtn.isInitialized && rotateBtn.visibility == View.VISIBLE) {
            val rect = Rect()
            rotateBtn.getGlobalVisibleRect(rect)
            if (rect.contains(rawX, rawY)) return true
        }
        // 2b. Lock button
        if (::lockBtn.isInitialized && lockBtn.visibility == View.VISIBLE) {
            val rect = Rect()
            lockBtn.getGlobalVisibleRect(rect)
            if (rect.contains(rawX, rawY)) return true
        }
        // 4. Bottom bar area (progress row + bottom bar)
        val progressRow = findViewById<View>(R.id.mpv_progress_row)
        val bottomBar = findViewById<View>(R.id.mpv_bottom_bar)
        var bottomTop = Int.MAX_VALUE
        if (progressRow != null && progressRow.visibility == View.VISIBLE) {
            val rect = Rect()
            progressRow.getGlobalVisibleRect(rect)
            if (rect.top > 0) bottomTop = minOf(bottomTop, rect.top)
        }
        if (bottomBar != null && bottomBar.visibility == View.VISIBLE) {
            val rect = Rect()
            bottomBar.getGlobalVisibleRect(rect)
            if (rect.top > 0) bottomTop = minOf(bottomTop, rect.top)
        }
        if (bottomTop != Int.MAX_VALUE && rawY >= bottomTop) {
            return true
        }
        return false
    }

    // ---------------- gestures ----------------

    private enum class Drag { NONE, SEEK, BRIGHTNESS, VOLUME }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        var drag = Drag.NONE
        var startX = 0f
        var startY = 0f
        var startPos = 0L
        var targetPos = 0L
        var startBrightness = 0.5f
        var startVolume = 0
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val slop = dp(16)
        var touchStartedInControls = false

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controls.visibility == View.VISIBLE) {
                    if (isTouchInControlArea(e.rawX.toInt(), e.rawY.toInt())) {
                        bumpControls()
                        return true
                    }
                    hideControls()
                } else {
                    showControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (controls.visibility == View.VISIBLE && isTouchInControlArea(e.rawX.toInt(), e.rawY.toInt())) {
                    bumpControls()
                    return true
                }
                dismissResumePrompt()
                val w = touch.width
                val step = Repo.store.seekStepSec
                when {
                    e.x < w * 0.33f -> seekRelative(-step)
                    e.x > w * 0.67f -> seekRelative(step)
                    else -> { togglePause(); showHint(if (paused) "▶" else "❚❚") }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (drag != Drag.NONE || paused || touchStartedInControls) return
                longPressActive = true
                speedBeforeLongPress = speed
                mpv?.setPropertyDouble("speed", 2.0)
                showHint("2× 快进中", sticky = true)
            }
        })

        touch.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isTouchInControlArea(ev.rawX.toInt(), ev.rawY.toInt())) {
                        drag = Drag.NONE
                        return@setOnTouchListener false
                    }
                    drag = Drag.NONE
                    startX = ev.x; startY = ev.y
                    startPos = posMs
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val b = window.attributes.screenBrightness
                    startBrightness = if (b < 0) 0.5f else b
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTouchInControlArea(ev.rawX.toInt(), ev.rawY.toInt()) && drag == Drag.NONE) return@setOnTouchListener false
                    if (longPressActive) return@setOnTouchListener true
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (drag == Drag.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        drag = if (abs(dx) > abs(dy)) Drag.SEEK
                        else if (startX < touch.width / 2f) Drag.BRIGHTNESS else Drag.VOLUME
                    }
                    when (drag) {
                        Drag.SEEK -> {
                            val dur = durMs.takeIf { it > 0 } ?: 0L
                            val delta = (dx / touch.width * min(dur, 600_000L).coerceAtLeast(90_000L)).toLong()
                            targetPos = (startPos + delta).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
                            val sign = if (delta >= 0) "+" else "-"
                            showHint("$sign${fmt(abs(delta))}\n${fmt(targetPos)} / ${fmt(dur)}", sticky = true)
                        }
                        Drag.BRIGHTNESS -> {
                            val v = (startBrightness - dy / touch.height).coerceIn(0.01f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = v }
                            showHint("☀ 亮度 ${(v * 100).toInt()}%", sticky = true)
                        }
                        Drag.VOLUME -> {
                            val v = (startVolume - dy / touch.height * maxVol * 1.2f).toInt().coerceIn(0, maxVol)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            showHint("🔊 音量 ${v * 100 / max(1, maxVol)}%", sticky = true)
                        }
                        Drag.NONE -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressActive) {
                        longPressActive = false
                        mpv?.setPropertyDouble("speed", speedBeforeLongPress)
                    }
                    if (drag == Drag.SEEK) {
                        dismissResumePrompt()
                        seekTo(targetPos)
                    }
                    drag = Drag.NONE
                    handler.postDelayed(hideHint, 400)
                }
            }
            true
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- PiP & lifecycle ----------------

    private fun computePipAspectRatio(): Rational {
        val w = mpv?.getPropertyInt("video-params/dw") ?: 0
        val h = mpv?.getPropertyInt("video-params/dh") ?: 0
        val ratio = if (w > 0 && h > 0) Rational(w, h) else Rational(16, 9)
        return when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 0.42f -> Rational(42, 100)
            else -> ratio
        }
    }

    private fun computePipSourceRect(): Rect {
        if (!::surface.isInitialized) return Rect()
        val sRect = Rect()
        if (!surface.getGlobalVisibleRect(sRect) || sRect.width() <= 0 || sRect.height() <= 0) {
            return sRect
        }
        if (aspectMode != 0) {
            return sRect
        }
        val w = mpv?.getPropertyInt("video-params/dw") ?: 0
        val h = mpv?.getPropertyInt("video-params/dh") ?: 0
        if (w > 0 && h > 0) {
            val sw = sRect.width()
            val sh = sRect.height()
            val videoAspect = w.toFloat() / h
            val surfaceAspect = sw.toFloat() / sh
            val (rw, rh) = if (videoAspect > surfaceAspect) {
                sw to (sw / videoAspect).toInt().coerceAtLeast(1)
            } else {
                (sh * videoAspect).toInt().coerceAtLeast(1) to sh
            }
            val left = sRect.left + (sw - rw) / 2
            val top = sRect.top + (sh - rh) / 2
            return Rect(left, top, left + rw, top + rh)
        }
        return sRect
    }

    private fun buildPipParams(forAutoEnter: Boolean = false): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(computePipAspectRatio())

        val sourceRect = computePipSourceRect()
        if (sourceRect.width() > 0 && sourceRect.height() > 0) {
            builder.setSourceRectHint(sourceRect)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
            val canAutoEnter = forAutoEnter || (mpv != null && !paused && fileLoaded && !switching && !isEnteringPip && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode))
            builder.setAutoEnterEnabled(canAutoEnter)
        }
        return builder.build()
    }

    private fun updatePipParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode && !force) {
            return
        }
        val params = buildPipParams() ?: return
        try {
            setPictureInPictureParams(params)
        } catch (_: Exception) {}
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mpv == null) return

        handler.removeCallbacks(hideControlsRunnable)
        controls.visibility = View.GONE
        if (::lockBtn.isInitialized) lockBtn.visibility = View.GONE
        hintView.visibility = View.GONE
        dismissResumePrompt()

        val params = buildPipParams(forAutoEnter = true) ?: return
        isEnteringPip = true

        val entered = try {
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            false
        }

        if (!entered) {
            isEnteringPip = false
            controls.visibility = View.VISIBLE
            if (::lockBtn.isInitialized) lockBtn.visibility = View.VISIBLE
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (mpv != null && !paused && fileLoaded && !switching && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode)) enterPip()
        }
    }

    private fun stretchMpvRow() {
        val row = findViewById<View>(R.id.mpv_prev)?.parent as? android.view.ViewGroup ?: return
        PlayerOrientation.stretchRow(this, row, findViewById<View>(R.id.mpv_subtitle)?.parent as? View)
    }
    private val mpvTransportIds = intArrayOf(R.id.mpv_prev, R.id.mpv_rew, R.id.mpv_play_pause, R.id.mpv_ffwd, R.id.mpv_next)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isEnteringPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) {
            return
        }
        updateRotateBtnPosition()
        PlayerOrientation.compactButtons(this, window.decorView, mpvTransportIds); stretchMpvRow()
        updateSpeedLabel()
        hideSystemBars()
        updatePipParams()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isEnteringPip = false
        if (isInPictureInPictureMode) {
            handler.removeCallbacks(hideControlsRunnable)
            controls.visibility = View.GONE
            if (::lockBtn.isInitialized) lockBtn.visibility = View.GONE
            hintView.visibility = View.GONE
            dismissResumePrompt()
        } else {
            PlayerOrientation.apply(this)
            hideSystemBars()
            updateRotateBtnPosition()
            PlayerOrientation.compactButtons(this, window.decorView, mpvTransportIds)
            stretchMpvRow()
            hideControls()
            surface.post { updatePipParams(force = true) }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(noisyReceiver) } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        if (mpv != null && !switching) {
            saveCurrent(stopped = true)
            mpv?.setPropertyBoolean("pause", true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AbLoopDialog.dismiss()
        ScreenLock.release(this)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        mpv?.let { m ->
            if (!switching) saveCurrent(stopped = true)
            m.removeObserver(this)
            runCatching { m.destroy() }
        }
        mpv = null
        super.onDestroy()
    }

    // ---------------- MediaSession & Key events ----------------

    private fun initMediaSession() {
        val session = MediaSession(this, "FanzhouMpvSession_${java.util.UUID.randomUUID()}")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                mpv?.setPropertyBoolean("pause", false)
            }
            override fun onPause() {
                mpv?.setPropertyBoolean("pause", true)
            }
            override fun onSkipToNext() {
                if (currentIndex + 1 < queue.size) playIndex(currentIndex + 1)
            }
            override fun onSkipToPrevious() {
                if (currentIndex > 0) playIndex(currentIndex - 1)
            }
            override fun onSeekTo(pos: Long) {
                seekTo(pos)
            }
            override fun onFastForward() {
                seekRelative(Repo.store.seekStepSec)
            }
            override fun onRewind() {
                seekRelative(-Repo.store.seekStepSec)
            }
            override fun onStop() {
                saveCurrent(stopped = true)
                finish()
            }
        })
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.isActive = true
        mediaSession = session
    }

    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        val state = when {
            buffering -> PlaybackState.STATE_BUFFERING
            paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val stateBuilder = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, posMs.coerceAtLeast(0), speed.toFloat())
        session.setPlaybackState(stateBuilder.build())
    }

    private fun updateMediaSessionMetadata(p: PlayItem) {
        val session = mediaSession ?: return
        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, p.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, p.subtitle ?: "饭舟")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs.coerceAtLeast(0))
        session.setMetadata(metaBuilder.build())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePause()
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                mpv?.setPropertyBoolean("pause", false)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                mpv?.setPropertyBoolean("pause", true)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelative(Repo.store.seekStepSec)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelative(-Repo.store.seekStepSec)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (currentIndex + 1 < queue.size) playIndex(currentIndex + 1)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (currentIndex > 0) playIndex(currentIndex - 1)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (controls.visibility != View.VISIBLE) {
                    showControls()
                } else {
                    togglePause()
                    bumpControls()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekRelative(-Repo.store.seekStepSec)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekRelative(Repo.store.seekStepSec)
                bumpControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                showSettingsMenu(controls)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
