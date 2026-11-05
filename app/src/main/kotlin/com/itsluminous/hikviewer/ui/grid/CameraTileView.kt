package com.itsluminous.hikviewer.ui.grid

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.databinding.ViewCameraTileBinding
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.player.TileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One camera in the grid: a `SurfaceView` for the decoder, the camera name, a small spinner while
 * nothing has been drawn yet, and a dim scrim with a centred Retry while the stream is down.
 *
 * Focus is made unmistakable on a TV viewed from the couch: the tile scales up 6 %, draws a 4 dp
 * accent border and rises above its neighbours, animated over 120 ms. A tile picked up for a
 * move ([lifted]) scales 10 % and rises further. A mouse pointer entering the tile requests focus
 * so hover behaves like D-pad navigation; click/OK/tap are the normal click and a long press
 * (held OK, long click or long touch) is the long click, which the grid turns into a menu. The
 * surface is handed to the owner through [onSurface] whenever it is created or destroyed.
 */
class CameraTileView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        private val binding = ViewCameraTileBinding.inflate(LayoutInflater.from(context), this)

        /** Receives the live surface, or null when it is gone. */
        var onSurface: ((android.view.Surface?) -> Unit)? = null

        /** The video surface. */
        val surfaceView: View get() = binding.surface

        /** True while the dimmed Retry overlay is up; the owner routes OK to a retry then. */
        val showsRetry: Boolean get() = binding.errorOverlay.visibility == VISIBLE

        /** True while the user is moving this tile: lifted above its neighbours. */
        var lifted: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                applyTransform(animate = true)
            }

        init {
            binding.name.visibility = GONE
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            isLongClickable = true
            setBackgroundResource(R.drawable.tile_background)
            binding.surface.setZOrderMediaOverlay(false)
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
            }
        }

        /** No player draws here any more: surface events go nowhere. */
        fun detachPlayer() {
            onSurface = null
        }

        /** Shows the spinner, the Retry overlay or neither for [state]. Idempotent: no view is recreated. */
        fun bind(state: TileState) {
            binding.spinner.visibility = if (state == TileState.Connecting) VISIBLE else GONE
            if (state is TileState.Error) {
                binding.errorMessage.text = state.message
                binding.errorOverlay.visibility = VISIBLE
            } else {
                binding.errorOverlay.visibility = GONE
            }
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            applyTransform(animate = true)
            binding.focusFrame.visibility = if (gainFocus) VISIBLE else INVISIBLE
        }

        private fun applyTransform(animate: Boolean) {
            val scale =
                when {
                    lifted -> LIFT_SCALE
                    isFocused -> FOCUS_SCALE
                    else -> 1f
                }
            val z =
                when {
                    lifted -> resources.getDimension(R.dimen.tile_lift_elevation)
                    isFocused -> resources.getDimension(R.dimen.tile_focus_elevation)
                    else -> 0f
                }
            if (animate) {
                animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(z)
                    .setDuration(FOCUS_ANIMATION_MILLIS)
                    .start()
            } else {
                scaleX = scale
                scaleY = scale
                translationZ = z
            }
        }

        /** Focus visuals. */
        companion object {
            /** 6 % larger when focused. */
            const val FOCUS_SCALE: Float = 1.06f

            /** 10 % larger while being moved. */
            const val LIFT_SCALE: Float = 1.1f

            /** Focus animation length. */
            const val FOCUS_ANIMATION_MILLIS: Long = 120
        }
    }
