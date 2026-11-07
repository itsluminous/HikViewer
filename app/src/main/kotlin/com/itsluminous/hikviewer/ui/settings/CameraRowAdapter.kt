package com.itsluminous.hikviewer.ui.settings

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.databinding.ItemCameraChoiceBinding

/**
 * The rows of Settings → Visible cameras and order, bound to the [SettingsViewModel]'s
 * [CameraOrderEditor]. Every row has a checkbox (tick = visible) and one of two ways to move:
 *
 * - **Television** ([touch] false): the text handle at the row's end. OK picks the row up (it
 *   says "moving"), UP/DOWN move it one step, OK drops it and persists the order.
 * - **Phone** ([touch] true): a drag handle at the row's start. Holding it starts an
 *   [ItemTouchHelper] drag from its ACTION_DOWN (long-press drag is off, so a long press on the
 *   row does nothing and the checkbox keeps toggling as usual); the row follows the finger, and
 *   letting go persists the order through the same ViewModel write as the TV's drop.
 *
 * Rows are addressed by their binding position at the time of the event, never an index captured
 * at bind, since a move changes positions without rebinding. The RecyclerView lives inside the
 * Settings ScrollView with nested scrolling off, so every row is laid out and nothing recycles;
 * the adapter is used for [ItemTouchHelper], which needs one.
 */
class CameraRowAdapter(
    private val viewModel: SettingsViewModel,
    /** True on a phone: the drag handle instead of the D-pad handle. */
    private val touch: Boolean,
    /** Runs after a tick changes which cameras are visible (the layout spinner follows the count). */
    private val onVisibilityChanged: () -> Unit,
) : RecyclerView.Adapter<CameraRowAdapter.Row>() {
    private val editor: CameraOrderEditor get() = viewModel.editor
    private var touchHelper: ItemTouchHelper? = null

    /** The drag rules a phone's [ItemTouchHelper] follows (up and down from the handle, drop persists); unused on a TV. */
    internal val dragCallback: ItemTouchHelper.Callback = DragCallback()

    /** One camera row; [onChecked] is the tick listener, detached while a bind sets the state. */
    class Row(
        val binding: ItemCameraChoiceBinding,
        val onChecked: CompoundButton.OnCheckedChangeListener,
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        if (!touch) return
        touchHelper = ItemTouchHelper(dragCallback).also { it.attachToRecyclerView(recyclerView) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
    }

    override fun getItemCount(): Int = editor.items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Row {
        val binding = ItemCameraChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.dragHandle.visibility = if (touch) View.VISIBLE else View.GONE
        binding.handle.visibility = if (touch) View.GONE else View.VISIBLE
        lateinit var row: Row
        row =
            Row(binding) { _, _ ->
                val position = row.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    viewModel.toggleVisible(position)
                    onVisibilityChanged()
                }
            }
        if (touch) bindDragHandle(row) else bindDpadHandle(row)
        return row
    }

    override fun onBindViewHolder(
        holder: Row,
        position: Int,
    ) {
        val choice = editor.items[position]
        val b = holder.binding
        // The listener fires on a programmatic change too; a rebind must never flip the store.
        b.check.setOnCheckedChangeListener(null)
        b.check.isChecked = choice.visible
        b.check.setOnCheckedChangeListener(holder.onChecked)
        b.name.text = choice.camera.name
        b.signal.setText(
            if (choice.camera.hasSignal) R.string.settings_camera_signal else R.string.settings_camera_no_signal,
        )
        b.moving.visibility = if (editor.pickedUp == position) View.VISIBLE else View.GONE
    }

    /** TV: OK picks up or drops, UP/DOWN move the picked-up row; the handle keeps focus as it moves. */
    private fun bindDpadHandle(row: Row) {
        val handle = row.binding.handle
        handle.setOnClickListener {
            val position = row.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            val before = editor.pickedUp
            val moving = viewModel.togglePickUp(position)
            // This row's label at once (the key that follows may move it); the row that was picked up rebinds.
            row.binding.moving.visibility = if (moving) View.VISIBLE else View.GONE
            if (before != null && before != position) notifyItemChanged(before)
        }
        handle.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || editor.pickedUp == null) return@setOnKeyListener false
            val delta =
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    else -> return@setOnKeyListener false
                }
            val from = row.bindingAdapterPosition
            val to = viewModel.moveBy(delta) ?: return@setOnKeyListener true
            notifyItemMoved(from, to)
            // The moved holder keeps focus; its position changed, nothing was rebound.
            handle.requestFocus()
            true
        }
    }

    /**
     * Phone: the drag starts the moment the handle is touched, not after a long press, so the
     * row follows the finger at once. The handle has no click of its own (the row's checkbox is
     * the control), which is why the accessibility click rule does not apply to it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindDragHandle(row: Row) {
        row.binding.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(row)
            false
        }
    }

    /** Drag up and down only, from the handle only; a drop persists like a D-pad drop. */
    private inner class DragCallback : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            viewModel.moveRow(from, to)
            notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(
            viewHolder: RecyclerView.ViewHolder,
            direction: Int,
        ) = Unit

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ) {
            super.clearView(recyclerView, viewHolder)
            viewModel.dropRow()
        }
    }
}
