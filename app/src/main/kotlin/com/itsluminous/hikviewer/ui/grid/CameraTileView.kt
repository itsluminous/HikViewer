package com.itsluminous.hikviewer.ui.grid

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.databinding.ViewCameraTileBinding
import com.itsluminous.hikviewer.player.StillSink
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.player.TileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One camera in the grid: a `SurfaceView` for the decoder, the camera name, a small spinner while
 * nothing has been drawn yet, a dim scrim with a centred retry icon while the stream is down, and a
 * dot in the top-right corner that is green while pictures reach the screen and red otherwise
 * ([framesFlowing]; the player samples it twice a second, so the dot never redraws per frame).
 *
 * Focus is a 4 dp accent border drawn by [TileFocusOverlay], the tile's last child, entirely
 * inside the tile; a tile picked up for a move ([lifted]) gets a thicker border and an accent
 * wash. The border hides after 5 s without input ([focusVisualShown], driven by the grid) and the
 * tile keeps focus meanwhile. Nothing in the tile is scaled or elevated, and the `SurfaceView`
 * never moves: animating the tile re-positioned the surface on every frame, which on Android 10
 * races the surface's own creation/destruction (`SurfaceView.positionChanged →
 * applySurfaceTransforms`, a use-after-free in `libgui`) and crashed the MiTV at launch while 16
 * surfaces were being created.
 *
 * The tile clips its children, and that is load-bearing. A `SurfaceView` below the window punches
 * its hole in `draw()`; up to Android 11 that is `canvas.drawColor(0, CLEAR)` over the *current
 * clip*, and a child of a `clipChildren = false` parent is not clipped to its own bounds, so the
 * clear reaches the nearest clipping ancestor: the whole grid. Twelve surfaces then each wipe
 * every dot, label and border drawn before them, and only the last-drawn tile keeps its overlays
 * (the round-4 symptom: one dot, a focus border only when the focused tile happened to be drawn
 * last). Android 12+ punches exactly the view's bounds, which is why the API 34 emulator never
 * showed it. The surfaces are media overlays so they stack above any other surface (a decoder's
 * sideband layer, say) but still below the window that carries the overlays.
 *
 * A mouse pointer entering the tile requests focus so hover behaves like D-pad navigation;
 * click/OK/tap are the normal click and a long press (held OK, long click or long touch) is the
 * long click, which the grid turns into a menu. The surface is handed to the owner through
 * [onSurface] whenever it is created or destroyed.
 */
class CameraTileView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs),
        StillSink {
        private val binding = ViewCameraTileBinding.inflate(LayoutInflater.from(context), this)

        private val mainThread = Handler(Looper.getMainLooper())

        /** Two thumbnail bitmaps: the pool writes the back one while the front one is on screen. */
        private var frontStill: Bitmap? = null
        private var backStill: Bitmap? = null

        /** Receives the live surface, or null when it is gone. */
        var onSurface: ((android.view.Surface?) -> Unit)? = null

        /** The video surface. */
        val surfaceView: View get() = binding.surface

        /** True while the dimmed Retry overlay is up; the owner routes OK to a retry then. */
        val showsRetry: Boolean get() = binding.errorOverlay.visibility == VISIBLE

        /** True while the user is moving this tile: thicker border and an accent wash. */
        var lifted: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                binding.focusFrame.lifted = value
            }

        /** True when the focus border is drawn: the tile has focus (and the visuals are shown) or is being moved. */
        val focusBorderVisible: Boolean get() = binding.focusFrame.borderVisible

        /**
         * Whether a focused tile draws its border. The grid turns this off on every tile after 5 s
         * without a key or pointer and back on at the next one, so a parked remote leaves a clean
         * picture; focus itself is untouched (a hidden border still belongs to the focused tile).
         */
        var focusVisualShown: Boolean
            get() = binding.focusFrame.shown
            set(value) {
                binding.focusFrame.shown = value
            }

        init {
            binding.name.visibility = GONE
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            isLongClickable = true
            setBackgroundResource(R.drawable.tile_background)
            // Never false: see the class KDoc on the Android 10 hole punch.
            clipChildren = true
            // The retry icon is the tile's own click (OK on the tile retries as well), so one code
            // path reopens the stream however the user got there; the icon grows a tenth with focus
            // since the tile's border cannot say which of the two holds it. The icon is an ordinary
            // view inside the scrim: scaling it never touches the surface.
            binding.retry.setOnClickListener { performClick() }
            binding.retry.setOnFocusChangeListener { button, focused ->
                val scale = if (focused) RETRY_FOCUS_SCALE else 1f
                button.scaleX = scale
                button.scaleY = scale
            }
            binding.surface.setZOrderMediaOverlay(true)
            binding.surface.holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurface?.invoke(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurface?.invoke(null)
                    }
                },
            )
            setOnHoverListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER && !isFocused) requestFocus()
                false
            }
        }

        /** Camera name shown in the label (when [showName]). */
        var cameraName: CharSequence
            get() = binding.name.text
            set(value) {
                binding.name.text = value
            }

        /** Whether the name label is drawn; off by default because the feed burns the name in. */
        var showName: Boolean = false
            set(value) {
                field = value
                binding.name.visibility = if (value) VISIBLE else GONE
            }

        /** Whether the fps counter is drawn. */
        var showFrameRate: Boolean = false
            set(value) {
                field = value
                binding.fps.visibility = if (value) VISIBLE else GONE
            }

        /** Green dot while true (pictures within the last ~2 s), red while false. */
        var framesFlowing: Boolean = false
            set(value) {
                field = value
                val color = if (value) R.color.live_dot_flowing else R.color.live_dot_stalled
                binding.liveDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
            }

        /** Rendered frames per second for the counter; whole numbers read better from a couch. */
        var frameRate: Float = 0f
            set(value) {
                field = value
                binding.fps.text = resources.getString(R.string.tile_fps, Math.round(value))
            }

        /**
         * Makes [player] draw into this tile: its surface is handed over now (if it already exists)
         * and again whenever it is created or destroyed, and the player's state and frame rate are
         * mirrored onto the overlays until the returned job is cancelled. [onState] sees every state
         * too, for the owner's own bookkeeping. One place for what four screens used to repeat.
         */
        fun attachPlayer(
            player: TilePlayer,
            scope: CoroutineScope,
            onState: (TileState) -> Unit = {},
        ): Job {
            onSurface = player::attachSurface
            player.attachStillSink(this)
            // The holder's surface is invalid before creation and after destruction; only a live one is handed over.
            binding.surface.holder.surface
                ?.takeIf { it.isValid }
                ?.let(player::attachSurface)
            return scope.launch {
                launch {
                    player.state.collect { state ->
                        bind(state)
                        onState(state)
                    }
                }
                launch { player.frameRate.collect { frameRate = it } }
                launch { player.framesFlowing.collect { framesFlowing = it } }
            }
        }

        /** No player draws here any more: surface events go nowhere. */
        fun detachPlayer() {
            onSurface = null
        }

        /**
         * Shows the spinner, the Retry overlay or neither for [state]. Idempotent: no view is
         * recreated. A thumbnail stays up only while the tile is in keyframe mode; a live picture or
         * a spinner must not be hidden behind the last still.
         */
        fun bind(state: TileState) {
            binding.spinner.visibility = if (state == TileState.Connecting) VISIBLE else GONE
            if (state != TileState.Keyframe) binding.still.visibility = GONE
            if (state is TileState.Error) {
                binding.errorMessage.text = state.message
                binding.errorOverlay.visibility = VISIBLE
            } else {
                binding.errorOverlay.visibility = GONE
            }
        }

        override val stillWidth: Int get() = width.coerceAtMost(STILL_MAX_WIDTH)

        override val stillHeight: Int get() = if (width == 0) 0 else height * stillWidth / width

        /** On the pool's thread: paints the back bitmap, then swaps it in on the main thread. */
        override fun onStill(
            pixels: IntArray,
            width: Int,
            height: Int,
        ) {
            val target =
                backStill?.takeIf { it.width == width && it.height == height }
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            target.setPixels(pixels, 0, width, 0, 0, width, height)
            // Not View.post: that waits for the view to be attached to a window, and a decoded
            // picture that arrives a moment before attachment must still be shown.
            mainThread.post {
                backStill = frontStill
                frontStill = target
                binding.still.setImageBitmap(target)
                binding.still.visibility = VISIBLE
            }
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            binding.focusFrame.focused = gainFocus
        }

        /** Sizes. */
        companion object {
            /** Thumbnails are never wider than this: a 480-wide still of a 960x480 stream is plenty for a tile. */
            const val STILL_MAX_WIDTH: Int = 480

            /** The retry icon grows this much while it holds focus. */
            const val RETRY_FOCUS_SCALE: Float = 1.1f
        }
    }
