package com.kevin.fanzhou.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import com.kevin.fanzhou.cloud.Resolved
import com.kevin.fanzhou.data.PlayItem
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
    private lateinit var touch: View
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var queue: MutableList<PlayItem> = mutableListOf()
    private var currentIndex = -1
    private val originals = HashMap<Int, PlayItem>()
    private val qualityLabel = HashMap<Int, String>()
    /** Cloud quality ladder (best first) per queue index, for automatic step-down on failure. */
    private val qualityLadder = HashMap<Int, List<Pair<String, Resolved>>>()
    /** Next ladder step to try per queue index (0 = best transcoded rendition). */
    private val qualityStep = HashMap<Int, Int>()
    private val ladderBusy = HashSet<Int>()
    private var coreForced = false
    private var switching = false

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

    private val posMs get() = (posSec * 1000).toLong()
    private val durMs get() = (durSec * 1000).toLong()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOrientation.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        coreForced = intent?.getBooleanExtra(PlayerCore.EXTRA_FORCED, false) == true
        hideSystemBars()
        setContentView(R.layout.mpv_player)
        bindViews()
        PlayerOrientation.compactButtons(this, window.decorView, mpvTransportIds); stretchMpvRow()
        queue = PlayQueue.items.toMutableList()
        intent?.data?.let { d -> queue = mutableListOf(PlayItem(url = d.toString(), title = d.lastPathSegment ?: "视频")); PlayQueue.set(queue, 0) }
        if (queue.isEmpty()) { finish(); return }
        if (!initMpv()) {
            toast("MPV 内核加载失败，已改用 ExoPlayer")
            PlayerCore.launch(this, mpv = false, forced = true)
            finish(); return
        }
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
        saveCurrent(stopped = true)
        originals.clear(); qualityLabel.clear()
        qualityLadder.clear(); qualityStep.clear(); ladderBusy.clear()
        queue = PlayQueue.items.toMutableList()
        if (queue.isEmpty()) { finish(); return }
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
        m.setOptionString("cache", "yes")
        m.setOptionString("demuxer-max-bytes", "${96 * 1024 * 1024}")
        m.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
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
        m.setOptionString("sub-border-size", "2.5")
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
        updateSpeedLabel()
        if (currentIndex >= 0 && currentIndex != i) saveCurrent(stopped = true)
        currentIndex = i
        PlayQueue.index = i
        val p = queue[i]
        titleView.text = listOfNotNull(p.title, p.subtitle).joinToString("  ·  ")
        posSec = p.startMs / 1000.0
        durSec = 0.0
        load(p, p.startMs)
        reportStart(p)
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
    }

    private fun onLoadFailed() {
        loading.visibility = View.GONE
        if (switching) return
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val cf = orig.cloudFile
        val sid = orig.sourceId
        if (cf != null && sid != null && Repo.store.autoQualityFallback) {
            advanceCloudLadder(idx, orig, "直连播放失败")
            return
        }
        // Last resort: server (Jellyfin/Emby) transcoding, still inside MPV (once per item).
        val jfId = orig.jfItemId
        if (jfId != null && sid != null && qualityStep[idx] == null) {
            val client = Repo.jellyfin(sid)
            if (client != null) {
                qualityStep[idx] = 1
                toast("直连播放失败，已切换为服务器转码")
                replaceCurrent(orig.copy(url = client.transcodeUrl(jfId, orig.jfMediaSourceId), headers = emptyMap()), "转码 8Mbps")
                return
            }
        }
        toast("MPV 无法播放此文件")
        showControls()
    }

    /** Step down the cloud transcoded-quality ladder (best first) until one loads. */
    private fun advanceCloudLadder(idx: Int, orig: PlayItem, reason: String) {
        if (!Repo.store.autoQualityFallback) return
        val cf = orig.cloudFile ?: return
        val sid = orig.sourceId ?: return
        if (!ladderBusy.add(idx)) return
        lifecycleScope.launch {
            try {
                val ladder = qualityLadder[idx] ?: run {
                    val qs = runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    val list = if (qs.isNotEmpty()) qs else
                        runCatching { CloudDrive.get(sid)?.resolveTranscode(cf) }.getOrNull()
                            ?.let { listOf("转码" to it) }.orEmpty()
                    qualityLadder[idx] = list
                    // If the user had manually picked a rendition, continue below it.
                    val cur = qualityLabel[idx]
                    qualityStep[idx] = if (cur == null) 0
                        else (list.indexOfFirst { it.first == cur } + 1).coerceAtLeast(0)
                    list
                }
                val step = qualityStep[idx] ?: 0
                if (step >= ladder.size || idx != currentIndex) {
                    if (step >= ladder.size) toast("$reason，且没有更低的画质可用了")
                    showControls(); return@launch
                }
                val (label, r) = ladder[step]
                qualityStep[idx] = step + 1
                toast("$reason，已自动切换到 $label")
                replaceCurrent(orig.copy(url = r.url, headers = r.headers, mime = r.mime, cloudFile = cf, subtitles = orig.subtitles), label)
            } finally {
                ladderBusy.remove(idx)
            }
        }
    }

    // ---------------- stall watchdog ----------------

    private var stallLastPos = -1.0
    private var stallSinceMs = 0L

    /** Cloud playback that stays in buffering without progress for 25s counts as failed. */
    private fun checkStall() {
        val idx = currentIndex
        val orig = queue.getOrNull(idx)?.let { originals[idx] ?: it } ?: return
        if (orig.cloudFile == null || paused || !fileLoaded) { stallSinceMs = 0; return }
        if (!buffering || posSec != stallLastPos) {
            stallLastPos = posSec
            stallSinceMs = System.currentTimeMillis()
            return
        }
        if (stallSinceMs > 0 && System.currentTimeMillis() - stallSinceMs > 25_000) {
            stallSinceMs = 0
            advanceCloudLadder(idx, orig, "播放卡顿")
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

    @kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun saveCurrent(stopped: Boolean, finished: Boolean = false) {
        val idx = currentIndex
        val p = (originals[idx] ?: queue.getOrNull(idx)) ?: return
        val pos = if (finished) durMs else posMs
        val dur = durMs
        val done = finished || (dur > 0 && pos > dur * 0.95)
        if (pos <= 0 && !done) return
        p.resumeKey?.let { Repo.store.savePosition(it, if (done) 0 else pos) }
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

    private val uiTick = object : Runnable {
        override fun run() {
            checkStall()
            if (!scrubbing) {
                timeBar.setDuration(durMs.coerceAtLeast(0))
                timeBar.setPosition(posMs.coerceAtLeast(0))
                timeBar.setBufferedPosition(((posSec + max(0.0, cacheSec - posSec)) * 1000).toLong())
                posView.text = fmt(posMs)
                durView.text = fmt(durMs)
            }
            handler.postDelayed(this, 500)
        }
    }

    // ---------------- controls ----------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

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
        touch = findViewById(R.id.mpv_touch)
        hintView.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x99000000.toInt()) }
        topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))

        fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick(); bumpControls() }
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
        lateinit var rotateBtn: ImageButton
        rotateBtn = iconBtn(R.drawable.ic_rotate, "屏幕方向") { PlayerOrientation.showMenu(this, rotateBtn) { showHint(it) } }
        topBar.addView(rotateBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_core, "切换到 ExoPlayer 内核") { switchToExo() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_hd, "画质") { showQualityMenu() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_aspect, "画面比例") { cycleAspect() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            topBar.addView(iconBtn(R.drawable.ic_pip, "画中画") { enterPip() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        }

        val step = Repo.store.seekStepSec
        findViewById<View>(R.id.mpv_prev).setOnClickListener { if (currentIndex > 0) playIndex(currentIndex - 1) else seekTo(0); bumpControls() }
        findViewById<View>(R.id.mpv_next).setOnClickListener { if (currentIndex + 1 < queue.size) playIndex(currentIndex + 1); bumpControls() }
        findViewById<View>(R.id.mpv_rew).setOnClickListener { seekRelative(-step); bumpControls() }
        findViewById<View>(R.id.mpv_ffwd).setOnClickListener { seekRelative(step); bumpControls() }
        playPause.setOnClickListener { togglePause(); bumpControls() }
        findViewById<View>(R.id.mpv_audio).setOnClickListener { showTrackMenu("audio"); bumpControls() }
        findViewById<View>(R.id.mpv_episodes).setOnClickListener { v -> showEpisodeMenu(v); bumpControls() }
        findViewById<View>(R.id.mpv_subtitle).setOnClickListener { showTrackMenu("sub"); bumpControls() }
        findViewById<View>(R.id.mpv_settings).setOnClickListener { showSettingsMenu(); bumpControls() }
        findViewById<View>(R.id.mpv_speed)?.setOnClickListener { showSpeedPopup(it); bumpControls() }
        updateSpeedLabel()
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubbing = true; handler.removeCallbacks(hideControlsRunnable) }
            override fun onScrubMove(timeBar: TimeBar, position: Long) { posView.text = fmt(position) }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) seekTo(position)
                bumpControls()
            }
        })
        attachGestures()
    }

    private fun updatePlayPause() {
        playPause.setImageResource(if (paused) androidx.media3.ui.R.drawable.exo_styled_controls_play else androidx.media3.ui.R.drawable.exo_styled_controls_pause)
        playPause.contentDescription = if (paused) "播放" else "暂停"
    }

    private val hideControlsRunnable = Runnable { hideControls() }
    private fun showControls() {
        controls.visibility = View.VISIBLE
        bumpControls()
    }
    private fun bumpControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!paused) handler.postDelayed(hideControlsRunnable, 5000)
    }
    private fun hideControls() {
        controls.visibility = View.GONE
        hideSystemBars()
    }

    private fun togglePause() {
        val m = mpv ?: return
        m.setPropertyBoolean("pause", !paused)
    }

    private fun seekTo(ms: Long) {
        mpv?.command(arrayOf("seek", "%.3f".format(java.util.Locale.US, ms / 1000.0), "absolute"))
        posSec = ms / 1000.0
    }

    private fun seekRelative(sec: Int) {
        mpv?.command(arrayOf("seek", sec.toString(), "relative"))
        showHint(if (sec < 0) "⏪ ${-sec}秒" else "⏩ ${sec}秒")
    }

    private fun setSpeed(s: Double) {
        speed = s
        mpv?.setPropertyDouble("speed", s)
        updateSpeedLabel()
    }

    /** Bottom-bar 倍速 label + 音轨/选集 buttons: landscape only (portrait has no spare width). */
    private fun updateSpeedLabel() {
        val portrait = PlayerOrientation.isPortrait(this)
        findViewById<View>(R.id.mpv_audio)?.visibility = if (portrait) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mpv_episodes)?.visibility =
            if (portrait || queue.size <= 1) View.GONE else View.VISIBLE
        val t = findViewById<TextView>(R.id.mpv_speed) ?: return
        t.visibility = if (portrait) View.GONE else View.VISIBLE
        t.text = fmtSpeed(speed) + "x"
    }

    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % aspectNames.size
        applyAspect()
        showHint(aspectNames[aspectMode])
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

    private fun showTrackMenu(type: String) {
        val list = tracks(type)
        val isSub = type == "sub"
        if (list.isEmpty()) { toast(if (isSub) "没有字幕" else "没有可选音轨"); return }
        val labels = (if (isSub) listOf("关闭字幕") else emptyList()) + list.map { it.label }
        val sel = list.indexOfFirst { it.selected }.let { if (isSub) (if (it < 0) 0 else it + 1) else it }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (isSub) "字幕" else "音轨")
            .setSingleChoiceItems(labels.toTypedArray(), sel) { d, which ->
                d.dismiss()
                val prop = if (isSub) "sid" else "aid"
                if (isSub && which == 0) mpv?.setPropertyString(prop, "no")
                else mpv?.setPropertyString(prop, list[if (isSub) which - 1 else which].id.toString())
            }
            .show()
    }

    private fun showSettingsMenu() {
        val items = arrayOf("音轨", "倍速（当前 ${fmtSpeed(speed)}×）", "字幕延迟", if (Repo.store.mpvHwdec) "解码：硬解（点按切换为软解）" else "解码：软解（点按切换为硬解）")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTrackMenu("audio")
                    1 -> showSpeedMenu()
                    2 -> showSubDelayMenu()
                    3 -> {
                        Repo.store.mpvHwdec = !Repo.store.mpvHwdec
                        mpv?.setPropertyString("hwdec", if (Repo.store.mpvHwdec) "mediacodec-copy" else "no")
                        showHint(if (Repo.store.mpvHwdec) "硬件解码" else "软件解码")
                    }
                }
            }
            .show()
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

    private fun showQualityMenu() {
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val jfId = orig.jfItemId
        val cf = orig.cloudFile
        when {
            jfId != null -> {
                val client = Repo.jellyfin(orig.sourceId ?: return) ?: return
                val presets = listOf(
                    Triple("超清 1080P · 20Mbps", 20_000_000, 1920),
                    Triple("超清 1080P · 10Mbps", 10_000_000, 1920),
                    Triple("高清 720P · 4Mbps", 4_000_000, 1280),
                    Triple("标清 480P · 1.5Mbps", 1_500_000, 854),
                    Triple("流畅 360P · 0.7Mbps", 700_000, 640),
                )
                val opts = listOf<Pair<String, () -> PlayItem>>("原画" to { orig }) + presets.map { (label, br, w) ->
                    label to { orig.copy(url = client.transcodeUrl(jfId, orig.jfMediaSourceId, br, w), headers = emptyMap()) }
                }
                pickQuality(idx, opts)
            }
            cf != null -> {
                val sid = orig.sourceId ?: return
                showHint("正在获取画质…", sticky = true)
                lifecycleScope.launch {
                    val qs = runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    hintView.visibility = View.GONE
                    if (idx != currentIndex) return@launch
                    val opts = listOf<Pair<String, () -> PlayItem>>("原画" to { orig }) + qs.map { (label, r) ->
                        label to { orig.copy(url = r.url, headers = r.headers, mime = r.mime, cloudFile = null) }
                    }
                    if (opts.size == 1) toast("网盘暂时没有这个视频的转码版本，只有原画")
                    pickQuality(idx, opts)
                }
            }
            else -> toast("该来源只有原画")
        }
    }

    private fun pickQuality(idx: Int, opts: List<Pair<String, () -> PlayItem>>) {
        if (opts.size <= 1) return
        val cur = qualityLabel[idx] ?: "原画"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("画质")
            .setSingleChoiceItems(opts.map { it.first }.toTypedArray(), opts.indexOfFirst { it.first == cur }) { d, which ->
                d.dismiss()
                val (label, make) = opts[which]
                if (label == cur || idx != currentIndex) return@setSingleChoiceItems
                qualityLadder[idx]?.let { l -> qualityStep[idx] = (l.indexOfFirst { it.first == label } + 1).coerceAtLeast(0) }
                    ?: qualityStep.remove(idx)
                if (label == "原画") qualityStep[idx] = 0
                if (label == "原画") {
                    val o = originals.remove(idx) ?: return@setSingleChoiceItems
                    queue[idx] = o
                    qualityLabel.remove(idx)
                    load(o, posMs)
                } else replaceCurrent(make(), label)
                showHint("画质：$label")
            }
            .show()
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
        PlayerCore.launch(this, mpv = false, forced = true)
        finish()
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

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controls.visibility == View.VISIBLE) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
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
                if (drag != Drag.NONE || paused) return
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
                    drag = Drag.NONE
                    startX = ev.x; startY = ev.y
                    startPos = posMs
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val b = window.attributes.screenBrightness
                    startBrightness = if (b < 0) 0.5f else b
                }
                MotionEvent.ACTION_MOVE -> {
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
                    if (drag == Drag.SEEK) seekTo(targetPos)
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

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val w = mpv?.getPropertyInt("video-params/dw") ?: 0
        val h = mpv?.getPropertyInt("video-params/dh") ?: 0
        val ratio = if (w > 0 && h > 0) Rational(w, h) else Rational(16, 9)
        val clamped = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 0.42f -> Rational(42, 100)
            else -> ratio
        }
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(clamped).build()) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (mpv != null && !paused && fileLoaded && !switching) enterPip()
    }

    private fun stretchMpvRow() {
        val row = findViewById<View>(R.id.mpv_prev)?.parent as? android.view.ViewGroup ?: return
        PlayerOrientation.stretchRow(this, row, findViewById<View>(R.id.mpv_subtitle)?.parent as? View)
    }
    private val mpvTransportIds = intArrayOf(R.id.mpv_prev, R.id.mpv_rew, R.id.mpv_play_pause, R.id.mpv_ffwd, R.id.mpv_next)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        PlayerOrientation.compactButtons(this, window.decorView, mpvTransportIds); stretchMpvRow()
        updateSpeedLabel()
        hideSystemBars()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) { handler.removeCallbacks(hideControlsRunnable); controls.visibility = View.GONE }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        if (mpv != null && !switching) {
            saveCurrent(stopped = true)
            mpv?.setPropertyBoolean("pause", true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mpv?.let { m ->
            if (!switching) saveCurrent(stopped = true)
            m.removeObserver(this)
            runCatching { m.destroy() }
        }
        mpv = null
        super.onDestroy()
    }
}
