package com.itsluminous.hikviewer.ui.settings

import androidx.lifecycle.ViewModel
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.SettingsStore

/**
 * Owns the Settings screen's camera list across configuration changes.
 *
 * The [CameraOrderEditor] used to be a field of the Activity, rebuilt from the store in
 * `onCreate`. Turning a phone recreated the Activity, and before the new one read the store the
 * framework restored the view hierarchy: every row's `CheckBox` shares the id `check`, so all of
 * them were restored from one saved state (the last, unticked row) and each restore fired the
 * checked listener, which unticked the rows one by one and saved an empty selection. Keeping the
 * editor here means the new Activity shows the list as the user left it, and the rows opt out of
 * state saving (see [SettingsActivity]) so a restore can never touch the selection again. The
 * store is written on every change, so after a process death the editor is rebuilt from it.
 */
class SettingsViewModel(
    account: AccountRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    /** The reorderable visible-camera list; survives rotation with this ViewModel. */
    val editor: CameraOrderEditor =
        CameraOrderEditor(
            account.inventory.value
                ?.cameras
                .orEmpty(),
            settings.visibleCameraKeys,
        )

    /** Ticks or unticks row [index] and persists the selection. */
    fun toggleVisible(index: Int) {
        editor.toggleVisible(index)
        save()
    }

    /** Picks row [index] up, or drops it (and persists the order) when it was picked up. Returns true while moving. */
    fun togglePickUp(index: Int): Boolean {
        val moving = editor.togglePickUp(index)
        if (!moving) save()
        return moving
    }

    /** Moves the picked-up row by [delta]; see [CameraOrderEditor.moveBy]. */
    fun moveBy(delta: Int): Int? = editor.moveBy(delta)

    /** A finger drag passed a neighbour: row [from] is now at [to]; see [CameraOrderEditor.move]. Not persisted yet. */
    fun moveRow(
        from: Int,
        to: Int,
    ) = editor.move(from, to)

    /** The dragged row was let go: the order is persisted, the same write as a D-pad drop. */
    fun dropRow() = save()

    private fun save() {
        settings.visibleCameraKeys = editor.visibleKeys()
    }
}
