package com.kevin.fanzhou.player

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kevin.fanzhou.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class AbSegment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startMs: Long,
    val endMs: Long
)

object AbSegmentStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context, videoKey: String): List<AbSegment> {
        val sp = context.getSharedPreferences("ab_segments", Context.MODE_PRIVATE)
        val raw = sp.getString("seg_$videoKey", "[]") ?: "[]"
        return try {
            json.decodeFromString<List<AbSegment>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, videoKey: String, list: List<AbSegment>) {
        val sp = context.getSharedPreferences("ab_segments", Context.MODE_PRIVATE)
        sp.edit().putString("seg_$videoKey", json.encodeToString(list)).apply()
    }
}

object AbLoopDialog {
    private var activeOverlay: View? = null
    private val handler = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = activeOverlay != null

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        activeOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        activeOverlay = null
    }

    fun show(
        activity: Activity,
        root: ViewGroup,
        videoKey: String,
        currentPosMs: Long,
        durationMs: Long,
        activeSegmentId: String?,
        fmt: (Long) -> String,
        onStartLoop: (AbSegment) -> Unit,
        onStopLoop: () -> Unit
    ) {
        dismiss()
        val dm = activity.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val segments = AbSegmentStore.load(activity, videoKey).toMutableList()

        val overlay = FrameLayout(activity).apply {
            id = R.id.fz_ab_dialog_tag
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(0x66000000)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }

        val cardWidth = (360 * dm.density).toInt().coerceAtMost(dm.widthPixels - dp(24))
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF21E1E1E.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x55FFFFFF.toInt())
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(14).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER)
        }

        lateinit var renderAddImpl: () -> Unit
        lateinit var renderConfirmDeleteImpl: (AbSegment) -> Unit

        fun renderList() {
            card.removeAllViews()

            // Header
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }

            val title = TextView(activity).apply {
                text = "🔁 AB 片段循环"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
            header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

            if (activeSegmentId != null) {
                val stopBtn = TextView(activity).apply {
                    text = "停止循环"
                    setTextColor(0xFFFF5252.toInt())
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(0x33F44336.toInt())
                    }
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        onStopLoop()
                        Toast.makeText(activity, "已停止 AB 循环", Toast.LENGTH_SHORT).show()
                        dismiss()
                    }
                }
                header.addView(stopBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }

            val addBtn = TextView(activity).apply {
                text = "+ 新增"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xFF1976D2.toInt())
                }
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { renderAddImpl() }
            }
            header.addView(addBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })

            val closeBtn = TextView(activity).apply {
                text = "✕"
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { dismiss() }
            }
            header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))
            card.addView(header)

            // Divider
            val div = View(activity).apply {
                setBackgroundColor(0x22FFFFFF)
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = 0; marginEnd = 0; topMargin = dp(8); bottomMargin = dp(8) }
            }
            card.addView(div)

            if (segments.isEmpty()) {
                val empty = TextView(activity).apply {
                    text = "暂无保存的 AB 片段\n点击右上角「+ 新增」设置循环区间"
                    setTextColor(0x88FFFFFF.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(22), dp(10), dp(22))
                }
                card.addView(empty)
            } else {
                val scroll = ScrollView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        if (segments.size > 3) dp(220) else LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val listLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                }

                segments.forEach { seg ->
                    val isCur = seg.id == activeSegmentId
                    val item = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(if (isCur) 0x331976D2.toInt() else 0x1AFFFFFF.toInt())
                            if (isCur) setStroke(dp(1), 0xFF2196F3.toInt())
                        }
                        setPadding(dp(10), dp(8), dp(8), dp(8))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                        isClickable = true
                        setOnClickListener {
                            onStartLoop(seg)
                            Toast.makeText(activity, "已开始循环「${seg.name}」", Toast.LENGTH_SHORT).show()
                            dismiss()
                        }
                    }

                    val infoCol = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        isClickable = true
                        setOnClickListener { item.performClick() }
                    }

                    val nameRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val nameTv = TextView(activity).apply {
                        text = seg.name
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    nameRow.addView(nameTv)
                    if (isCur) {
                        val badge = TextView(activity).apply {
                            text = "【循环中】"
                            setTextColor(0xFF4CAF50.toInt())
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(dp(4), 0, 0, 0)
                        }
                        nameRow.addView(badge)
                    }
                    infoCol.addView(nameRow)

                    val timeTv = TextView(activity).apply {
                        val durText = fmt((seg.endMs - seg.startMs).coerceAtLeast(0))
                        text = "${fmt(seg.startMs)} ~ ${fmt(seg.endMs)} (时长 $durText)"
                        setTextColor(0xAAFFFFFF.toInt())
                        textSize = 12f
                    }
                    infoCol.addView(timeTv)

                    item.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))

                    val delBtn = TextView(activity).apply {
                        text = "🗑"
                        setTextColor(0xCCFF5252.toInt())
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener {
                            renderConfirmDeleteImpl(seg)
                        }
                    }
                    item.addView(delBtn, LinearLayout.LayoutParams(-2, -2))

                    listLayout.addView(item)
                }
                scroll.addView(listLayout)
                card.addView(scroll)
            }
        }

        fun renderConfirmDelete(seg: AbSegment) {
            card.removeAllViews()

            val title = TextView(activity).apply {
                text = "确认删除片段？"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            card.addView(title)

            val msg = TextView(activity).apply {
                text = "确定要删除片段「${seg.name}」(${fmt(seg.startMs)} ~ ${fmt(seg.endMs)}) 吗？"
                setTextColor(0xBBFFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(12))
            }
            card.addView(msg)

            val btnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }

            fun actionBtn(label: String, isDel: Boolean, onClick: () -> Unit) = TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(16), dp(7), dp(16), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(if (isDel) 0xFFD32F2F.toInt() else 0x33FFFFFF.toInt())
                }
                setOnClickListener { onClick() }
            }

            val cancelBtn = actionBtn("取消", false) { renderList() }
            val confirmBtn = actionBtn("确认删除", true) {
                segments.removeAll { it.id == seg.id }
                AbSegmentStore.save(activity, videoKey, segments)
                if (seg.id == activeSegmentId) onStopLoop()
                Toast.makeText(activity, "已删除片段「${seg.name}」", Toast.LENGTH_SHORT).show()
                renderList()
            }

            btnRow.addView(cancelBtn)
            btnRow.addView(View(activity), LinearLayout.LayoutParams(dp(12), 1))
            btnRow.addView(confirmBtn)
            card.addView(btnRow)
        }

        fun renderAdd() {
            card.removeAllViews()

            var curA = currentPosMs.coerceAtLeast(0L)
            var curB = (currentPosMs + 10_000L).coerceAtMost(if (durationMs > 0) durationMs else currentPosMs + 60_000L).coerceAtLeast(curA + 1000L)

            // Header
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            val title = TextView(activity).apply {
                text = "新增 AB 循环片段"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            val backBtn = TextView(activity).apply {
                text = "✕"
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { renderList() }
            }
            header.addView(backBtn)
            card.addView(header)

            val div = View(activity).apply {
                setBackgroundColor(0x22FFFFFF)
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(8) }
            }
            card.addView(div)

            // Name input
            val nameLabel = TextView(activity).apply {
                text = "片段名称："
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 12f
            }
            card.addView(nameLabel)

            val nameInput = EditText(activity).apply {
                setText("片段 ${segments.size + 1}")
                setTextColor(Color.WHITE)
                textSize = 13f
                setSingleLine(true)
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0x26FFFFFF.toInt())
                    setStroke(dp(1), 0x44FFFFFF.toInt())
                }
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3); bottomMargin = dp(8) }
            }
            card.addView(nameInput)

            // Point A
            lateinit var aValTv: TextView
            lateinit var bValTv: TextView

            val aRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
            }
            aRow.addView(TextView(activity).apply { text = "A 点 (起点)："; setTextColor(Color.WHITE); textSize = 13f })
            aValTv = TextView(activity).apply {
                text = fmt(curA)
                setTextColor(0xFF64B5F6.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            aRow.addView(aValTv)
            aRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            val aCurBtn = TextView(activity).apply {
                text = "设为当前 (${fmt(currentPosMs)})"
                setTextColor(0xFF90CAF9.toInt())
                textSize = 11f
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(0x332196F3.toInt())
                }
                setPadding(dp(6), dp(3), dp(6), dp(3))
                setOnClickListener {
                    curA = currentPosMs.coerceAtLeast(0L)
                    aValTv.text = fmt(curA)
                }
            }
            aRow.addView(aCurBtn)
            card.addView(aRow)

            val aStepRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) }
            }
            fun applyStep(deltaMs: Long, isA: Boolean) {
                if (isA) {
                    curA = (curA + deltaMs).coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
                    aValTv.text = fmt(curA)
                } else {
                    curB = (curB + deltaMs).coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
                    bValTv.text = fmt(curB)
                }
            }

            fun stepBtn(label: String, deltaMs: Long, isA: Boolean) = TextView(activity).apply {
                text = label
                setTextColor(0xDDFFFFFF.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(0x22FFFFFF.toInt())
                }
                setPadding(dp(8), dp(3), dp(8), dp(3))
                isClickable = true
                val repeatRunnable = object : Runnable {
                    override fun run() {
                        applyStep(deltaMs, isA)
                        handler.postDelayed(this, 90)
                    }
                }
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            applyStep(deltaMs, isA)
                            handler.postDelayed(repeatRunnable, 350)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            handler.removeCallbacks(repeatRunnable)
                            true
                        }
                        else -> false
                    }
                }
            }
            aStepRow.addView(stepBtn("-10s", -10_000L, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            aStepRow.addView(stepBtn("-1s", -1000L, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            aStepRow.addView(stepBtn("+1s", 1000L, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            aStepRow.addView(stepBtn("+10s", 10_000L, true), LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(aStepRow)

            // Point B
            val bRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            bRow.addView(TextView(activity).apply { text = "B 点 (终点)："; setTextColor(Color.WHITE); textSize = 13f })
            bValTv = TextView(activity).apply {
                text = fmt(curB)
                setTextColor(0xFF64B5F6.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            bRow.addView(bValTv)
            bRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            val bCurBtn = TextView(activity).apply {
                text = "设为当前 (${fmt(currentPosMs)})"
                setTextColor(0xFF90CAF9.toInt())
                textSize = 11f
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(0x332196F3.toInt())
                }
                setPadding(dp(6), dp(3), dp(6), dp(3))
                setOnClickListener {
                    curB = currentPosMs.coerceAtLeast(0L)
                    bValTv.text = fmt(curB)
                }
            }
            bRow.addView(bCurBtn)
            card.addView(bRow)

            val bStepRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) }
            }
            bStepRow.addView(stepBtn("-10s", -10_000L, false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            bStepRow.addView(stepBtn("-1s", -1000L, false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            bStepRow.addView(stepBtn("+1s", 1000L, false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            bStepRow.addView(stepBtn("+10s", 10_000L, false), LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(bStepRow)

            // Bottom action row
            val actRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            }
            val cancelBtn = TextView(activity).apply {
                text = "返回"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(16), dp(7), dp(16), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0x33FFFFFF.toInt())
                }
                setOnClickListener { renderList() }
            }
            val saveBtn = TextView(activity).apply {
                text = "保存"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(22), dp(7), dp(22), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xFF1976D2.toInt())
                }
                setOnClickListener {
                    if (curB <= curA) {
                        Toast.makeText(activity, "B 点时间必须大于 A 点时间", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val name = nameInput.text.toString().trim().ifEmpty { "片段 ${segments.size + 1}" }
                    val newSeg = AbSegment(name = name, startMs = curA, endMs = curB)
                    segments.add(newSeg)
                    AbSegmentStore.save(activity, videoKey, segments)
                    Toast.makeText(activity, "已保存「${newSeg.name}」", Toast.LENGTH_SHORT).show()
                    renderList()
                }
            }

            actRow.addView(cancelBtn)
            actRow.addView(View(activity), LinearLayout.LayoutParams(dp(14), 1))
            actRow.addView(saveBtn)
            card.addView(actRow)
        }

        renderAddImpl = { renderAdd() }
        renderConfirmDeleteImpl = { renderConfirmDelete(it) }
        renderList()
        overlay.addView(card)
        root.addView(overlay)
        activeOverlay = overlay
    }
}
