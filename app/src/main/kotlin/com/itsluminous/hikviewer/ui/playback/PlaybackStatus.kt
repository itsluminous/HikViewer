package com.itsluminous.hikviewer.ui.playback

import androidx.annotation.StringRes
import com.itsluminous.hikviewer.R

/** The status line's text for this phase, or null while footage is moving and there is nothing to say. */
val PlaybackPhase.statusRes: Int?
    @StringRes
    get() =
        when (this) {
            PlaybackPhase.LOADING -> R.string.playback_loading
            PlaybackPhase.NO_RECORDINGS -> R.string.playback_no_recordings
            PlaybackPhase.ERROR -> R.string.playback_error
            PlaybackPhase.ENDED -> R.string.playback_ended
            PlaybackPhase.PAUSED -> R.string.playback_paused
            PlaybackPhase.PLAYING -> null
        }

/**
 * True for the phases about the day's listing rather than the transport. The phone screen shows
 * these even while live (an empty day explains why a tap on the bar does nothing); ENDED and
 * PAUSED only make sense while footage is on screen.
 */
val PlaybackPhase.aboutTheDay: Boolean
    get() = this == PlaybackPhase.LOADING || this == PlaybackPhase.NO_RECORDINGS || this == PlaybackPhase.ERROR
