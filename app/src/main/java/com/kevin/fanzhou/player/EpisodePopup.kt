package com.kevin.fanzhou.player

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import coil.imageLoader
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import coil.request.Disposable
import coil.request.ImageRequest
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.VideoThumbnails
import com.kevin.fanzhou.data.PlayItem
import com.kevin.fanzhou.data.Repo

/**
 * 选集列表：锚定在「选集」按钮上方。每集显示缩略图（网盘封面 / 已抽取的视频帧 / Jellyfin 剧集图）、
 * 观看进度条与「看到 mm:ss / 总时长」「已看完」「未观看」等播放记录。
 */
object EpisodePopup {
    private const val ACCENT = 0xFF4FC3F7.toInt()

    fun show(anchor: View, items: List<PlayItem>, selected: Int, onPick: (Int) -> Unit) {
        val ctx = anchor.context
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        val w = minOf(px(340f), screenW - px(16f))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(6f), 0, px(6f))
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply { setColor(0xEB1C1C1E.toInt()); cornerRadius = 12 * dp }
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popup = PopupWindow(scroll, w, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 8 * dp
        }
        val requests = ArrayList<Disposable>()
        val scope = kotlinx.coroutines.MainScope()
        popup.setOnDismissListener { requests.forEach { it.dispose() }; scope.cancel() }
        val ripple = TypedValue().also { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        val thumbW = px(104f)
        val thumbH = px(58.5f)

        items.forEachIndexed { i, item ->
            val isCur = i == selected
            val key = item.resumeKey ?: item.effectiveResumeKey
            val store = Repo.store
            val finished = store.isFinished(key)
            val pos = store.position(key)
            val dur = store.duration(key)
            val progress = when {
                finished -> 1f
                pos > 0 && dur > 0 -> (pos.toFloat() / dur).coerceIn(0.02f, 1f)
                else -> 0f
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(10f), px(6f), px(12f), px(6f))
                if (isCur) setBackgroundColor(0x264FC3F7)
                else if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                setOnClickListener { popup.dismiss(); onPick(i) }
            }

            // ---- thumbnail with episode-number placeholder, progress bar and "当前" tag ----
            val thumbBox = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = 6 * dp }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 6 * dp)
                }
                clipToOutline = true
            }
            thumbBox.addView(TextView(ctx).apply {
                text = "${i + 1}"
                gravity = Gravity.CENTER
                setTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, Typeface.BOLD)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumbBox.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (progress > 0f) {
                thumbBox.addView(View(ctx).apply { setBackgroundColor(0x66000000) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3f), Gravity.BOTTOM))
                thumbBox.addView(View(ctx).apply { setBackgroundColor(ACCENT) },
                    FrameLayout.LayoutParams((thumbW * progress).toInt().coerceAtLeast(px(3f)), px(3f), Gravity.BOTTOM or Gravity.START))
            }
            if (finished) thumbBox.addView(TextView(ctx).apply {
                text = "✓"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE60288D1.toInt()) }
            }, FrameLayout.LayoutParams(px(18f), px(18f), Gravity.TOP or Gravity.END).apply {
                setMargins(0, px(3f), px(3f), 0)
            })
            if (isCur) thumbBox.addView(TextView(ctx).apply {
                text = "播放中"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.WHITE)
                setPadding(px(4f), px(1f), px(4f), px(1f))
                background = GradientDrawable().apply { setColor(0xE60288D1.toInt()); cornerRadius = 4 * dp }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                setMargins(px(3f), px(3f), 0, 0)
            })
            row.addView(thumbBox, LinearLayout.LayoutParams(thumbW, thumbH))
            fun loadThumb(data: Any, headers: Map<String, String>) {
                val b = ImageRequest.Builder(ctx).data(data).target(iv).crossfade(true).size(px(208f), px(117f))
                headers.forEach { (k, v) -> b.addHeader(k, v) }
                requests += ctx.imageLoader.enqueue(b.build())
            }
            val src = thumbSource(ctx, item)
            val cf = item.cloudFile
            val sid = item.sourceId
            if (src != null) loadThumb(src.first, src.second)
            else if (cf != null && sid != null && kotlin.math.abs(i - selected) <= 3) {
                // 附近几集还没有封面时，后台抽一帧（与网盘列表共用缓存）
                scope.launch { VideoThumbnails.loadOrExtract(ctx, sid, cf)?.let { loadThumb(it, emptyMap()) } }
            }

            // ---- title + watch record ----
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply {
                text = listOfNotNull(item.title, item.subtitle).joinToString(" · ")
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (isCur) ACCENT else Color.WHITE)
                if (isCur) setTypeface(typeface, Typeface.BOLD)
            })
            val meta = when {
                finished -> "已看完" + if (dur > 0) " · ${fmt(dur)}" else ""
                pos > 0 && dur > 0 -> "看到 ${fmt(pos)} / ${fmt(dur)} · ${(pos * 100 / dur).coerceIn(1, 99)}%"
                pos > 0 -> "看到 ${fmt(pos)}"
                dur > 0 -> "未观看 · ${fmt(dur)}"
                else -> "未观看"
            }
            texts.addView(TextView(ctx).apply {
                text = meta
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(if (finished) 0xFF8E8E93.toInt() else if (pos > 0) 0xCC4FC3F7.toInt() else 0xFF9E9E9E.toInt())
                setPadding(0, px(3f), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(10f) })
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        scroll.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        // Cap the height (~5 rows / 60% of the screen); the list scrolls.
        val maxH = minOf(loc[1] - px(8f), (screenH * 0.6f).toInt(), px(380f)).coerceAtLeast(px(150f))
        val h = scroll.measuredHeight.coerceAtMost(maxH)
        popup.height = h
        val x = (loc[0] + anchor.width - w).coerceIn(px(8f), (screenW - w - px(8f)).coerceAtLeast(px(8f)))
        val y = (loc[1] - h - px(4f)).coerceAtLeast(0)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        if (selected >= 0) scroll.post { col.getChildAt(selected)?.let { c -> scroll.scrollTo(0, (c.top - h / 2 + c.height / 2).coerceAtLeast(0)) } }
    }

    /** Best thumbnail for an item: provider cover → cached extracted frame → Jellyfin episode image. */
    private fun thumbSource(ctx: android.content.Context, item: PlayItem): Pair<Any, Map<String, String>>? {
        val sid = item.sourceId
        item.cloudFile?.let { cf ->
            if (sid == null) return null
            cf.thumb?.let { t -> return t to (CloudDrive.get(sid)?.thumbHeaders ?: emptyMap()) }
            VideoThumbnails.getCached(ctx, sid, cf.id)?.let { return it to emptyMap() }
            return null
        }
        val jf = item.jfItemId ?: return null
        val client = Repo.jellyfin(sid ?: return null) ?: return null
        return client.episodeThumb(jf) to client.authHeaders()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
