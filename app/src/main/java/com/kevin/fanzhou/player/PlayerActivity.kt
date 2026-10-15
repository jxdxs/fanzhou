package com.kevin.fanzhou.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.session.MediaSession
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kevin.fanzhou.R
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.Resolved
import com.kevin.fanzhou.data.PlayItem
import com.kevin.fanzhou.data.SubTrack
import com.kevin.fanzhou.subtitle.OnlineSubtitles
import android.widget.EditText
import java.io.File
import com.kevin.fanzhou.data.PlayQueue
import com.kevin.fanzhou.data.Repo
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var activeExternalSubFile: File? = null
    private var subDelaySec: Double = 0.0
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var rotateBtn: ImageButton
    private lateinit var lockBtn: ImageButton
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var isEnteringPip = false
    private var activeAbSegment: AbSegment? = null

    private var queue: MutableList<PlayItem> = mutableListOf()
    private var currentIndex = -1
    private var transcodedIds = HashSet<String>()
    /** Original (direct-play) item per queue index, kept once a quality switch replaces it. */
    private val originals = HashMap<Int, PlayItem>()
    private val qualityLabel = HashMap<Int, String>()
    /** Cloud quality renditions (best first) fetched per queue index, reused by the 画质 menu. */
    private val qualityCache = HashMap<Int, List<Pair<String, Resolved>>>()
    /** Queue indexes whose remembered per-folder quality has already been applied. */
    private val folderQualityApplied = HashSet<Int>()
    /** Next cloud-ladder position to try per index after a playback failure. */
    private val cloudLadderStep = HashMap<Int, Int>()
    private var cloudFallbackBusy = false
    /** Label of an online subtitle to select once its track appears. */
    private var pendingOnlineSubLabel: String? = null
    /** Queue indexes whose cached online subtitle has already been attached. */
    private val savedSubAttached = HashSet<Int>()
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
    private var isHandoff = false
    private val autoMpv get() = !coreForced && Repo.store.playerCore == com.kevin.fanzhou.data.CORE_AUTO && PlayerCore.mpvAvailable

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
                val (data, rawExt) = com.kevin.fanzhou.subtitle.OnlineSubtitles.unwrap(bytes, name, File(cacheDir, "subs"))
                val utf8Data = com.kevin.fanzhou.subtitle.OnlineSubtitles.toUtf8(data)
                val ext = com.kevin.fanzhou.subtitle.OnlineSubtitles.hintExt(utf8Data, rawExt)
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
                        activeExternalSubFile = out
                        subDelaySec = 0.0
                        applyOnlineSub(out, com.kevin.fanzhou.subtitle.OnlineSubtitles.Hit("本地", "本地导入", "", ""))
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
        PlayerCore.launch(this, mpv = true, forced = forced, handoff = true)
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        PlayerOrientation.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        coreForced = intent?.getBooleanExtra(PlayerCore.EXTRA_FORCED, false) == true
        isHandoff = intent?.getBooleanExtra(PlayerCore.EXTRA_HANDOFF, false) == true
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
        isHandoff = intent.getBooleanExtra(PlayerCore.EXTRA_HANDOFF, false)
        switchedToMpv = false
        transcodedIds.clear(); originals.clear(); qualityLabel.clear()
        qualityCache.clear(); folderQualityApplied.clear(); savedSubAttached.clear(); cloudLadderStep.clear()
        reportStopCurrent()
        loadQueueFromIntent(intent)
        startQueue()
    }

    private fun loadQueueFromIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null) {
            val url = data.toString()
            val resumeKey = "url:${url.substringBefore('?')}"
            val savedPos = Repo.store.position(resumeKey)
            queue = mutableListOf(
                PlayItem(
                    url = url,
                    title = data.lastPathSegment ?: "视频",
                    startMs = savedPos,
                    resumeKey = resumeKey
                )
            )
            PlayQueue.set(queue, 0)
        } else {
            queue = PlayQueue.items.toMutableList()
        }
    }

    // ---------------- UI ----------------

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

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = (layoutInflater.inflate(R.layout.fz_player_view, root, false) as PlayerView).apply {
            useController = true
            controllerShowTimeoutMs = 10000
            setControllerHideOnTouch(false)
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowSubtitleButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            keepScreenOn = true
            subtitleView?.visibility = View.VISIBLE
            applySubtitleScale()
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                topBar.visibility = vis
                rotateBtn.visibility = vis
                lockBtn.visibility = vis
                if (vis == View.VISIBLE) updateSpeedLabel()
                if (vis != View.VISIBLE) hideSystemBars()
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

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
        playerView.addOnLayoutChangeListener(pipLayoutListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.addOnLayoutChangeListener(pipLayoutListener)

        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { dismissResumePrompt() }
            override fun onScrubMove(timeBar: TimeBar, position: Long) {}
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {}
        })

        rotateBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_rotate)
            contentDescription = "屏幕旋转"
            setBackgroundResource(R.drawable.rotate_fab_bg)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                val label = PlayerOrientation.toggle(this@PlayerActivity)
                showHint(label)
                playerView.showController()
            }
        }
        val shortEdge = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val bottomMargin = (shortEdge - dp(44)) / 2
        root.addView(rotateBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.BOTTOM).apply {
            marginStart = dp(16)
            this.bottomMargin = bottomMargin
        })

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
        root.addView(lockBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(16)
        })

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        }
        fun iconBtn(res: Int, desc: String, onClick: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick(it) }
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
        topBar.addView(iconBtn(R.drawable.ic_core, "切换到 MPV 内核") { switchToMpv(null, forced = true); showHint("MPV 内核") }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_hd, "画质") { v -> showQualityMenu(v) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
        topBar.addView(iconBtn(R.drawable.ic_aspect, "画面比例") { cycleResize() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            topBar.addView(iconBtn(R.drawable.ic_pip, "画中画") { enterPip() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        fitExoBottomBar()
        extendSpeedMenu()
        playerView.findViewById<TextView>(R.id.fz_speed)?.setOnClickListener { showSpeedPicker(it) }
        playerView.findViewById<View>(R.id.fz_audio)?.setOnClickListener { v -> showAudioTrackMenu(v) }
        playerView.findViewById<View>(R.id.fz_episodes)?.setOnClickListener { v -> showEpisodeMenu(v) }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { v -> showPlayerSettingsMenu(v) }
        playerView.findViewById<View>(R.id.fz_subtitle)?.setOnClickListener { v -> showTextTrackMenu(v) }
        playerView.findViewById<View>(R.id.fz_ab)?.setOnClickListener { showAbLoopDialog() }
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

        subBubbleView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6212121.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x55FFFFFF.toInt())
            }
            setPadding(dp(12), dp(6), dp(10), dp(6))
            elevation = dp(6).toFloat()
            visibility = View.GONE

            val tv = TextView(this@PlayerActivity).apply {
                text = "💬 匹配在线中文字幕"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(tv)

            val closeBtn = TextView(this@PlayerActivity).apply {
                text = "✕"
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 14f
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener { hideSmartSubBubble() }
            }
            addView(closeBtn)

            setOnClickListener {
                hideSmartSubBubble()
                showOnlineSubSearch()
            }
        }
        val bubbleLp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        bubbleLp.marginEnd = dp(16)
        bubbleLp.bottomMargin = dp(84)
        root.addView(subBubbleView, bubbleLp)

        buildResumePromptView(root)

        setContentView(root)
        attachGestures()
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

    private fun cycleResize() {
        resizeMode = (resizeMode + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeMode]
        showHint(resizeNames[resizeMode])
        updatePipParams()
    }

    private fun isTouchInControlArea(rawX: Int, rawY: Int): Boolean {
        // Resume prompt touches take precedence
        val prompt = resumePromptView
        if (prompt != null && prompt.visibility == View.VISIBLE) {
            val rect = Rect()
            prompt.getGlobalVisibleRect(rect)
            if (rect.contains(rawX, rawY)) return true
        }
        if (!playerView.isControllerFullyVisible) return false
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
        // 3. Subtitle bubble
        subBubbleView?.let { bubble ->
            if (bubble.visibility == View.VISIBLE) {
                val rect = Rect()
                bubble.getGlobalVisibleRect(rect)
                if (rect.contains(rawX, rawY)) return true
            }
        }
        // 4. Bottom bar area (progress row + bottom bar)
        val progressRow = playerView.findViewById<View>(R.id.fz_progress_row)
        val bottomBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
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
        var touchStartedInControls = false

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    if (isTouchInControlArea(e.rawX.toInt(), e.rawY.toInt())) {
                        playerView.showController()
                        return true
                    }
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible && isTouchInControlArea(e.rawX.toInt(), e.rawY.toInt())) {
                    playerView.showController()
                    return true
                }
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
                if (drag != Drag.NONE || !player.isPlaying || touchStartedInControls) return
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
                    if (isTouchInControlArea(ev.rawX.toInt(), ev.rawY.toInt())) {
                        drag = Drag.NONE
                        return@setOnTouchListener false
                    }
                    drag = Drag.NONE
                    startX = ev.x; startY = ev.y
                    startPos = player.currentPosition
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val b = window.attributes.screenBrightness
                    startBrightness = if (b < 0) 0.5f else b
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTouchInControlArea(ev.rawX.toInt(), ev.rawY.toInt()) && drag == Drag.NONE) return@setOnTouchListener false
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
                    if (drag == Drag.SEEK) {
                        player.seekTo(targetPos)
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

    // ---------------- Player ----------------

    private fun initPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val bufMb = Repo.store.bufferSizeMb
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        val safeBufMb = minOf(bufMb, maxOf(32, (maxHeapMb * 0.40).toInt()))
        val targetBytes = safeBufMb * 1024 * 1024
        val minBufferMs = (safeBufMb * 200).coerceIn(15_000, 45_000)
        val maxBufferMs = (safeBufMb * 500).coerceIn(30_000, 120_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(20_000, true)
            .build()
        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
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
        applyAudioBoost()
        mediaSession = MediaSession.Builder(this, player)
            .setId("FanzhouExoSession_${java.util.UUID.randomUUID()}")
            .build()
        startQueue()
        handler.postDelayed(progressTick, 10_000)
        handler.postDelayed(skipCheckTick, 500)
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
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
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

    private fun ensureSavedSubsAttached() {
        for (i in queue.indices) {
            val p = queue[i]
            val key = p.effectiveResumeKey
            val titleKey = "title:" + p.title.trim()
            val path = Repo.store.savedSub(key) ?: Repo.store.savedSub(titleKey) ?: continue
            val file = File(path)
            if (!file.exists()) continue
            val uri = Uri.fromFile(file).toString()
            if (p.subtitles.none { it.url == uri }) {
                val label = "在线字幕"
                val sub = SubTrack(uri, label, SubTrack.mimeFor(file.extension), "zh")
                queue[i] = p.copy(subtitles = p.subtitles + sub)
                if (i == currentIndex || i == PlayQueue.index) {
                    pendingOnlineSubLabel = label
                }
            }
        }
    }

    private fun startQueue() {
        if (queue.isEmpty()) { finish(); return }
        ensureSavedSubsAttached()
        val start = PlayQueue.index.coerceIn(0, queue.size - 1)
        val startItem = queue[start]
        val savedPos = startItem.startMs
        val isHandoffPlayback = isHandoff
        isHandoff = false
        val initialSeek = if (isHandoffPlayback) savedPos else 0L

        val remembered = Repo.store.qualityForSeries(startItem.seriesKey)
        if (!remembered.isNullOrEmpty() && remembered != "原画") {
            val jfId = startItem.jfItemId
            val sid = startItem.sourceId
            if (jfId != null && sid != null) {
                val client = Repo.jellyfin(sid)
                val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == remembered }
                if (client != null && preset != null) {
                    val updated = startItem.copy(url = client.transcodeUrl(jfId, startItem.jfMediaSourceId, preset.second, preset.third), headers = emptyMap(), mime = null)
                    originals[start] = startItem
                    queue[start] = updated
                    qualityLabel[start] = remembered
                }
            }
        }
        val headers = queue.firstOrNull()?.headers ?: emptyMap()
        player.setMediaSources(queue.mapIndexed { i, p -> mediaSourceFactory(p.headers.ifEmpty { headers }).createMediaSource(toMediaItem(p, i)) }, start, initialSeek)
        player.playbackParameters = PlaybackParameters(Repo.store.defaultSpeed)
        player.pauseAtEndOfMediaItems = !Repo.store.autoPlayNext
        player.prepare()
        player.play()
        currentIndex = start
        skipController.onIndexChanged(start, initialSeek)
        onItemStarted()
        if (!isHandoffPlayback && savedPos > 3000L) {
            showResumePrompt(savedPos)
        } else {
            dismissResumePrompt()
        }
        if (!remembered.isNullOrEmpty() && remembered != "原画") {
            preResolveUpcomingQualities(start + 1, remembered)
        }
    }

    private fun onItemStarted() {
        val p = queue.getOrNull(currentIndex) ?: return
        PlayerCore.bumpCloudRecent(p)
        activeAbSegment = null
        updateSpeedLabel()
        applyFolderQuality()
        attachSavedSub()
        titleView.text = listOfNotNull(p.title, p.subtitle).joinToString("  ·  ")
        val id = p.jfItemId ?: return
        val sid = p.sourceId ?: return
        lifecycleScope.launch { Repo.jellyfin(sid)?.reportStart(id, p.jfMediaSourceId, player.currentPosition) }
    }

    private var hasResumedFromSaved = false

    private fun saveProgress(index: Int, posMs: Long, durMs: Long, stopped: Boolean) {
        val p = queue.getOrNull(index) ?: return
        val effectivePos = if (!hasResumedFromSaved && resumeTargetPos > 3000L && posMs < resumeTargetPos && posMs < 60_000L) {
            resumeTargetPos
        } else {
            posMs
        }
        val finished = durMs > 0 && effectivePos > durMs * 0.95
        val key = p.resumeKey ?: p.effectiveResumeKey
        Repo.store.saveProgress(key, effectivePos, durMs, finished)
        val id = p.jfItemId ?: return
        val sid = p.sourceId ?: return
        val client = Repo.jellyfin(sid) ?: return
        val paused = !player.isPlaying
        // Use the application scope so the stop report survives activity teardown.
        kotlinx.coroutines.GlobalScope.launch {
            if (stopped) client.reportStop(id, p.jfMediaSourceId, effectivePos)
            else client.reportProgress(id, p.jfMediaSourceId, effectivePos, paused)
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

    private val skipController = PlayerSkipController()

    private fun checkAbLoop() {
        val seg = activeAbSegment ?: return
        if (!::player.isInitialized) return
        if (player.playbackState == Player.STATE_BUFFERING) return
        val pos = player.currentPosition
        if (pos >= seg.endMs) {
            player.seekTo(seg.startMs)
        }
    }

    private val skipCheckTick = object : Runnable {
        override fun run() {
            checkAutoSkip()
            checkAbLoop()
            handler.postDelayed(this, if (activeAbSegment != null) 200 else 500)
        }
    }


    private fun updateChapterMarkers() {
        val timeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress) ?: return
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

    private fun showChapterDialog() {
        val chs = skipController.chapters
        if (chs.isEmpty() || !::player.isInitialized) return
        val curPos = player.currentPosition
        val curIdx = chs.indexOfLast { it.timeMs <= curPos }.coerceAtLeast(0)
        val items = chs.map { ch ->
            val m = ch.timeMs / 1000 / 60
            val s = (ch.timeMs / 1000) % 60
            "%02d:%02d  %s".format(m, s, ch.title)
        }.toTypedArray()

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选择章节 (${chs.size})")
            .setSingleChoiceItems(items, curIdx) { dialog, which ->
                val target = chs[which]
                player.seekTo(target.timeMs)
                showHint("跳转章节: ${target.title}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkAutoSkip() {
        if (!::player.isInitialized || !player.isPlaying || currentIndex < 0 || currentIndex !in queue.indices) return
        if (resumePromptView?.visibility == View.VISIBLE) return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val p = queue.getOrNull(currentIndex)
        skipController.checkAutoSkip(
            posMs = pos,
            durMs = dur,
            currentIndex = currentIndex,
            queueSize = queue.size,
            seriesKey = p?.seriesKey,
            onSeek = { player.seekTo(it) },
            onNextEpisode = {
                player.seekTo(currentIndex + 1, 0L)
                player.play()
            },
            showHint = { showHint(it) },
        )
    }

    private var loudnessEnhancer: LoudnessEnhancer? = null

    private fun applyAudioBoost() {
        if (!::player.isInitialized) return
        loudnessEnhancer = AudioBoost.applyExo(loudnessEnhancer, player.audioSessionId)
    }

    private fun showAudioEnhanceDialog(anchor: View? = null) {
        val targetAnchor = anchor ?: playerView.findViewById(androidx.media3.ui.R.id.exo_settings) ?: topBar
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

    private fun dismissResumePrompt() {
        handler.removeCallbacks(resumeCountdownRunnable)
        resumePromptView?.let { prompt ->
            if (prompt.visibility == View.VISIBLE) {
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
                if (::player.isInitialized) {
                    player.seekTo(resumeTargetPos)
                    showHint("已跳转至上次播放位置")
                }
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

    private var subBubbleView: View? = null
    private var smartSubPromptedIndex = -1

    private fun hideSmartSubBubble() {
        handler.removeCallbacks(dismissSmartSubBubble)
        subBubbleView?.visibility = View.GONE
    }

    private val dismissSmartSubBubble = Runnable {
        subBubbleView?.animate()?.alpha(0f)?.setDuration(350)?.withEndAction {
            subBubbleView?.visibility = View.GONE
        }
    }

    private fun showSmartSubBubble() {
        val bubble = subBubbleView ?: return
        handler.removeCallbacks(dismissSmartSubBubble)
        bubble.alpha = 0f
        bubble.visibility = View.VISIBLE
        bubble.animate().alpha(1f).setDuration(250).start()
        handler.postDelayed(dismissSmartSubBubble, 7000)
    }

    private fun checkSmartSubBubble() {
        if (!com.kevin.fanzhou.subtitle.OnlineSubtitles.anyConfigured()) return
        if (currentIndex < 0 || currentIndex == smartSubPromptedIndex) return
        if (!::player.isInitialized) return
        val tracks = player.currentTracks
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val hasChinese = textGroups.any { g ->
            (0 until g.length).any { i ->
                val f = g.getTrackFormat(i)
                val lang = f.language?.lowercase() ?: ""
                val label = f.label?.lowercase() ?: ""
                lang in setOf("zh", "chi", "zho", "chs", "cht", "cmn", "yue") ||
                    label.contains("中") || label.contains("简") || label.contains("繁") ||
                    label.contains("chs") || label.contains("cht") || label.contains("chinese")
            }
        }
        val p = queue.getOrNull(currentIndex)
        val hasExtChinese = p?.subtitles?.any { s ->
            val t = (s.label + " " + (s.lang ?: "")).lowercase()
            t.contains("zh") || t.contains("chi") || t.contains("中") || t.contains("简") || t.contains("繁")
        } == true

        if (!hasChinese && !hasExtChinese) {
            smartSubPromptedIndex = currentIndex
            showSmartSubBubble()
        }
    }

    private var lastPos = 0L
    private var lastDur = 0L

    private val speedChoices = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)
    private fun fmtSpeed(s: Float) = "%.2f".format(s).trimEnd('0').trimEnd('.') + "x"

    /**
     * Bottom bar. Portrait keeps only 快退/播放/快进 on the left and 倍速/音轨/选集 + gear on the
     * right; 上一集/下一集、字幕 and the rest fold into the gear menu (see showPlayerSettingsMenu).
     */
    private fun updateSpeedLabel() {
        if (!::playerView.isInitialized) return
        val portrait = PlayerOrientation.isPortrait(this)
        // Official setters: Media3's controller owns these buttons' visibility.
        playerView.setShowPreviousButton(!portrait)
        playerView.setShowNextButton(!portrait)
        playerView.findViewById<View>(R.id.fz_subtitle)?.visibility =
            if (portrait) View.GONE else View.VISIBLE
        updateSubtitleButton()
        playerView.findViewById<View>(R.id.fz_audio)?.visibility = View.VISIBLE
        playerView.findViewById<View>(R.id.fz_episodes)?.visibility =
            if (queue.size <= 1) View.GONE else View.VISIBLE
        playerView.findViewById<View>(R.id.fz_ab)?.visibility = View.VISIBLE
        updateAbButtonState()
        val t = playerView.findViewById<TextView>(R.id.fz_speed) ?: return
        t.visibility = View.VISIBLE
        if (::player.isInitialized) t.text = fmtSpeed(player.playbackParameters.speed)
    }

    private fun showAbLoopDialog() {
        val p = queue.getOrNull(currentIndex) ?: return
        val curPos = if (::player.isInitialized) player.currentPosition else 0L
        val dur = if (::player.isInitialized) player.duration.coerceAtLeast(0L) else 0L
        val container = findViewById<ViewGroup>(android.R.id.content)
        AbLoopDialog.show(
            activity = this,
            root = container,
            videoKey = p.effectiveResumeKey,
            currentPosMs = curPos,
            durationMs = dur,
            activeSegmentId = activeAbSegment?.id,
            fmt = { fmt(it) },
            onStartLoop = { seg ->
                activeAbSegment = seg
                player.seekTo(seg.startMs)
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
        val abBtn = playerView.findViewById<TextView>(R.id.fz_ab) ?: return
        val isLooping = activeAbSegment != null
        abBtn.setTextColor(if (isLooping) 0xFF42A5F5.toInt() else Color.WHITE)
    }

    private fun updateSubtitleButton() {
        val btn = playerView.findViewById<ImageButton>(R.id.fz_subtitle) ?: return
        if (!::player.isInitialized) return
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val hasSelected = !disabled && player.currentTracks.groups.any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSelected(it) }
        }
        btn.setImageResource(
            if (hasSelected) androidx.media3.ui.R.drawable.exo_styled_controls_subtitle_on
            else androidx.media3.ui.R.drawable.exo_styled_controls_subtitle_off
        )
    }

    /** If the user manually picked a quality in this folder/series before, start new episodes at it. */
    private fun applyFolderQuality() {
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val key = orig.seriesKey
        val remembered = Repo.store.qualityForSeries(key) ?: return
        if (qualityLabel[idx] != null || !folderQualityApplied.add(idx)) {
            val currentQuality = qualityLabel[idx] ?: remembered
            if (!currentQuality.isNullOrEmpty() && currentQuality != "原画") {
                preResolveUpcomingQualities(idx + 1, currentQuality)
            }
            return
        }
        val cf = orig.cloudFile
        val jfId = orig.jfItemId
        val sid = orig.sourceId ?: return
        lifecycleScope.launch {
            if (jfId != null) {
                val client = Repo.jellyfin(sid) ?: return@launch
                val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == remembered } ?: return@launch
                val pos = player.currentPosition.coerceAtLeast(0)
                val updated = orig.copy(url = client.transcodeUrl(jfId, orig.jfMediaSourceId, preset.second, preset.third), headers = emptyMap(), mime = null, startMs = pos)
                qualityLabel[idx] = remembered
                replaceCurrent(updated, pos)
                showHint("画质：$remembered（已记住本剧设置）")
                preResolveUpcomingQualities(idx + 1, remembered)
            } else if (cf != null) {
                val qs = qualityCache[idx] ?: runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    .also { if (it.isNotEmpty()) qualityCache[idx] = it }
                if (idx != currentIndex) return@launch
                val hit = PlayerCore.matchQuality(qs, remembered) ?: return@launch
                val pos = player.currentPosition.coerceAtLeast(0)
                qualityLabel[idx] = hit.first
                val updated = orig.copy(url = hit.second.url, headers = hit.second.headers, mime = hit.second.mime, cloudFile = cf, subtitles = p.subtitles, startMs = pos)
                replaceCurrent(updated, pos)
                showHint("画质：${hit.first}（已记住本剧设置）")
                preResolveUpcomingQualities(idx + 1, remembered)
            }
        }
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

    /** Bottom-bar 音轨 button: audio tracks menu using compact AudioTrackPopup. */
    private fun showAudioTrackMenu(anchor: View) {
        if (!::player.isInitialized) return
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) choices.add(g to i)
        }
        if (choices.isEmpty()) { toast("没有可选音轨"); return }
        val labels = choices.map { (g, i) -> audioTrackLabel(g.getTrackFormat(i)) }
        val sel = choices.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
        AudioTrackPopup.show(anchor, labels, sel) { which ->
            val (g, i) = choices[which]
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                .build()
            showHint("音轨：${labels[which]}")
        }
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

    /** Gear menu: hosts the buttons folded out of the portrait bottom bar, plus the usual options. */
    /** Settings (gear) menu: compact icon-button strip anchored above the button. */
    private fun showPlayerSettingsMenu(anchor: View) {
        if (!::player.isInitialized) return
        val items = ArrayList<IconMenu.Item>()
        items += IconMenu.Item(R.drawable.ic_subtitle, "字幕") { showTextTrackMenu(anchor) }
        items += IconMenu.Item(R.drawable.ic_sub_delay, "字幕延迟") { showSubDelayMenu() }
        if (currentIndex > 0) {
            items += IconMenu.Item(R.drawable.ic_prev, "上一集") {
                player.seekTo(currentIndex - 1, 0L); player.play()
            }
        }
        if (currentIndex + 1 < queue.size) {
            items += IconMenu.Item(R.drawable.ic_next, "下一集") {
                player.seekTo(currentIndex + 1, 0L); player.play()
            }
        }
        if (skipController.chapters.isNotEmpty()) {
            items += IconMenu.Item(R.drawable.ic_view_list, "章节(${skipController.chapters.size})") { showChapterDialog() }
        }
        items += IconMenu.Item(R.drawable.ic_skip_intro, "跳过片头") { showSkipIntroPopup(anchor) }
        items += IconMenu.Item(R.drawable.ic_skip_outro, "跳过片尾") { showSkipOutroPopup(anchor) }
        items += IconMenu.Item(R.drawable.ic_audio, "音频增强") { showAudioEnhanceDialog(anchor) }
        if (!PlayerOrientation.isPortrait(this)) {
            items += IconMenu.Item(R.drawable.ic_shuffle, "随机", active = player.shuffleModeEnabled) {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
            val rmLabel = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> "单曲循环"
                Player.REPEAT_MODE_ALL -> "循环全部"
                else -> "循环"
            }
            items += IconMenu.Item(R.drawable.ic_repeat, rmLabel, active = player.repeatMode != Player.REPEAT_MODE_OFF) {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        IconMenu.show(anchor, items)
    }

    /** 跳过片头：弹窗里直接调秒数（即时保存），点跳过执行。 */
    private fun showSkipIntroPopup(anchor: View) {
        val p = queue.getOrNull(currentIndex)
        skipController.showSkipIntroPopup(
            anchor = anchor,
            currentPosMs = player.currentPosition,
            currentIndex = currentIndex,
            seriesKey = p?.seriesKey,
            onSeek = { player.seekTo(it) },
            showHint = { showHint(it) },
            toast = { toast(it) },
        )
    }

    /** 跳过片尾：弹窗里直接调秒数（即时保存），执行跳过片尾（播放下一集或跳到片尾结束）。 */
    private fun showSkipOutroPopup(anchor: View) {
        val dur = player.duration
        val p = queue.getOrNull(currentIndex)
        skipController.showSkipOutroPopup(
            anchor = anchor,
            durMs = if (dur == C.TIME_UNSET) 0L else dur,
            currentIndex = currentIndex,
            seriesKey = p?.seriesKey,
            hasNext = currentIndex + 1 < queue.size,
            onNext = {
                player.seekTo(currentIndex + 1, 0L)
                player.play()
            },
            onSeekEnd = { player.seekTo(it) },
            showHint = { showHint(it) },
            toast = { toast(it) },
        )
    }

    /** 防误触锁定：藏起控制条与顶栏，全屏防误触锁定。 */
    private fun lockScreen() {
        playerView.hideController()
        playerView.controllerAutoShow = false
        topBar.visibility = View.GONE
        rotateBtn.visibility = View.GONE
        if (::lockBtn.isInitialized) lockBtn.visibility = View.GONE
        ScreenLock.lock(this) {
            playerView.controllerAutoShow = true
            playerView.showController()
        }
    }

    /** Subtitle menu: compact popup above the button (在线搜索 + 关闭 + embedded/external tracks). */
    private fun showTextTrackMenu(anchor: View) {
        if (!::player.isInitialized) return
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) choices.add(g to i)
        }
        val items = ArrayList<TextMenu.Item>()
        items += TextMenu.Item("在线搜索字幕…") { showOnlineSubSearch() }
        items += TextMenu.Item("导入本地字幕…") { pickSubLauncher.launch("*/*") }
        items += TextMenu.Item("字幕大小与样式…") { showSubtitleStyleDialog() }
        items += TextMenu.Item("字幕延迟同步…") { showSubDelayMenu() }
        items += TextMenu.Item("关闭字幕") {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            updateSubtitleButton()
        }
        choices.forEach { (g, i) ->
            val f = g.getTrackFormat(i)
            val label = listOfNotNull(f.label?.takeIf { it.isNotEmpty() }, f.language?.let { audioLangName(it) })
                .joinToString(" · ").ifEmpty { "字幕" }
            val isSelected = g.isTrackSelected(i) && !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val display = if (isSelected) "✓ $label" else label
            items += TextMenu.Item(display) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
                playerView.subtitleView?.visibility = View.VISIBLE
                updateSubtitleButton()
            }
        }
        TextMenu.show(anchor, items)
    }

    private fun applySubtitleScale() = applySubtitleStyle()

    private fun applySubtitleStyle() {
        if (!::playerView.isInitialized) return
        val subView = playerView.subtitleView ?: return
        val scale = Repo.store.subtitleScale
        subView.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)

        val textColor = when (Repo.store.subtitleColor) {
            "yellow" -> android.graphics.Color.parseColor("#FFE600")
            "cyan" -> android.graphics.Color.parseColor("#00FFFF")
            else -> android.graphics.Color.WHITE
        }
        val style = when (Repo.store.subtitleStyle) {
            "shadow" -> androidx.media3.ui.CaptionStyleCompat(
                textColor,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                android.graphics.Color.BLACK,
                null
            )
            "box" -> androidx.media3.ui.CaptionStyleCompat(
                textColor,
                android.graphics.Color.argb(160, 0, 0, 0),
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                android.graphics.Color.BLACK,
                null
            )
            else -> androidx.media3.ui.CaptionStyleCompat(
                textColor,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                null
            )
        }
        subView.setStyle(style)

        val marginDp = Repo.store.subtitleBottomMargin
        val density = resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val fraction = (marginDp * density) / height
        subView.setBottomPaddingFraction(fraction.coerceIn(0.02f, 0.25f))
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
                    android.app.AlertDialog.Builder(this@PlayerActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
            if (idx == currentIndex) {
                activeExternalSubFile = file
                subDelaySec = 0.0
                applyOnlineSub(file, hit)
            }
            toast("字幕已加载：${hit.provider}")
        }
    }

    /** Updates subtitles for current item without recreating whole player buffer */
    private fun updateSubtitlesCurrent(newItem: PlayItem, pos: Long) {
        val idx = currentIndex
        queue[idx] = newItem
        val wasPlaying = player.playWhenReady
        val ms = mediaSourceFactory(newItem.headers).createMediaSource(toMediaItem(newItem, idx))
        player.removeMediaItem(idx)
        player.addMediaSource(idx, ms)
        player.seekTo(idx, pos)
        player.playWhenReady = wasPlaying
    }

    /** Subtitle delay fine-tuning menu */
    private fun showSubDelayMenu() {
        val srcFile = activeExternalSubFile
        if (srcFile == null || !srcFile.exists()) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("字幕延迟")
                .setMessage("当前播放的是内嵌字幕或尚未加载外挂字幕。\n\n• ExoPlayer 仅支持对外挂/在线字幕进行时间轴微调。\n• 如需对内嵌字幕进行毫秒级延迟调整，推荐切换至 MPV 内核。")
                .setPositiveButton("切换 MPV 内核") { _, _ ->
                    switchToMpv(null, forced = true)
                }
                .setNeutralButton("搜索在线字幕") { _, _ ->
                    showOnlineSubSearch()
                }
                .setNegativeButton("知道了", null)
                .show()
            return
        }

        val opts = doubleArrayOf(-2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("字幕延迟（当前 ${"%+.1f".format(subDelaySec)} 秒）")
            .setItems(opts.map { if (it == 0.0) "复位" else "%+.1f 秒".format(it) }.toTypedArray()) { _, w ->
                val newDelay = if (opts[w] == 0.0) 0.0 else subDelaySec + opts[w]
                subDelaySec = newDelay
                showHint("正在调整字幕时间轴…", sticky = true)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val raw = srcFile.readText(Charsets.UTF_8)
                        val shifted = OnlineSubtitles.shiftSubtitle(raw, srcFile.extension, (newDelay * 1000).toLong())
                        val subsDir = File(cacheDir, "subs")
                        if (!subsDir.exists()) subsDir.mkdirs()
                        val shiftedFile = File(subsDir, "shifted_${srcFile.name}")
                        shiftedFile.writeText(shifted, Charsets.UTF_8)
                        withContext(Dispatchers.Main) {
                            hintView.visibility = View.GONE
                            val cur = queue.getOrNull(currentIndex) ?: return@withContext
                            val uri = Uri.fromFile(shiftedFile).toString()
                            val label = "在线字幕" + (if (newDelay != 0.0) " (${"%+.1f".format(newDelay)}s)" else "")
                            val sub = SubTrack(uri, label, SubTrack.mimeFor(shiftedFile.extension), "zh")
                            pendingOnlineSubLabel = label
                            val oldUri = Uri.fromFile(srcFile).toString()
                            val shiftedPrefix = Uri.fromFile(File(subsDir, "shifted_")).toString()
                            val filteredSubs = cur.subtitles.filterNot { it.url == oldUri || it.url.startsWith(shiftedPrefix) }
                            val newSubs = filteredSubs + sub
                            val pos = player.currentPosition.coerceAtLeast(0)
                            updateSubtitlesCurrent(cur.copy(subtitles = newSubs), pos)
                            selectOnlineSubtitleTrack()
                            showHint("字幕延迟 ${"%+.1f".format(newDelay)} 秒")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            hintView.visibility = View.GONE
                            toast("字幕调整失败：${e.message}")
                        }
                    }
                }
            }
            .show()
    }

    /** Attach a downloaded subtitle file to the current item and select it. */
    private fun applyOnlineSub(file: File, hit: OnlineSubtitles.Hit?) {
        val idx = currentIndex
        val cur = queue.getOrNull(idx) ?: return
        val label = "在线字幕" + (hit?.let { "·${it.provider} ${it.lang}" } ?: "")
        val uri = android.net.Uri.fromFile(file).toString()
        val sub = SubTrack(uri, label, SubTrack.mimeFor(file.extension), "zh")
        pendingOnlineSubLabel = label
        activeExternalSubFile = file
        subDelaySec = 0.0
        val newSubs = cur.subtitles.filterNot { it.url == uri } + sub
        updateSubtitlesCurrent(cur.copy(subtitles = newSubs), player.currentPosition.coerceAtLeast(0))
        selectOnlineSubtitleTrack()
    }

    /** Re-attach the subtitle previously downloaded for this item, if any. */
    private fun attachSavedSub() {
        val idx = currentIndex
        if (!savedSubAttached.add(idx)) return
        val p = queue.getOrNull(idx) ?: return
        val key = p.effectiveResumeKey
        val titleKey = "title:" + p.title.trim()
        val path = Repo.store.savedSub(key) ?: Repo.store.savedSub(titleKey) ?: return
        val file = File(path)
        if (!file.exists()) return
        val uri = android.net.Uri.fromFile(file).toString()
        val label = "在线字幕"
        val sub = SubTrack(uri, label, SubTrack.mimeFor(file.extension), "zh")
        pendingOnlineSubLabel = label
        activeExternalSubFile = file
        subDelaySec = 0.0
        val cur = queue.getOrNull(idx) ?: return
        if (cur.subtitles.any { it.url == uri }) {
            selectOnlineSubtitleTrack()
            return
        }
        lifecycleScope.launch {
            if (idx == currentIndex && ::player.isInitialized) {
                val current = queue.getOrNull(idx) ?: return@launch
                val curPos = player.currentPosition.coerceAtLeast(0)
                val newSubs = current.subtitles.filterNot { it.url == uri } + sub
                updateSubtitlesCurrent(current.copy(subtitles = newSubs), curPos)
                selectOnlineSubtitleTrack()
            }
        }
    }

    private fun selectOnlineSubtitleTrack(tracks: Tracks? = null) {
        val want = pendingOnlineSubLabel ?: return
        val currentTracks = tracks ?: (if (::player.isInitialized) player.currentTracks else null) ?: return
        var matchedGroup: Tracks.Group? = null
        var matchedIndex = -1

        // 1. Try exact label match
        for (g in currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val fl = g.getTrackFormat(i).label
                if (fl == want) {
                    matchedGroup = g
                    matchedIndex = i
                    break
                }
            }
            if (matchedGroup != null) break
        }

        // 2. Try prefix/contains match
        if (matchedGroup == null) {
            for (g in currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until g.length) {
                    val fl = g.getTrackFormat(i).label
                    if (fl != null && (fl.startsWith("在线字幕") || want.startsWith(fl) || fl.contains(want))) {
                        matchedGroup = g
                        matchedIndex = i
                        break
                    }
                }
                if (matchedGroup != null) break
            }
        }

        // 3. Fallback: pick the last text track group
        if (matchedGroup == null) {
            val textGroups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            if (textGroups.isNotEmpty()) {
                matchedGroup = textGroups.last()
                matchedIndex = 0
            }
        }

        if (matchedGroup != null && matchedIndex >= 0) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(matchedGroup.mediaTrackGroup, matchedIndex))
                .build()
            pendingOnlineSubLabel = null
        }
        playerView.subtitleView?.visibility = View.VISIBLE
        updateSubtitleButton()
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

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                if (pendingOnlineSubLabel != null) selectOnlineSubtitleTrack()
                checkAutoSkip()
            }
            updatePipParams()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            updatePipParams()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (replacingCurrent) return
            val newIndex = mediaItem?.mediaId?.toIntOrNull() ?: return
            if (newIndex == currentIndex) return
            if (currentIndex >= 0) saveProgress(currentIndex, lastDur, lastDur, stopped = true)
            currentIndex = newIndex
            PlayQueue.index = newIndex
            val savedPos = queue[newIndex].startMs
            skipController.onIndexChanged(newIndex, 0L)
            updateChapterMarkers()
            smartSubPromptedIndex = -1
            hideSmartSubBubble()
            dismissResumePrompt()
            if (savedPos > 3000L && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                showResumePrompt(savedPos)
            }
            onItemStarted()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                if (resumeTargetPos > 3000L && newPosition.positionMs >= resumeTargetPos - 5000L) {
                    hasResumedFromSaved = true
                }
                if (kotlin.math.abs(newPosition.positionMs - resumeTargetPos) > 1000L) {
                    dismissResumePrompt()
                }
            }
        }

        override fun onMetadata(metadata: Metadata) {
            var added = false
            val curChapters = skipController.chapters.toMutableList()
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is ChapterFrame) {
                    val subTitle = (0 until entry.subFrameCount).asSequence()
                        .mapNotNull { entry.getSubFrame(it) as? TextInformationFrame }
                        .firstOrNull()?.values?.firstOrNull() ?: entry.chapterId
                    val timeMs = entry.startTimeMs.toLong()
                    if (curChapters.none { it.timeMs == timeMs }) {
                        curChapters.add(PlayerChapter(timeMs, subTitle.ifEmpty { "第 ${curChapters.size + 1} 章" }))
                        added = true
                    }
                }
            }
            if (added) {
                curChapters.sortBy { it.timeMs }
                skipController.chapters = curChapters
                updateChapterMarkers()
            }
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
            // Select the online subtitle that was just attached.
            if (pendingOnlineSubLabel != null) {
                selectOnlineSubtitleTrack(tracks)
            }
            updateSubtitleButton()
            checkSmartSubBubble()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            applyAudioBoost()
        }

        override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
            updateSubtitleButton()
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = queue.getOrNull(currentIndex)
            // Cloud file: automatically step down the drive's transcode ladder.
            if (p != null && (originals[currentIndex] ?: p).cloudFile != null) {
                cloudFallbackOrElse(error)
                return
            }
            errorFallback(error)
        }
    }

    /** Non-cloud failure chain: switch core (auto mode) → Jellyfin transcode → toast. */
    private fun errorFallback(error: PlaybackException) {
        val p = queue.getOrNull(currentIndex)
        if (autoMpv && p != null && originals[currentIndex] == null && switchToMpv("ExoPlayer 无法播放", forced = false)) return
        if (!tryTranscode("直连播放失败")) toast("播放失败：${error.errorCodeName}")
    }

    /**
     * The current cloud rendition failed (expired link, unsupported stream…):
     * fetch the drive's transcode ladder and automatically continue at the next
     * lower rendition, keeping the position. When the ladder is empty or
     * exhausted, fall back to [errorFallback].
     */
    private fun cloudFallbackOrElse(error: PlaybackException) {
        val idx = currentIndex
        val orig = originals[idx] ?: queue.getOrNull(idx) ?: run { errorFallback(error); return }
        val cf = orig.cloudFile ?: run { errorFallback(error); return }
        val sid = orig.sourceId ?: run { errorFallback(error); return }
        if (cloudFallbackBusy) return
        cloudFallbackBusy = true
        lifecycleScope.launch {
            try {
                val qs = qualityCache[idx] ?: runCatching { CloudDrive.get(sid)?.qualities(cf) }.getOrNull().orEmpty()
                    .also { if (it.isNotEmpty()) qualityCache[idx] = it }
                if (idx != currentIndex) return@launch
                if (qs.isEmpty()) { errorFallback(error); return@launch }

                val remembered = Repo.store.qualityForSeries(orig.seriesKey)
                val matched = if (remembered != null) PlayerCore.matchQuality(qs, remembered) else null
                val (label, r) = if (matched != null && matched.first != qualityLabel[idx]) {
                    matched
                } else {
                    val curLabel = qualityLabel[idx] ?: "原画"
                    val curPos = if (curLabel == "原画") -1 else qs.indexOfFirst { it.first == curLabel }
                    val next = cloudLadderStep[idx] ?: (curPos + 1)
                    if (next !in qs.indices) { errorFallback(error); return@launch }
                    cloudLadderStep[idx] = next + 1
                    qs[next]
                }

                val pos = player.currentPosition.coerceAtLeast(0)
                val subs = queue.getOrNull(idx)?.subtitles ?: orig.subtitles
                qualityLabel[idx] = label
                Repo.store.setQualityForSeries(orig.seriesKey, label)
                replaceCurrent(orig.copy(url = r.url, headers = r.headers, mime = r.mime, subtitles = subs, startMs = pos), pos)
                showHint("原画播放失败，已自动切换：$label（已记住本剧设置）")
                preResolveUpcomingQualities(idx + 1, label)
            } finally { cloudFallbackBusy = false }
        }
    }

    private var replacingCurrent = false

    private fun replaceCurrent(newItem: PlayItem, pos: Long) {
        val idx = currentIndex
        originals.getOrPut(idx) { queue[idx] }
        queue[idx] = newItem
        val ms = mediaSourceFactory(newItem.headers).createMediaSource(toMediaItem(newItem, idx))
        replacingCurrent = true
        try {
            val wasPlaying = player.isPlaying
            player.removeMediaItem(idx)
            player.addMediaSource(idx, ms)
            player.seekTo(idx, pos)
            player.prepare()
            if (wasPlaying) player.play()
        } finally {
            replacingCurrent = false
        }
    }

    private fun updateFutureMediaSource(futureIdx: Int, newItem: PlayItem) {
        if (!::player.isInitialized || futureIdx == currentIndex || futureIdx !in 0 until player.mediaItemCount) return
        try {
            val ms = mediaSourceFactory(newItem.headers).createMediaSource(toMediaItem(newItem, futureIdx))
            player.removeMediaItem(futureIdx)
            player.addMediaSource(futureIdx, ms)
        } catch (_: Exception) {}
    }

    private fun preResolveUpcomingQualities(fromIndex: Int, preferredLabel: String) {
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
                    val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == preferredLabel } ?: continue
                    val updated = item.copy(
                        url = client.transcodeUrl(jfId, item.jfMediaSourceId, preset.second, preset.third),
                        headers = emptyMap(),
                        mime = null
                    )
                    withContext(Dispatchers.Main) {
                        originals.getOrPut(idx) { item }
                        queue[idx] = updated
                        qualityLabel[idx] = preferredLabel
                        updateFutureMediaSource(idx, updated)
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
                                updateFutureMediaSource(idx, updated)
                            }
                        }
                    }
                }
            }
        }
    }

    /** For Jellyfin items, fall back to server-side transcoding (HLS) at the current position. */
    private fun tryTranscode(reason: String): Boolean {
        val p = queue.getOrNull(currentIndex) ?: return false
        val id = p.jfItemId ?: return false
        if (!transcodedIds.add(id)) return false
        val client = Repo.jellyfin(p.sourceId ?: return false) ?: return false
        val pos = player.currentPosition
        val remembered = Repo.store.qualityForSeries(p.seriesKey)
        val preset = PlayerCore.JF_PRESETS.firstOrNull { it.first == remembered } ?: PlayerCore.JF_PRESETS[1]
        val newItem = p.copy(url = client.transcodeUrl(id, p.jfMediaSourceId, preset.second, preset.third), startMs = pos)
        originals.getOrPut(currentIndex) { p }
        val label = preset.first
        qualityLabel[currentIndex] = label
        Repo.store.setQualityForSeries(p.seriesKey, label)
        queue[currentIndex] = newItem
        toast("$reason，已切换为转码：$label（已记住本剧设置）")
        replaceCurrent(newItem, pos)
        preResolveUpcomingQualities(currentIndex + 1, label)
        return true
    }

    // ---------------- Quality ----------------

    private fun showQualityMenu(anchor: View? = null) {
        val idx = currentIndex
        val p = queue.getOrNull(idx) ?: return
        val orig = originals[idx] ?: p
        val cf = orig.cloudFile
        val jfId = orig.jfItemId
        when {
            jfId != null -> {
                val client = Repo.jellyfin(orig.sourceId ?: return) ?: return
                val presets = PlayerCore.JF_PRESETS
                val opts = listOf<Pair<String, (() -> PlayItem)>>("原画" to { orig }) + presets.map { (label, br, w) ->
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
                    val opts = listOf<Pair<String, (() -> PlayItem)>>("原画" to { orig }) + qs.map { (label, r) ->
                        label to { orig.copy(url = r.url, headers = r.headers, mime = r.mime) }
                    }
                    if (opts.size == 1) toast("网盘暂时没有这个视频的转码版本，只有原画")
                    pickQuality(idx, opts, anchor)
                }
            }
            else -> toast("该来源只有原画")
        }
    }

    private fun pickQuality(idx: Int, opts: List<Pair<String, (() -> PlayItem)>>, anchor: View? = null) {
        if (opts.size <= 1) return
        val cur = qualityLabel[idx] ?: "原画"
        val checked = opts.indexOfFirst { it.first == cur }
        val targetAnchor = anchor ?: topBar
        QualityPopup.show(targetAnchor, opts.map { it.first }, checked) { which ->
            val (label, make) = opts[which]
            if (label == cur || idx != currentIndex) return@show
            val pos = player.currentPosition
            val item = make().copy(startMs = pos)
            qualityLabel[idx] = label
            cloudLadderStep.remove(idx)
            val o = originals[idx] ?: queue.getOrNull(idx) ?: item
            val key = o.seriesKey
            folderQualityApplied.add(idx)
            Repo.store.setQualityForSeries(key, if (label == "原画") null else label)
            replaceCurrent(item, pos)
            if (label == "原画") originals.remove(idx)
            showHint("画质：$label" + if (label != "原画") "（已记住本剧设置）" else "")
            if (label != "原画") {
                preResolveUpcomingQualities(idx + 1, label)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- PiP & lifecycle ----------------

    private fun computePipAspectRatio(): Rational {
        val p = if (::player.isInitialized) player else null
        val vs = p?.videoSize
        val vw = vs?.width?.takeIf { it > 0 } ?: (p?.videoFormat?.width ?: 0)
        val vh = vs?.height?.takeIf { it > 0 } ?: (p?.videoFormat?.height ?: 0)
        val ratio = if (vw > 0 && vh > 0) Rational(vw, vh) else Rational(16, 9)
        return when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 0.42f -> Rational(42, 100)
            else -> ratio
        }
    }

    private fun computePipSourceRect(): Rect {
        if (!::playerView.isInitialized) return Rect()
        val contentFrame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
        val rect = Rect()
        if (contentFrame != null && contentFrame.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
            return rect
        }
        val pRect = Rect()
        if (playerView.getGlobalVisibleRect(pRect) && pRect.width() > 0 && pRect.height() > 0) {
            val p = if (::player.isInitialized) player else null
            val vs = p?.videoSize
            val vw = vs?.width?.takeIf { it > 0 } ?: (p?.videoFormat?.width ?: 0)
            val vh = vs?.height?.takeIf { it > 0 } ?: (p?.videoFormat?.height ?: 0)
            if (vw > 0 && vh > 0) {
                val pw = pRect.width()
                val ph = pRect.height()
                val videoAspect = vw.toFloat() / vh
                val viewAspect = pw.toFloat() / ph
                val (rw, rh) = if (videoAspect > viewAspect) {
                    pw to (pw / videoAspect).toInt().coerceAtLeast(1)
                } else {
                    (ph * videoAspect).toInt().coerceAtLeast(1) to ph
                }
                val left = pRect.left + (pw - rw) / 2
                val top = pRect.top + (ph - rh) / 2
                return Rect(left, top, left + rw, top + rh)
            }
            return pRect
        }
        return rect
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
            val canAutoEnter = forAutoEnter || (::player.isInitialized && player.isPlaying && !isEnteringPip && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode))
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
        if (!::player.isInitialized) return

        playerView.hideController()
        playerView.useController = false
        topBar.visibility = View.GONE
        rotateBtn.visibility = View.GONE
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
            playerView.useController = true
            topBar.visibility = View.VISIBLE
            rotateBtn.visibility = View.VISIBLE
            if (::lockBtn.isInitialized) lockBtn.visibility = View.VISIBLE
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (::player.isInitialized && player.isPlaying && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode)) enterPip()
        }
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
        if (isEnteringPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) {
            return
        }
        updateRotateBtnPosition()
        if (::playerView.isInitialized) fitExoBottomBar()
        updateSpeedLabel()
        hideSystemBars()
        updatePipParams()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isEnteringPip = false
        if (isInPictureInPictureMode) {
            playerView.hideController()
            playerView.useController = false
            topBar.visibility = View.GONE
            rotateBtn.visibility = View.GONE
            if (::lockBtn.isInitialized) lockBtn.visibility = View.GONE
            hintView.visibility = View.GONE
            dismissResumePrompt()
        } else {
            PlayerOrientation.apply(this)
            hideSystemBars()
            updateRotateBtnPosition()
            if (::playerView.isInitialized) {
                fitExoBottomBar()
                playerView.useController = true
                playerView.hideController()
            }
            topBar.visibility = View.GONE
            rotateBtn.visibility = View.GONE
            if (::lockBtn.isInitialized) lockBtn.visibility = View.GONE
            hintView.visibility = View.GONE
            playerView.post { updatePipParams(force = true) }
        }
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
        AbLoopDialog.dismiss()
        ScreenLock.release(this)
        try { loudnessEnhancer?.release() } catch (_: Throwable) {}
        loudnessEnhancer = null
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) {
            reportStopCurrent()
            player.release()
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::player.isInitialized) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (player.isPlaying) player.pause() else player.play()
                showHint(if (player.isPlaying) "❚❚" else "▶")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player.play()
                showHint("▶")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player.pause()
                showHint("❚❚")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                dismissResumePrompt()
                val step = Repo.store.seekStepSec * 1000L
                player.seekTo(player.currentPosition + step)
                showHint("⏩ ${Repo.store.seekStepSec}秒")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                dismissResumePrompt()
                val step = Repo.store.seekStepSec * 1000L
                player.seekTo(max(0, player.currentPosition - step))
                showHint("⏪ ${Repo.store.seekStepSec}秒")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (currentIndex + 1 < queue.size) {
                    player.seekTo(currentIndex + 1, 0L)
                    player.play()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (currentIndex > 0) {
                    player.seekTo(currentIndex - 1, 0L)
                    player.play()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!playerView.isControllerFullyVisible) {
                    playerView.showController()
                } else {
                    if (player.isPlaying) player.pause() else player.play()
                    showHint(if (player.isPlaying) "❚❚" else "▶")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                dismissResumePrompt()
                val step = Repo.store.seekStepSec * 1000L
                player.seekTo(max(0, player.currentPosition - step))
                showHint("⏪ ${Repo.store.seekStepSec}秒")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                dismissResumePrompt()
                val step = Repo.store.seekStepSec * 1000L
                player.seekTo(player.currentPosition + step)
                showHint("⏩ ${Repo.store.seekStepSec}秒")
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
                showPlayerSettingsMenu(playerView)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
