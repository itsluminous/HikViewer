package com.itsluminous.hikviewer.ui.grid

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.databinding.ViewCameraTileBinding
import com.itsluminous.hikviewer.player.TileState

/**
 * One camera in the grid: a `SurfaceView` for the decoder, the camera name and a state badge.
 *
 * Focus is made unmistakable on a TV viewed from the couch: the tile scales up 6 %, draws a 4 dp
 * accent border and rises above its neighbours, animated over 120 ms. A mouse pointer entering
 * the tile requests focus so hover behaves like D-pad navigation; click/OK/tap are the normal
 * click. The surface is handed to the owner through [onSurface] whenever it is created or destroyed.
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

        init {
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
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

        /** Camera name shown in the label. */
        var cameraName: CharSequence
            get() = binding.name.text
            set(value) {
                binding.name.text = value
            }

        /** Updates the badge for [state]. */
        fun bind(state: TileState) {
            val badge = binding.badge
            when (state) {
                TileState.Connecting -> badge.setText(R.string.tile_connecting)
                TileState.Live -> badge.setText(R.string.tile_live)
                TileState.Keyframe -> badge.setText(R.string.tile_keyframe)
                is TileState.Error -> badge.text = state.message
            }
            badge.setTextColor(
                when (state) {
                    TileState.Live -> context.getColor(R.color.badge_live)
                    is TileState.Error -> context.getColor(R.color.badge_error)
                    else -> Color.WHITE
                },
            )
            // The surface is opaque black until the first frame; hide it behind the badge while
            // nothing renders so an error tile does not look like a dark camera.
            binding.surface.alpha = if (state is TileState.Error) 0f else 1f
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            val scale = if (gainFocus) FOCUS_SCALE else 1f
            animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(if (gainFocus) resources.getDimension(R.dimen.tile_focus_elevation) else 0f)
                .setDuration(FOCUS_ANIMATION_MILLIS)
                .start()
            binding.focusFrame.visibility = if (gainFocus) VISIBLE else INVISIBLE
        }

        /** Focus visuals. */
        companion object {
            /** 6 % larger when focused. */
            const val FOCUS_SCALE: Float = 1.06f

            /** Focus animation length. */
            const val FOCUS_ANIMATION_MILLIS: Long = 120
        }
    }
