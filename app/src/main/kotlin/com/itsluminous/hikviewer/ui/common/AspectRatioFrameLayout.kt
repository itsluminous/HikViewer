package com.itsluminous.hikviewer.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A 16:9 frame for the phone's live picture: as wide as it is given and 16:9 tall when the height
 * is free (portrait: the controls take the rest of the screen below it), and the largest 16:9
 * rectangle that fits when the height is bounded too (landscape: the picture fills the screen's
 * height and is centred by its parent).
 */
class AspectRatioFrameLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            var width = MeasureSpec.getSize(widthMeasureSpec)
            var height = width * HEIGHT_RATIO / WIDTH_RATIO
            val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && height > maxHeight) {
                height = maxHeight
                width = height * WIDTH_RATIO / HEIGHT_RATIO
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }

        private companion object {
            const val WIDTH_RATIO = 16
            const val HEIGHT_RATIO = 9
        }
    }
