package io.github.kichikuou.xsystem4

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import org.libsdl.app.SDLActivity
import java.io.File
import kotlin.math.hypot

// Intent for this activity must have the following extras:
// - EXTRA_GAME_ROOT (string): A path to the game installation.
// - EXTRA_SAVE_DIR (string): A path to a directory where save files are stored.
class XSystem4Activity : SDLActivity() {
    companion object {
        const val EXTRA_GAME_ROOT = "GAME_ROOT"
        const val EXTRA_SAVE_DIR = "SAVE_DIR"
        const val COMMAND_OPEN_PLAYING_MANUAL = 0x8000  // xsystem4/src/hll/SystemService.c

        const val PREFS_NAME = "xsystem4"
        const val PREF_TOUCH_MODE = "touch_mode"
        const val PREF_ANIME4K = "anime4k"
        const val PREF_ANIME4K_SCALE = "anime4k_scale"
    }

    enum class TouchMode {
        TOUCHPAD,  // Relative cursor movement, like a laptop touchpad.
        DIRECT,     // Touch directly maps to screen position.
    }

    enum class Anime4kScale(val label: String) {
        OFF("关闭"),
        X2("2x (M)"),
        X4("4x (M+S)"),
    }

    private var cursorView: ImageView? = null
    private var cursorBitmapNormal: Bitmap? = null
    private var cursorBitmapDragging: Bitmap? = null

    private var touchMode = TouchMode.TOUCHPAD
    private var anime4kScale = Anime4kScale.X4

    // Implemented in xsystem4/src/anime4k.c (libxsystem4.so)
    private external fun nativeSetAnime4kMode(mode: Int)

    private var cursorX = -1f
    private var cursorY = -1f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownTime = 0L
    private var maxPointers = 1
    private var hasMoved = false
    private var isDragging = false
    private var lastTwoFingerY = 0f
    private var twoFingerScrolled = false

