/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.R

/** Private native drawing input; accessibility exposes its password role and no pattern value. */
internal class RecoveryPatternView(context: Context, private val changed: (CharArray) -> Unit) : View(context) {
    private var cells = intArrayOf()
    private var drawing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        contentDescription = context.getString(R.string.passwords_restore_pattern_description)
        isFocusable = true
    }
    fun clear() { cells.fill(0); cells = intArrayOf(); drawing = false; invalidate() }
    private fun x(cell: Int) = (cell % 3 + 0.5f) * width / 3f
    private fun y(cell: Int) = (cell / 3 + 0.5f) * height / 3f
    private fun add(px: Float, py: Float) {
        val radius = minOf(width, height) / 10f
        val cell = (0..8).firstOrNull { val dx = px - x(it); val dy = py - y(it); dx * dx + dy * dy <= radius * radius } ?: return
        val next = RecoveryPattern.append(cells, cell)
        cells.fill(0); cells = next; invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || rejectsPasswordTouch(event.flags)) return false
        if (event.pointerCount != 1) {
            clear(); changed(CharArray(0)); parent?.requestDisallowInterceptTouchEvent(false); return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear(); drawing = true; changed(CharArray(0)); parent?.requestDisallowInterceptTouchEvent(true)
                add(event.x, event.y); return true
            }
            MotionEvent.ACTION_MOVE -> if (drawing) {
                for (i in 0 until event.historySize) add(event.getHistoricalX(i), event.getHistoricalY(i))
                add(event.x, event.y); return true
            }
            MotionEvent.ACTION_UP -> if (drawing) {
                add(event.x, event.y); drawing = false; changed(RecoveryPattern.encode(cells))
                parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> { clear(); changed(CharArray(0)); parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return drawing
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(66, 133, 244); paint.strokeWidth = 4 * resources.displayMetrics.density
        for (i in 1 until cells.size) canvas.drawLine(x(cells[i - 1]), y(cells[i - 1]), x(cells[i]), y(cells[i]), paint)
        val radius = minOf(width, height) / 24f
        for (cell in 0..8) {
            paint.color = if (cell in cells) Color.rgb(66, 133, 244) else Color.GRAY
            canvas.drawCircle(x(cell), y(cell), radius, paint)
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.isPassword = true
        info.text = null
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
