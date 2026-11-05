package com.itsluminous.hikviewer.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A frame as wide as it is given and 16:9 tall, for the phone's live picture: the video keeps its
 * shape in portrait and the controls take the rest of the screen below it.
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
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = width * HEIGHT_RATIO / WIDTH_RATIO
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
