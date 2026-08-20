package com.cayatur.winbridge.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.AudioStart
import com.cayatur.winbridge.protocol.AudioStop
import com.cayatur.winbridge.protocol.InputKey
import com.cayatur.winbridge.protocol.InputMouse
import com.cayatur.winbridge.protocol.InputScroll
import com.cayatur.winbridge.protocol.InputText
import com.cayatur.winbridge.protocol.MediaPacket
import com.cayatur.winbridge.protocol.StreamIds
import com.cayatur.winbridge.protocol.StreamStart
import com.cayatur.winbridge.protocol.StreamStats
import com.cayatur.winbridge.protocol.StreamStop
import com.cayatur.winbridge.service.BridgeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Views the PC screen and drives it.
 *
 * Deliberately not Compose. This screen repaints on every frame at up to sixty
 * hertz and its whole job is a bitmap and a touch handler; a recomposition pass
 * per frame would be work for nothing. The chrome around it is plain views for
 * the same reason.
 */
class PcScreenActivity : Activity() {

    private lateinit var surface: PcScreenView
    private lateinit var status: TextView
    private lateinit var keyboardTrap: EditText
    private lateinit var touchMode: Button
    private lateinit var audioButton: Button

    private val app get() = WinBridgeApp.instance
    private var audioOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)

        // The point of a mirror is to watch it. Letting the screen time out
        // mid-session would be a bug report.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildLayout())
        active = this

        wireSurface()
        observe()
        start()
    }

    // ---- layout -------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#FFB8B8C0"))
            textSize = 13f
            setPadding(24, 18, 24, 12)
            text = getString(R.string.pc_screen_connecting)
        }
        root.addView(status)

        surface = PcScreenView(this)
        val holder = FrameLayout(this)
        holder.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // An off-screen field is the only reliable way to get a soft keyboard to
        // produce arbitrary text for something that is not a text view. Its
        // content is read and cleared on every change, so it never accumulates.
        keyboardTrap = EditText(this).apply {
            alpha = 0f
            isCursorVisible = false
            setBackgroundColor(Color.TRANSPARENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val typed = s?.toString().orEmpty()
                    if (typed.isEmpty()) return
                    s?.clear()
                    send(InputText(text = typed))
                }
            })
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> { send(InputKey(code = "backspace")); true }
                    KeyEvent.KEYCODE_ENTER -> { send(InputKey(code = "enter")); true }
                    else -> false
                }
            }
        }
        holder.addView(keyboardTrap, FrameLayout.LayoutParams(1, 1))

        root.addView(
            holder,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(buildControls())
        return root
    }

    private fun buildControls(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF17171B"))
            setPadding(8, 8, 8, 8)
        }

        touchMode = button(getString(R.string.pc_screen_touch)) {
            surface.directTouch = !surface.directTouch
            touchMode.text = if (surface.directTouch) getString(R.string.pc_screen_touch) else "Trackpad"
        }
        bar.addView(touchMode)

        bar.addView(
            button(getString(R.string.pc_screen_keyboard)) {
                keyboardTrap.requestFocus()
                getSystemService(InputMethodManager::class.java)
                    .showSoftInput(keyboardTrap, InputMethodManager.SHOW_IMPLICIT)
            },
        )

        audioButton = button(getString(R.string.pc_screen_audio)) { toggleAudio() }
        bar.addView(audioButton)

        bar.addView(button("Esc") { send(InputKey(code = "escape")) })
        bar.addView(button("Win") { send(InputKey(code = "win")) })
        bar.addView(button("Fit") { surface.resetZoom() })

        return bar
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setPadding(18, 8, 18, 8)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = 6 }
    }

    // ---- wiring -------------------------------------------------------------

    private fun wireSurface() {
        surface.onMouse = { action, x, y, buttonName ->
            when (action) {
                "relative" -> send(InputMouse(action = "move", dx = x, dy = y, relative = true))
                else -> send(InputMouse(action = action, x = x, y = y, button = buttonName))
            }
        }
        surface.onScroll = { x, y, dy ->
            send(InputScroll(x = x, y = y, dy = dy))
        }
    }

    private fun observe() {
        app.scope.launch {
            app.state.pcStream.collectLatest { info ->
                if (info == null) return@collectLatest
                runOnUiThread {
                    if (info.active) {
                        surface.configure(info)
                        status.text = "${info.width}×${info.height}"
                    } else {
                        status.text = info.reason ?: getString(R.string.pc_screen_off)
                    }
                }
            }
        }

        // The sender walks quality, frame rate and resolution from what actually
        // arrives here, so this is not optional bookkeeping — without it the
        // stream never adapts.
        app.scope.launch {
            var lastFrames = 0
            var lastBytes = 0L
            while (true) {
                delay(1000)
                val frames = surface.framesPresented - lastFrames
                val bytes = surface.bytesReceived - lastBytes
                lastFrames = surface.framesPresented
                lastBytes = surface.bytesReceived

                runCatching {
                    app.client.sendMessage(
                        StreamStats(
                            stream = StreamIds.name(StreamIds.PC_SCREEN),
                            fps = frames.toDouble(),
                            kbps = bytes * 8 / 1000.0,
                            latencyMs = 0,
                        ),
                    )
                }
            }
        }
    }

    private fun start() {
        val store = app.store
        app.scope.launch {
            runCatching {
                app.client.sendMessage(
                    StreamStart(
                        stream = StreamIds.name(StreamIds.PC_SCREEN),
                        maxFps = store.viewPcMaxFps,
                        quality = store.viewPcQuality,
                        maxEdge = store.viewPcMaxEdge,
                        interact = store.viewPcInteract,
                        audio = store.viewPcAudio,
                        cursor = true,
                    ),
                )
            }
        }
        audioOn = store.viewPcAudio
        updateAudioLabel()
    }

    private fun toggleAudio() {
        audioOn = !audioOn
        app.scope.launch {
            runCatching {
                if (audioOn) {
                    app.client.sendMessage(AudioStart(stream = StreamIds.name(StreamIds.PC_AUDIO)))
                } else {
                    app.client.sendMessage(AudioStop(stream = StreamIds.name(StreamIds.PC_AUDIO)))
                }
            }
        }
        updateAudioLabel()
    }

    private fun updateAudioLabel() {
        audioButton.text = if (audioOn) "🔊" else "🔇"
    }

    private fun send(message: Any) {
        app.scope.launch {
            runCatching {
                when (message) {
                    is InputMouse -> app.client.sendMessage(message)
                    is InputScroll -> app.client.sendMessage(message)
                    is InputKey -> app.client.sendMessage(message)
                    is InputText -> app.client.sendMessage(message)
                }
            }
        }
    }

    /** Called from the sink for every screen packet while this is on top. */
    fun onPacket(packet: MediaPacket) = surface.onPacket(packet)

    override fun onDestroy() {
        if (active === this) active = null

        app.scope.launch {
            runCatching {
                app.client.sendMessage(StreamStop(stream = StreamIds.name(StreamIds.PC_SCREEN)))
                app.client.sendMessage(AudioStop(stream = StreamIds.name(StreamIds.PC_AUDIO)))
            }
        }
        com.cayatur.winbridge.feature.AudioPlayback.stop(StreamIds.PC_AUDIO)
        super.onDestroy()
    }

    companion object {
        /**
         * The live viewer, if any. Screen packets are routed straight here rather
         * than through a flow: a StateFlow would coalesce frames and a
         * SharedFlow would allocate a subscription per packet, and neither is
         * worth it for something with exactly one consumer.
         */
        @Volatile
        var active: PcScreenActivity? = null
            private set
    }
}
