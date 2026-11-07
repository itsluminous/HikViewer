package com.itsluminous.hikviewer.ui.settings

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A `RecyclerView` that is as tall as all of its rows, for a short list inside a ScrollView.
 *
 * A ScrollView measures its content with an UNSPECIFIED height whose size is the ScrollView's own
 * height, and each wrapping parent on the way down subtracts what sits above the list. A
 * `LinearLayoutManager` reads that size as the space to fill and stops laying out rows once it is
 * used up, so the camera list of a 640 dp phone showed two rows under the sections above it and
 * clipped the rest. Measured with a size of zero instead, the manager knows the height is
 * unbounded, lays out every row and reports their total, and the ScrollView does the scrolling.
 * Nothing recycles, which is the point: there are a few dozen cameras at most and
 * [androidx.recyclerview.widget.ItemTouchHelper] needs a RecyclerView to drag rows.
 */
class AllRowsRecyclerView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : RecyclerView(context, attrs, defStyleAttr) {
        override fun onMeasure(
            widthSpec: Int,
            heightSpec: Int,
        ) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
    }
