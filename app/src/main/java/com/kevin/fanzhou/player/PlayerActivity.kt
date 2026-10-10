package com.kevin.fanzhou.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kevin.fanzhou.R
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.Resolved
import com.kevin.fanzhou.data.PlayItem
import com.kevin.fanzhou.data.PlayQueue
import com.kevin.fanzhou.data.Repo
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var queue: MutableList<PlayItem> = mutableListOf()
    private var currentIndex = -1
    private var transcodedIds = HashSet<String>()
    /** Original (direct-play) item per queue index, kept once a quality switch replaces it. */
    private val originals = HashMap<Int, PlayItem>()
    private val qualityLabel = HashMap<Int, String>()
    /** Cloud quality ladder (best first) per queue index, for automatic step-down on failure. */
    private val qualityLadder = HashMap<Int, List<Pair<String, Resolved>>>()
    /** Next ladder step to try per queue index (0 = best transcoded rendition). */
    private val qualityStep = HashMap<Int, Int>()
    private val ladderBusy = HashSet<Int>()
    private var resizeMode = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val resizeNames = arrayOf("适应", "裁剪填充", "拉伸")
    private var longPressSpeedActive = false
    private var speedBeforeLongPress = 1f

    companion object {
        /** Opens the queue in the configured core (ExoPlayer or MPV). */
        fun start(context: Context, items: List<PlayItem>, index: Int) = PlayerCore.start(context, items, index)
    }

    /** True when the user explicitly chose ExoPlayer for this video (no automatic switch to MPV). */
    private var coreForced = false
    private var switchedToMpv = false
    private val autoMpv get() = !coreForced && Repo.store.playerCore == com.kevin.fanzhou.data.CORE_AUTO && PlayerCore.mpvAvailable

    private fun queueForHandoff(pos: Long): List<PlayItem> = queue.mapIndexed { i, p ->
        val orig = originals[i] ?: p
        if (i == currentIndex) orig.copy(startMs = pos) else orig
    }

    /** Hands the current queue and position over to the MPV core. */
    private fun switchToMpv(reason: String?, forced: Boolean): Boolean {
        if (switchedToMpv || currentIndex < 0) return false
        if (!PlayerCore.mpvAvailable) { toast("MPV 内核在此设备上不可用"); return false }
        switchedToMpv = true
        val pos = if (::player.isInitialized) player.currentPosition.coerceAtLeast(0) else 0
        reportStopCurrent()
        PlayQueue.set(queueForHandoff(pos), currentIndex)
        reason?.let { toast("$it，已切换到 MPV 内核") }
        PlayerCore.launch(this, mpv = true, forced = forced)
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOrientation.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        coreForced = intent?.getBooleanExtra(PlayerCore.EXTRA_FORCED, false) == true
        if (!coreForced && intent?.data != null && Repo.store.playerCore == com.kevin.fanzhou.data.CORE_MPV && PlayerCore.mpvAvailable) {
            // Opened from another app while MPV is the chosen core.
            loadQueueFromIntent(intent)
            PlayerCore.launch(this, mpv = true, forced = true)
            finish()
            return
        }
        hideSystemBars()
        buildUi()
        loadQueueFromIntent(intent)
        initPlayer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        coreForced = intent.getBooleanExtra(PlayerCore.EXTRA_FORCED, false)
        switchedToMpv = false
        transcodedIds.clear(); originals.clear(); qualityLabel.clear()
        qualityLadder.clear(); qualityStep.clear(); ladderBusy.clear()
        reportStopCurrent()
        loadQueueFromIntent(intent)
        startQueue()
    }

    private fun loadQueueFromIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null) {
            queue = mutableListOf(PlayItem(url = data.toString(), title = data.lastPathSegment ?: "视频"))
            PlayQueue.set(queue, 0)
        } else {
            queue = PlayQueue.items.toMutableList()
        }
    }

    // ---------------- UI ----------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5000
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowSubtitleButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            keepScreenOn = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                topBar.visibility = vis
                if (vis != View.VISIBLE) hideSystemBars()
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        }
        fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
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
        topBar.addView(iconBtn(R.drawable.ic_core, "切换到 MPV 内核") { switchToMpv(null, forced = true); showHint("MPV 内核") }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_hd, "画质") { showQualityMenu() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_aspect, "画面比例") { cycleResize() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            topBar.addView(iconBtn(R.drawable.ic_pip, "画中画") { enterPip() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        fitExoBottomBar()
        extendSpeedMenu()
        playerView.findViewById<TextView>(R.id.fz_speed)?.setOnClickListener { showSpeedPicker(it) }
        playerView.findViewById<View>(R.id.fz_audio)?.setOnClickListener { showAudioTrackMenu() }
        playerView.findViewById<View>(R.id.fz_episodes)?.setOnClickListener { v -> showEpisodeMenu(v) }
        updateSpeedLabel()

        hintView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x99000000.toInt()) }
            visibility = View.GONE
        }
        root.addView(hintView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        attachGestures()
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

    private fun cycleResize() {
        resizeMode = (resizeMode + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeMode]
        showHint(resizeNames[resizeMode])
    }

    // ---------------- Gestures ----------------

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
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = playerView.width
                val step = Repo.store.seekStepSec * 1000L
                when {
                    e.x < w * 0.33f -> { player.seekTo(max(0, player.currentPosition - step)); showHint("⏪ ${Repo.store.seekStepSec}秒") }
                    e.x > w * 0.67f -> { player.seekTo(player.currentPosition + step); showHint("⏩ ${Repo.store.seekStepSec}秒") }
                    else -> { if (player.isPlaying) player.pause() else player.play(); showHint(if (player.isPlaying) "▶" else "❚❚") }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (drag != Drag.NONE || !player.isPlaying) return
                longPressSpeedActive = true
                speedBeforeLongPress = player.playbackParameters.speed
                player.playbackParameters = PlaybackParameters(2f)
                showHint("2× 快进中", sticky = true)
            }
        })

        playerView.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag = Drag.NONE
                    startX = ev.x; startY = ev.y
                    startPos = player.currentPosition
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val b = window.attributes.screenBrightness
                    startBrightness = if (b < 0) 0.5f else b
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressSpeedActive) return@setOnTouchListener true
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (drag == Drag.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        drag = if (abs(dx) > abs(dy)) Drag.SEEK
                        else if (startX < playerView.width / 2f) Drag.BRIGHTNESS else Drag.VOLUME
                    }
                    when (drag) {
                        Drag.SEEK -> {
                            val dur = player.duration.takeIf { it > 0 } ?: 0L
                            val delta = (dx / playerView.width * min(dur, 600_000L).coerceAtLeast(90_000L)).toLong()
                            targetPos = (startPos + delta).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
                            val sign = if (delta >= 0) "+" else "-"
                            showHint("$sign${fmt(abs(delta))}\n${fmt(targetPos)} / ${fmt(dur)}", sticky = true)
                        }
                        Drag.BRIGHTNESS -> {
                            val v = (startBrightness - dy / playerView.height).coerceIn(0.01f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = v }
                            showHint("☀ 亮度 ${(v * 100).toInt()}%", sticky = true)
                        }
                        Drag.VOLUME -> {
                            val v = (startVolume - dy / playerView.height * maxVol * 1.2f).toInt().coerceIn(0, maxVol)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            showHint("🔊 音量 ${v * 100 / max(1, maxVol)}%", sticky = true)
                        }
                        Drag.NONE -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressSpeedActive) {
                        longPressSpeedActive = false
                        player.playbackParameters = PlaybackParameters(speedBeforeLongPress)
                    }
                    if (drag == Drag.SEEK) player.seekTo(targetPos)
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

    // ---------------- Player ----------------

    private fun initPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        player = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(Repo.store.seekStepSec * 1000L)
            .setSeekForwardIncrementMs(Repo.store.seekStepSec * 1000L)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("zh", "chi", "zho", "cmn", "ja", "en")
            .setPreferredTextLanguages("zh", "chi", "zho", "chs", "cht")
            .build()
        playerView.player = player
        player.addListener(listener)
        startQueue()
        handler.postDelayed(progressTick, 10_000)
        handler.postDelayed(stallTick, 5_000)
    }

    private fun mediaSourceFactory(headers: Map<String, String>): DefaultMediaSourceFactory {
        val ds = OkHttpDataSource.Factory(Repo.http).setDefaultRequestProperties(headers)
        val dsf = androidx.media3.datasource.DefaultDataSource.Factory(this, ds)
        // cloud://<source>/<file> URIs are resolved to fresh signed links right before each request.
        val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(dsf) { spec ->
            val ref = CloudDrive.fromUri(spec.uri)
            if (ref == null) spec else {
                val r = kotlinx.coroutines.runBlocking { CloudDrive.resolveCached(ref.first, ref.second) }
                spec.withUri(Uri.parse(r.url)).withAdditionalHeaders(r.headers)
            }
        }
        // smb:// is read through smbj; everything else goes through the resolving http chain.
        return DefaultMediaSourceFactory(SchemeRoutingDataSource.Factory(resolving))
    }

    private fun toMediaItem(p: PlayItem, i: Int): MediaItem {
        val subs = p.subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(s.mime)
                .setLabel(s.label)
                .setLanguage(s.lang)
                .build()
        }
        return MediaItem.Builder()
            .setUri(p.url)
            .setMimeType(p.mime)
            .setMediaId(i.toString())
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(p.title).setSubtitle(p.subtitle).build())
            .build()
    }

    private fun startQueue() {
        if (queue.isEmpty()) { finish(); return }
        val start = PlayQueue.index.coerceIn(0, queue.size - 1)
        val headers = queue.firstOrNull()?.headers ?: emptyMap()
        player.setMediaSources(queue.mapIndexed { i, p -> mediaSourceFactory(p.headers.ifEmpty { headers }).createMediaSource(toMediaItem(p, i)) }, start, queue[start].startMs)
        player.playbackParameters = PlaybackParameters(Repo.store.defaultSpeed)
        player.pauseAtEndOfMediaItems = !Repo.store.autoPlayNext
        player.prepare()
        player.play()
        currentIndex = start
        onItemStarted()
    }

    private fun onItemStarted() {
        val p = queue.getOrNull(currentIndex) ?: return
        updateSpeedLabel()
        titleView.text = listOfNotNull(p.title, p.subtitle).joinToString("  ·  ")
        val id = p.jfItemId ?: return
        val sid = p.sourceId ?: return
        lifecycleScope.launch { Repo.jellyfin(sid)?.reportStart(id, p.jfMediaSourceId, player.currentPosition) }
    }

    private fun saveProgress(index: Int, posMs: Long, durMs: Long, stopped: Boolean) {
        val p = queue.getOrNull(index) ?: return
        val finished = durMs > 0 && posMs > durMs * 0.95
        p.resumeKey?.let { Repo.store.savePosition(it, if (finished) 0 else posMs) }
        val id = p.jfItemId ?: return
        val sid = p.sourceId ?: return
        val client = Repo.jellyfin(sid) ?: return
        val paused = !player.isPlaying
        // Use the application scope so the stop report survives activity teardown.
        kotlinx.coroutines.GlobalScope.launch {
            if (stopped) client.reportStop(id, p.jfMediaSourceId, posMs)
            else client.reportProgress(id, p.jfMediaSourceId, posMs, paused)
        }
    }

    private fun reportStopCurrent() {
        if (!::player.isInitialized || currentIndex < 0) return
        saveProgress(currentIndex, player.currentPosition, player.duration, stopped = true)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying && currentIndex >= 0)
                saveProgress(currentIndex, player.currentPosition, player.duration, stopped = false)
            handler.postDelayed(this, 10_000)
        }
    }

    private var lastPos = 0L
    private var lastDur = 0L

    private val speedChoices = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)
    private fun fmtSpeed(s: Float) = "%.2f".format(s).trimEnd('0').trimEnd('.') + "x"

    /** Bottom-bar 倍速 label + 音轨/选集 buttons: landscape only (portrait has no spare width). */
    private fun updateSpeedLabel() {
        if (!::playerView.isInitialized) return
        val portrait = PlayerOrientation.isPortrait(this)
        playerView.findViewById<View>(R.id.fz_audio)?.visibility = if (portrait) View.GONE else View.VISIBLE
        playerView.findViewById<View>(R.id.fz_episodes)?.visibility =
            if (portrait || queue.size <= 1) View.GONE else View.VISIBLE
        val t = playerView.findViewById<TextView>(R.id.fz_speed) ?: return
        t.visibility = if (portrait) View.GONE else View.VISIBLE
        if (::player.isInitialized) t.text = fmtSpeed(player.playbackParameters.speed)
    }

    /** Bottom-bar 选集 button: jump within the current queue; popup anchored at the button. */
    private fun showEpisodeMenu(anchor: View) {
        if (queue.size <= 1) { toast("当前只有一个视频"); return }
        val labels = queue.map { item -> listOfNotNull(item.title, item.subtitle).joinToString(" · ") }
        EpisodePopup.show(anchor, labels, currentIndex) { which ->
            if (which != currentIndex) {
                player.seekToDefaultPosition(which)
                player.play()
                showHint("第 ${which + 1} 集")
            }
        }
    }

    // ---------------- stall watchdog ----------------

    private var stallLastPos = -1L
    private var stallSince = 0L
    private val stallTick = object : Runnable {
        override fun run() {
            checkStall()
            handler.postDelayed(this, 5_000)
        }
    }

    /** Original/transcoded cloud playback that buffers without progress for 25s counts as failed. */
    private fun checkStall() {
        if (!::player.isInitialized || currentIndex < 0) return
        val p = queue.getOrNull(currentIndex) ?: return
        if (p.cloudFile == null || !player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) {
            stallSince = 0; return
        }
        val pos = player.currentPosition
        if (pos != stallLastPos) { stallLastPos = pos; stallSince = System.currentTimeMillis(); return }
        if (stallSince == 0L) { stallSince = System.currentTimeMillis(); return }
        if (System.currentTimeMillis() - stallSince > 25_000) {
            stallSince = 0; stallLastPos = -1
            if (!tryCloudTranscode(p, "播放卡顿")) toast("播放卡顿，且没有可切换的画质")
        }
    }

    /** Bottom-bar 音轨 button: audio tracks used to be only inside the settings (gear) dialog. */
    private fun showAudioTrackMenu() {
        if (!::player.isInitialized) return
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) choices.add(g to i)
        }
        if (choices.isEmpty()) { toast("没有可选音轨"); return }
        val labels = choices.map { (g, i) -> audioTrackLabel(g.getTrackFormat(i)) }
        val sel = choices.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("音轨")
            .setSingleChoiceItems(labels.toTypedArray(), sel) { d, which ->
                d.dismiss()
                val (g, i) = choices[which]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
                showHint("音轨：${labels[which]}")
            }
            .show()
    }

    private fun audioTrackLabel(f: androidx.media3.common.Format): String {
        val lang = f.language?.takeIf { it.isNotEmpty() }?.let { audioLangName(it) }
        val ch = if (f.channelCount != androidx.media3.common.Format.NO_VALUE && f.channelCount > 0) "${f.channelCount}声道" else null
        val br = if (f.averageBitrate != androidx.media3.common.Format.NO_VALUE && f.averageBitrate > 0) "${(f.averageBitrate + 500) / 1000}kbps" else null
        return listOfNotNull(f.label?.takeIf { it.isNotEmpty() }, lang, f.codecs?.substringBefore(',')?.uppercase(), ch, br)
            .distinct().joinToString(" · ").ifEmpty { "音轨" }
    }

    private fun audioLangName(l: String) = when (l.lowercase()) {
        "chi", "zho", "zh", "chs", "zh-hans", "cmn", "sc" -> "中文"
        "cht", "zh-hant", "tc" -> "繁体中文"
        "eng", "en" -> "英语"
        "jpn", "ja" -> "日语"
        "kor", "ko" -> "韩语"
        else -> l
    }

    private fun showSpeedPicker(anchor: View) {
        val cur = player.playbackParameters.speed
        SpeedPopup.show(anchor, speedChoices.map { fmtSpeed(it) }, speedChoices.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }) { w ->
            player.playbackParameters = PlaybackParameters(speedChoices[w])
            showHint(fmtSpeed(speedChoices[w]))
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { updateSpeedLabel() }
        override fun onEvents(p: Player, events: Player.Events) {
            if (p.duration > 0) { lastPos = p.currentPosition; lastDur = p.duration }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newIndex = mediaItem?.mediaId?.toIntOrNull() ?: return
            if (newIndex == currentIndex) return
            if (currentIndex >= 0) saveProgress(currentIndex, lastDur, lastDur, stopped = true)
            currentIndex = newIndex
            PlayQueue.index = newIndex
            val start = queue[newIndex].startMs
            if (start > 0 && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) player.seekTo(start)
            onItemStarted()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            val audioBad = audioGroups.isNotEmpty() && audioGroups.none { it.isSupported }
            val videoBad = videoGroups.isNotEmpty() && videoGroups.none { it.isSupported }
            if (audioBad || videoBad) {
                val reason = if (videoBad) "视频格式不支持" else "音频格式不支持"
                if (autoMpv && switchToMpv(reason, forced = false)) return
                if (!tryTranscode(reason)) toast(if (videoBad) "该视频的画面格式本机无法解码" else "该视频的音频格式本机无法解码")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = queue.getOrNull(currentIndex)
            // 自动: try MPV on the original file first; server/cloud transcoding stays the last resort.
            if (autoMpv && p != null && originals[currentIndex] == null && switchToMpv("ExoPlayer 无法播放", forced = false)) return
            if (!tryTranscode("直连播放失败")) toast("播放失败：${error.errorCodeName}")
        }
    }

    private fun replaceCurrent(newItem: PlayItem, pos: Long) {
        val idx = currentIndex
        originals.getOrPut(idx) { queue[idx] }
        queue[idx] = newItem
        val ms = mediaSourceFactory(newItem.headers).createMediaSource(toMediaItem(newItem, idx))
        player.removeMediaItem(idx)
        player.addMediaSource(idx, ms)
        player.seekTo(idx, pos)
        player.prepare()
        player.play()
    }

    /** Cloud drives: step down the transcoded-quality ladder (best first) until one plays. */
    private fun tryCloudTranscode(p: PlayItem, reason: String): Boolean {
        if (!Repo.store.autoQualityFallback) return false
        val f = p.cloudFile ?: return false
        val sid = p.sourceId ?: return false
        val idx = currentIndex
        if (idx < 0) return false
        if (!ladderBusy.add(idx)) return true // a step-down is already in flight
        val pos = player.currentPosition.coerceAtLeast(0)
        lifecycleScope.launch {
            try {
                val ladder = qualityLadder[idx] ?: run {
                    val qs = runCatching { CloudDrive.get(sid)?.qualities(f) }.getOrNull().orEmpty()
                    val list = if (qs.isNotEmpty()) qs else
                        runCatching { CloudDrive.get(sid)?.resolveTranscode(f) }.getOrNull()
                            ?.let { listOf("转码" to it) }.orEmpty()
                    qualityLadder[idx] = list
                    // If the user had manually picked a rendition, continue below it.
                    val cur = qualityLabel[idx]
                    qualityStep[idx] = if (cur == null) 0
                        else (list.indexOfFirst { it.first == cur } + 1).coerceAtLeast(0)
                    list
                }
                val step = qualityStep[idx] ?: 0
                if (step >= ladder.size) { toast("$reason，且没有更低的画质可用了"); return@launch }
                val (label, r) = ladder[step]
                qualityStep[idx] = step + 1
                qualityLabel[idx] = label
                originals.getOrPut(idx) { p }
                toast("$reason，已自动切换到 $label")
                if (idx == currentIndex) replaceCurrent(p.copy(url = r.url, headers = r.headers, mime = r.mime, startMs = pos), pos)
            } finally {
                ladderBusy.remove(idx)
            }
        }
        return true
    }

    /** For Jellyfin items, fall back to server-side transcoding (HLS) at the current position. */
    private fun tryTranscode(reason: String): Boolean {
        val p = queue.getOrNull(currentIndex) ?: return false
        if (p.cloudFile != null) return tryCloudTranscode(p, reason)
        val id = p.jfItemId ?: return false
        if (!transcodedIds.add(id)) return false
        val client = Repo.jellyfin(p.sourceId ?: return false) ?: return false
        val pos = player.currentPosition
        val newItem = p.copy(url = client.transcodeUrl(id, p.jfMediaSourceId), startMs = pos)
        originals.getOrPut(currentIndex) { p }
        qualityLabel[currentIndex] = "转码 8Mbps"
        queue[currentIndex] = newItem
        toast("$reason，已切换为服务器转码")
        val ms = mediaSourceFactory(emptyMap()).createMediaSource(toMediaItem(newItem, currentIndex))
        val idx = currentIndex
        player.removeMediaItem(idx)
        player.addMediaSource(idx, ms)
        player.seekTo(idx, pos)
        player.prepare()
        player.play()
        return true
    }

    // ---------------- Quality ----------------

    private fun showQualityMenu() {
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val cf = orig.cloudFile
        val jfId = orig.jfItemId
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
                val opts = listOf<Pair<String, (() -> PlayItem)>>("原画" to { orig }) + presets.map { (label, br, w) ->
                    label to { orig.copy(url = client.transcodeUrl(jfId, orig.jfMediaSourceId, br, w), headers = emptyMap(), mime = null) }
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
                    val opts = listOf<Pair<String, (() -> PlayItem)>>("原画" to { orig }) + qs.map { (label, r) ->
                        label to { orig.copy(url = r.url, headers = r.headers, mime = r.mime) }
                    }
                    if (opts.size == 1) toast("网盘暂时没有这个视频的转码版本，只有原画")
                    pickQuality(idx, opts)
                }
            }
            else -> toast("该来源只有原画")
        }
    }

    private fun pickQuality(idx: Int, opts: List<Pair<String, (() -> PlayItem)>>) {
        if (opts.size <= 1) return
        val cur = qualityLabel[idx] ?: "原画"
        val checked = opts.indexOfFirst { it.first == cur }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("画质")
            .setSingleChoiceItems(opts.map { it.first }.toTypedArray(), checked) { d, which ->
                d.dismiss()
                val (label, make) = opts[which]
                if (label == cur || idx != currentIndex) return@setSingleChoiceItems
                val pos = player.currentPosition
                val item = make().copy(startMs = pos)
                qualityLabel[idx] = label
                qualityLadder[idx]?.let { l -> qualityStep[idx] = (l.indexOfFirst { it.first == label } + 1).coerceAtLeast(0) }
                    ?: qualityStep.remove(idx)
                if (label == "原画") qualityStep[idx] = 0
                replaceCurrent(item, pos)
                if (label == "原画") originals.remove(idx)
                showHint("画质：$label")
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- PiP & lifecycle ----------------

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val fmt = player.videoFormat
        val ratio = if (fmt != null && fmt.width > 0 && fmt.height > 0) Rational(fmt.width, fmt.height) else Rational(16, 9)
        val clamped = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 0.42f -> Rational(42, 100)
            else -> ratio
        }
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(clamped).build()) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::player.isInitialized && player.isPlaying) enterPip()
    }

    private val exoTransportIds = intArrayOf(
        androidx.media3.ui.R.id.exo_prev, androidx.media3.ui.R.id.exo_rew_with_amount,
        androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_ffwd_with_amount,
        androidx.media3.ui.R.id.exo_next)

    /**
     * Media3 hides the whole bottom bar ("minimal mode") when exo_time + overflow button is wider
     * than the screen. Our transport buttons live inside exo_time, so in portrait shrink them,
     * the time text and paddings until it fits.
     */
    /** Media3's settings → 播放速度 list is hard-coded to 0.25x–2x; swap in our own list (adds 3x/4x/5x). */
    private fun extendSpeedMenu() {
        try {
            val f = playerView.javaClass.getDeclaredField("controller").apply { isAccessible = true }
            val ctl = f.get(playerView) ?: return
            val af = ctl.javaClass.getDeclaredField("playbackSpeedAdapter").apply { isAccessible = true }
            val adapter = af.get(ctl) ?: return
            val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)
            val texts = arrayOf("0.25x", "0.5x", "0.75x", "正常", "1.25x", "1.5x", "2x", "3x", "4x", "5x")
            adapter.javaClass.getDeclaredField("playbackSpeeds").apply { isAccessible = true }.set(adapter, speeds)
            adapter.javaClass.getDeclaredField("playbackSpeedTexts").apply { isAccessible = true }.set(adapter, texts)
            adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
        } catch (e: Exception) {
            android.util.Log.w("Fanzhou", "speed menu patch failed", e)
        }
    }

    /**
     * Media3 draws the "10" with gravity bottom + an 18dp bottom padding tuned for its own 52dp
     * button, so on our sizes the number drifted off the arrow circle. The circle's centre sits at
     * 52.9% of the icon height (path centre y=13 of 24, scaled 0.7 about 12), so centre the text
     * there and size it to the circle, recomputed whenever the button is resized.
     */
    private fun centerSkipAmount(t: TextView) {
        fun apply() {
            val h = t.height.takeIf { it > 0 } ?: return
            val top = (h * 0.058f).toInt()
            t.includeFontPadding = false
            t.gravity = Gravity.CENTER
            if (t.paddingTop != top || t.paddingBottom != 0 || t.paddingLeft != 0 || t.paddingRight != 0) t.setPadding(0, top, 0, 0)
            val px = (minOf(h, t.width.takeIf { it > 0 } ?: h) * 0.19f)
            if (kotlin.math.abs(t.textSize - px) > 0.5f) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        }
        if (t.getTag(R.id.fz_stretch_tag) == null) {
            t.setTag(R.id.fz_stretch_tag, true)
            t.addOnLayoutChangeListener { _, l, tp, r, b, ol, ot, or_, ob -> if (r - l != or_ - ol || b - tp != ob - ot) t.post { apply() } }
        }
        t.post { apply() }
    }

    private fun fitExoBottomBar() {
        if (!::playerView.isInitialized) return
        val portrait = PlayerOrientation.isPortrait(this)
        val btn = dp(if (portrait) 38 else 53)
        val pad = dp(if (portrait) 6 else 9)
        fun size(v: View?) {
            v ?: return
            val lp = v.layoutParams ?: return
            lp.width = btn; lp.height = btn; v.layoutParams = lp
        }
        for (id in intArrayOf(androidx.media3.ui.R.id.exo_prev, androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_next)) {
            playerView.findViewById<View>(id)?.let { size(it); it.setPadding(pad, pad, pad, pad) }
        }
        for (id in intArrayOf(androidx.media3.ui.R.id.exo_rew_with_amount, androidx.media3.ui.R.id.exo_ffwd_with_amount)) {
            val b = playerView.findViewById<View>(id) ?: continue
            size(b)
            // Only resize Media3's own FrameLayout wrapper; Media3 may place the button directly in
            // our fz_time row, and shrinking that row to one button's size clipped the whole bar.
            (b.parent as? FrameLayout)?.takeIf { it.id != R.id.fz_time }?.let { size(it) }
            (b as? TextView)?.let { centerSkipAmount(it) }
        }
        val ts = if (portrait) 12f else 14f
        for (id in intArrayOf(androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration)) {
            (playerView.findViewById<View>(id) as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, ts)
        }
        playerView.findViewById<View>(R.id.fz_time)?.let {
            val p = dp(if (portrait) 2 else 10); it.setPaddingRelative(p, 0, p, 0)
        }
        (playerView.findViewById<View>(R.id.fz_time) as? android.view.ViewGroup)?.let {
            PlayerOrientation.stretchRow(this, it, playerView.findViewById(androidx.media3.ui.R.id.exo_basic_controls))
        }
        playerView.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::playerView.isInitialized) fitExoBottomBar()
        updateSpeedLabel()
        hideSystemBars()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) { playerView.hideController(); topBar.visibility = View.GONE }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        if (::player.isInitialized) {
            reportStopCurrent()
            player.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) {
            reportStopCurrent()
            player.release()
        }
        super.onDestroy()
    }
}
