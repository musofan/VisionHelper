package com.visionhelper.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 对话框气泡。
 *
 * 三种角色在视觉上必须可区分：用户（右/蓝色）、助手（左/深灰）、系统提示（左/琥珀色）。
 * 助手气泡就是"语音播报前先显示的文字信息"的载体。
 */
object ChatBubble {

    fun user(context: Context, text: String): View =
        wrap(context, text, "#2D6CDF", "#FFFFFF", Gravity.END, bold = false)

    fun assistant(context: Context, text: String): View =
        wrap(context, text, "#1E2A38", "#EAF2FB", Gravity.START, bold = false)

    fun system(context: Context, text: String): View =
        wrap(context, text, "#3A2E12", "#FFD98A", Gravity.START, bold = false)

    private fun wrap(
        context: Context,
        text: String,
        bg: String,
        fg: String,
        gravity: Int,
        bold: Boolean,
    ): View {
        val bubble = TextView(context).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(Color.parseColor(fg))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 12).toFloat()
                setColor(Color.parseColor(bg))
                setStroke(dp(context, 1), Color.parseColor("#2A3846"))
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            setPadding(0, dp(context, 4), 0, 0)
        }
        row.addView(bubble, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}