    // Hold-and-tap right click state (touchpad mode)
    private var secondFingerId = -1
    private var secondFingerDownTime = 0L
    private var secondFingerDownX = 0f
    private var secondFingerDownY = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!hasMoved && maxPointers == 1) {
            isDragging = true
            cursorView?.setImageBitmap(cursorBitmapDragging)
            SDLActivity.onNativeMouse(1, MotionEvent.ACTION_DOWN, cursorX, cursorY, false)
        }
    }

    private var menuPopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Workaround for https://github.com/libsdl-org/SDL/issues/8995
        SDLActivity.setWindowStyle(true)
        touchMode = try {
            TouchMode.valueOf(
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_TOUCH_MODE, TouchMode.TOUCHPAD.name)!!)
        } catch (e: IllegalArgumentException) {
            TouchMode.TOUCHPAD
        }
        anime4kScale = try {
            Anime4kScale.valueOf(
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_ANIME4K_SCALE, Anime4kScale.X4.name)!!)
        } catch (e: IllegalArgumentException) {
            Anime4kScale.X4
        }
        try {
            nativeSetAnime4kMode(anime4kScale.ordinal)
        } catch (e: UnsatisfiedLinkError) {
            // Library not loaded yet; the menu toggle applies it later.
        }
        initVirtualCursor()
    }

    // ------------------------------------------------------------------
    // Virtual cursor
    // ------------------------------------------------------------------

    private fun createCursorBitmap(fillColor: Int, strokeColor: Int): Bitmap {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 36f)
            lineTo(10f, 26f)
            lineTo(18f, 42f)
            lineTo(24f, 38f)
            lineTo(16f, 22f)
            lineTo(28f, 22f)
            close()
        }

        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.color = strokeColor
        paint.strokeWidth = 3f
        canvas.drawPath(path, paint)

        return bitmap
    }

    private fun initVirtualCursor() {
        try {
            cursorBitmapNormal = createCursorBitmap(Color.WHITE, Color.BLACK)
            cursorBitmapDragging = createCursorBitmap(Color.BLACK, Color.WHITE)

            cursorView = ImageView(this).apply {
                setImageBitmap(cursorBitmapNormal)
                layoutParams = ViewGroup.LayoutParams(48, 48)
                elevation = 9999f
                translationZ = 9999f
                x = 100f
                y = 100f
            }
            val decorView = window.decorView as? ViewGroup
            if (decorView != null) {
                decorView.addView(cursorView)
            } else {
                mLayout?.addView(cursorView)
            }
            cursorView?.bringToFront()
            window.decorView.post {
                cursorView?.bringToFront()
            }
        } catch (e: Exception) {
            android.util.Log.e("XSystem4", "initVirtualCursor failed", e)
        }
    }

    private fun updateCursor(x: Float, y: Float) {
        // In direct mode the finger IS the pointer; no visible cursor.
        if (touchMode == TouchMode.DIRECT) {
            cursorView?.visibility = View.INVISIBLE
            return
        }
        cursorView?.visibility = View.VISIBLE
        cursorView?.apply {
            this.x = x
            this.y = y
            bringToFront()
        }
    }

    private fun surfaceSize(): Pair<Float, Float> {
        val width = mSurface?.width?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat()
        val height = mSurface?.height?.toFloat() ?: resources.displayMetrics.heightPixels.toFloat()
        return Pair(width, height)
    }

    private fun sendClick(button: Int) {
        SDLActivity.onNativeMouse(button, MotionEvent.ACTION_DOWN, cursorX, cursorY, false)
        cursorView?.postDelayed({
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, cursorX, cursorY, false)
        }, 40L)
    }

    // ------------------------------------------------------------------
    // Touch dispatch
    // ------------------------------------------------------------------

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.source == InputDevice.SOURCE_MOUSE ||
            event.source == (InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TOUCHSCREEN)) {
            return super.dispatchTouchEvent(event)
        }

        return if (touchMode == TouchMode.DIRECT) {
            handleDirectTouch(event)
        } else {
            handleTouchpadTouch(event)
        }
    }

    private fun initCursorIfNeeded(width: Float, height: Float) {
        if (cursorX < 0f) {
            cursorX = width / 2f
            cursorY = height / 2f
            updateCursor(cursorX, cursorY)
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, false)
        }
    }

    /** Touchpad mode: relative cursor movement. */
    private fun handleTouchpadTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount
        val (width, height) = surfaceSize()

        initCursorIfNeeded(width, height)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                lastTouchX = touchDownX
                lastTouchY = touchDownY
                touchDownTime = System.currentTimeMillis()
                maxPointers = 1
                hasMoved = false
                isDragging = false
                secondFingerId = -1
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, 350L)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (pointerCount > maxPointers) {
                    maxPointers = pointerCount
                }
                if (pointerCount == 2) {
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                    val idx = event.actionIndex
                    secondFingerId = event.getPointerId(idx)
                    secondFingerDownTime = System.currentTimeMillis()
                    secondFingerDownX = event.getX(idx)
                    secondFingerDownY = event.getY(idx)
                    twoFingerScrolled = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount > maxPointers) {
                    maxPointers = pointerCount
                }
                if (pointerCount == 1) {
                    val curX = event.x
                    val curY = event.y
                    val dx = curX - lastTouchX
                    val dy = curY - lastTouchY
                    lastTouchX = curX
                    lastTouchY = curY

                    val dist = hypot((curX - touchDownX).toDouble(), (curY - touchDownY).toDouble()).toFloat()
                    if (dist > 14f) {
                        hasMoved = true
                        if (!isDragging) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                    }

                    val sensitivity = 1.3f
                    cursorX = (cursorX + dx * sensitivity).coerceIn(0f, width)
                    cursorY = (cursorY + dy * sensitivity).coerceIn(0f, height)
                    updateCursor(cursorX, cursorY)

                    // Always keep mouse position synchronized for hover inspection
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, false)

                    if (isDragging) {
                        SDLActivity.onNativeMouse(1, MotionEvent.ACTION_MOVE, cursorX, cursorY, false)
                    }
                } else if (pointerCount == 2) {
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dy = midY - lastTwoFingerY
                    val scrollThreshold = 25f
                    if (Math.abs(dy) >= scrollThreshold) {
                        val scrollDir = if (dy > 0) 1f else -1f
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0f, scrollDir, false)
                        lastTwoFingerY = midY
                        twoFingerScrolled = true
                    }
                    // Track the second finger's own movement.
                    val idx = event.findPointerIndex(secondFingerId)
                    if (idx >= 0) {
                        val d2 = hypot(
                            (event.getX(idx) - secondFingerDownX).toDouble(),
                            (event.getY(idx) - secondFingerDownY).toDouble()
                        ).toFloat()
                        if (d2 > 30f) {
                            // Big movement of the second finger: treat as scroll-ish, not a tap.
                            twoFingerScrolled = true
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (pointerCount == 2 && secondFingerId >= 0) {
                    // Hold one finger + tap with another = right click.
                    // This also covers the classic two-finger simultaneous tap.
                    val dur = System.currentTimeMillis() - secondFingerDownTime
                    val idx = event.findPointerIndex(secondFingerId)
                    val secondFingerMoved = if (idx >= 0) {
                        hypot(
                            (event.getX(idx) - secondFingerDownX).toDouble(),
                            (event.getY(idx) - secondFingerDownY).toDouble()
                        ).toFloat() > 30f
                    } else {
                        false
                    }
                    if (!twoFingerScrolled && !secondFingerMoved && dur < 500L) {
                        sendClick(2)
                    }
                    secondFingerId = -1
                }
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val duration = System.currentTimeMillis() - touchDownTime
                val dist = hypot((event.x - touchDownX).toDouble(), (event.y - touchDownY).toDouble()).toFloat()

                if (isDragging) {
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, cursorX, cursorY, false)
                    isDragging = false
                    cursorView?.setImageBitmap(cursorBitmapNormal)
                } else if (maxPointers == 1 && !hasMoved && dist < 14f && duration < 320L) {
                    // Strictly stationary tap = Click (Mouse only, no duplicate touch event)
                    sendClick(1)
                }

                // Continuously re-latch hover coordinates after touch release so tooltip stays up
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, false)
                maxPointers = 1
                hasMoved = false
                secondFingerId = -1
            }
        }
        return true
    }

    /** Direct touch mode: touch position maps directly to the screen. */
    private fun handleDirectTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount
        val (width, height) = surfaceSize()

        initCursorIfNeeded(width, height)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                lastTouchX = touchDownX
                lastTouchY = touchDownY
                touchDownTime = System.currentTimeMillis()
                maxPointers = 1
                hasMoved = false
                isDragging = false
                twoFingerScrolled = false
                cursorX = event.x.coerceIn(0f, width)
                cursorY = event.y.coerceIn(0f, height)
                updateCursor(cursorX, cursorY)
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount > maxPointers) {
                    maxPointers = pointerCount
                }
                if (pointerCount == 2) {
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                    val idx = event.actionIndex
                    secondFingerId = event.getPointerId(idx)
                    secondFingerDownTime = System.currentTimeMillis()
                    secondFingerDownX = event.getX(idx)
                    secondFingerDownY = event.getY(idx)
                    twoFingerScrolled = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount > maxPointers) {
                    maxPointers = pointerCount
                }
                if (pointerCount == 1) {
                    val curX = event.x
                    val curY = event.y
                    val dist = hypot((curX - touchDownX).toDouble(), (curY - touchDownY).toDouble()).toFloat()
                    cursorX = curX.coerceIn(0f, width)
                    cursorY = curY.coerceIn(0f, height)
                    if (dist > 14f) {
                        hasMoved = true
                        // Moving while pressed = drag (Rance games need this a lot).
                        if (!isDragging && maxPointers == 1 && !twoFingerScrolled) {
                            isDragging = true
                            SDLActivity.onNativeMouse(1, MotionEvent.ACTION_DOWN, cursorX, cursorY, false)
                        }
                    }
                    updateCursor(cursorX, cursorY)
                    if (isDragging) {
                        SDLActivity.onNativeMouse(1, MotionEvent.ACTION_MOVE, cursorX, cursorY, false)
                    } else {
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, false)
                    }
                } else if (pointerCount == 2) {
                    // Cursor keeps following the FIRST finger.
                    cursorX = event.getX(0).coerceIn(0f, width)
                    cursorY = event.getY(0).coerceIn(0f, height)
                    updateCursor(cursorX, cursorY)
                    if (isDragging) {
                        SDLActivity.onNativeMouse(1, MotionEvent.ACTION_MOVE, cursorX, cursorY, false)
                    }

                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dy = midY - lastTwoFingerY
                    if (Math.abs(dy) >= 25f) {
                        val scrollDir = if (dy > 0) 1f else -1f
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0f, scrollDir, false)
                        lastTwoFingerY = midY
                        twoFingerScrolled = true
                        hasMoved = true
                    }
                    val idx = event.findPointerIndex(secondFingerId)
                    if (idx >= 0) {
                        val d2 = hypot(
                            (event.getX(idx) - secondFingerDownX).toDouble(),
                            (event.getY(idx) - secondFingerDownY).toDouble()
                        ).toFloat()
                        if (d2 > 30f) {
                            twoFingerScrolled = true
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isDragging) {
                    // The finger holding the drag went away: end the drag.
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, cursorX, cursorY, false)
                    isDragging = false
                }
                if (pointerCount == 2 && secondFingerId >= 0) {
                    // Hold one finger + tap with another = right click,
                    // at the first finger's (cursor) position.
                    val dur = System.currentTimeMillis() - secondFingerDownTime
                    val idx = event.findPointerIndex(secondFingerId)
                    val secondFingerMoved = if (idx >= 0) {
                        hypot(
                            (event.getX(idx) - secondFingerDownX).toDouble(),
                            (event.getY(idx) - secondFingerDownY).toDouble()
                        ).toFloat() > 30f
                    } else {
                        false
                    }
                    if (!twoFingerScrolled && !secondFingerMoved && dur < 500L) {
                        sendClick(2)
                    }
                    secondFingerId = -1
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - touchDownTime
                val dist = hypot((event.x - touchDownX).toDouble(), (event.y - touchDownY).toDouble()).toFloat()

                if (isDragging) {
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, cursorX, cursorY, false)
                    isDragging = false
                } else if (maxPointers == 1 && !hasMoved &&
                    dist < 14f && duration < 320L) {
                    sendClick(1)
                }

                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, false)
                maxPointers = 1
                hasMoved = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Back key menu
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_DOWN) {
            toggleMenu()
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    private fun toggleMenu() {
        if (menuPopup?.isShowing == true) {
            menuPopup?.dismiss()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val drawerWidth = dp(320)   // tablet max width (5 x 64dp)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // MD1 temporary navigation drawer: full-height panel sliding in from
        // the left, resting elevation 16dp, with a scrim over the content.
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(16).toFloat()
        }

        fun addSubheader(text: String) {
            drawer.addView(TextView(this).apply {
                this.text = text
                typeface = medium
                textSize = 14f
                setTextColor(0x8A000000.toInt())
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                )
                setPadding(dp(16), 0, dp(16), 0)
            })
        }

        val ripple = RippleDrawable(
            ColorStateList.valueOf(0x40000000.toInt()),
            ColorDrawable(Color.TRANSPARENT),
            null
        )

        fun addRow(label: String, checked: Boolean = false, onClick: () -> Unit) {
            drawer.addView(TextView(this).apply {
                text = if (checked) "✓ $label" else "  $label"
                typeface = medium
                // Selected item switches to 100% black (MD1 drawer selection state).
                textSize = 14f
                setTextColor(if (checked) Color.BLACK else 0xDE000000.toInt())
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                )
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple.constantState?.newDrawable() ?: ripple
                setOnClickListener { onClick() }
            })
        }

        addSubheader("输入方式")
        addRow("触摸板模式", touchMode == TouchMode.TOUCHPAD) {
            setTouchMode(TouchMode.TOUCHPAD)
        }
        addRow("触控模式", touchMode == TouchMode.DIRECT) {
            setTouchMode(TouchMode.DIRECT)
        }
        addSubheader("画面")
        for (scale in Anime4kScale.values()) {
            addRow("Anime4K ${scale.label}", anime4kScale == scale) {
                anime4kScale = scale
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_ANIME4K_SCALE, scale.name)
                    .apply()
                try {
                    nativeSetAnime4kMode(scale.ordinal)
                } catch (e: UnsatisfiedLinkError) {
                }
                menuPopup?.dismiss()
                Toast.makeText(
                    this,
                    "Anime4K：${scale.label}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        addSubheader("工具")
        addRow("修改器（开发中）") {
            Toast.makeText(this, "内置修改器开发中，敬请期待", Toast.LENGTH_SHORT).show()
        }
        // Full-bleed divider with 8dp padding above and below.
        drawer.addView(View(this).apply {
            setBackgroundColor(0x1F000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        })
        addRow("退出游戏") {
            menuPopup?.dismiss()
            finish()
        }

        // Scrim root: tapping outside the drawer dismisses it.
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { menuPopup?.dismiss() }
        }
        root.addView(
            drawer,
            FrameLayout.LayoutParams(drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
        )

        val pw = PopupWindow(
            root,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        )
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { menuPopup = null }

        menuPopup = pw
        pw.showAtLocation(window.decorView, Gravity.TOP or Gravity.START, 0, 0)

        // Slide in from the left over a fading scrim.
        drawer.translationX = -drawerWidth.toFloat()
        root.alpha = 0f
        root.post {
            drawer.animate().translationX(0f).setDuration(200L).start()
            root.animate().alpha(1f).setDuration(200L).start()
        }
    }

    private fun setTouchMode(mode: TouchMode) {
        touchMode = mode
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_TOUCH_MODE, mode.name)
            .apply()
        menuPopup?.dismiss()
        updateCursor(cursorX, cursorY)
        val name = if (mode == TouchMode.TOUCHPAD) "触摸板模式" else "触控模式"
        Toast.makeText(this, "已切换：$name", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    override fun getLibraries(): Array<String> {
        return arrayOf("SDL2", "xsystem4")
    }

    override fun getArguments(): Array<String> {
        val saveFolder = intent.getStringExtra(EXTRA_SAVE_DIR)!!
        val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
        return arrayOf("--save-folder", saveFolder, "--save-format=rsm", gameRoot)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            // Let SDL stop and join its native thread before terminating the game process.
            super.onDestroy()
        } finally {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onUnhandledMessage(command: Int, param: Any): Boolean {
        when (command) {
            COMMAND_OPEN_PLAYING_MANUAL -> {
                val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
                val manualDir = File(gameRoot, "Manual")
                if (manualDir.isDirectory) {
                    val intent = Intent(this, ManualActivity::class.java).apply {
                        val url = "file://${manualDir.absolutePath}/index.html"
                        putExtra(ManualActivity.EXTRA_URL, url)
                    }
                    startActivity(intent)
                }
                return true
            }
        }
        return super.onUnhandledMessage(command, param)
    }
}
