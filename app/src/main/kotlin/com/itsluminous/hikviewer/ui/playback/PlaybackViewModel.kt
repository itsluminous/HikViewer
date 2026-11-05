package com.itsluminous.hikviewer.ui.playback

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.player.StreamOpener
import com.itsluminous.hikviewer.player.TilePlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Which control the D-pad acts on. Logical, not view focus: the controls hide while playing. */
enum class PlaybackControl {
    /** The 24 h bar: LEFT/RIGHT seek, OK pauses. */
    BAR,

    /** The date chip: LEFT/RIGHT change the day. */
    DATE,

    /** The speed button: LEFT/RIGHT/OK cycle the speed. */
    SPEED,
}

/** What the screen is doing besides showing pictures. */
enum class PlaybackPhase {
    /** Listing the day's recordings. */
    LOADING,

    /** The day has no footage. */
    NO_RECORDINGS,

    /** A window is open or about to be. */
    PLAYING,

    /** Stopped by the user; the last picture stays. */
    PAUSED,

    /** The recording ran out in the direction of travel. */
    ENDED,

    /** The record list could not be fetched. */
    ERROR,
}

/** Everything the playback screen renders apart from the video itself. */
data class PlaybackUiState(
    /** Recorder date shown on the bar. */
    val day: LocalDate,
    /** Footage on that day. */
    val segments: List<RecordSegment> = emptyList(),
    /** The playhead (recorder time). */
    val position: LocalDateTime = day.atStartOfDay(),
    /** Speed button position. */
    val speed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    /** Transport state. */
    val phase: PlaybackPhase = PlaybackPhase.LOADING,
    /** The control the D-pad acts on. */
    val control: PlaybackControl = PlaybackControl.BAR,
) {
    /** True while footage is meant to be moving. */
    val isPlaying: Boolean get() = phase == PlaybackPhase.PLAYING
}

/**
 * Drives recorded playback of one camera: lists the day's segments, opens windows on a single
 * hardware [TilePlayer], and turns seek/speed/pause into window reopens, because the relay offers
 * no seek, pause or speed control over this path (see `PlaybackStream`).
 *
 *  * **1x** opens `[position, segment end]` and lets the relay stream in real time; the playhead
 *    follows the pictures' presentation times. When the window ends the next segment is opened.
 *  * **Seek** moves the playhead (and the label) at once; the stream is reopened there only after
 *    [SeekController.DEBOUNCE_MILLIS] without further seek input, so a burst of presses, a held
 *    key or a drag along the bar opens one window. A held key accelerates on the controller's
 *    schedule and the playhead is clamped to the day's footage.
 *  * **Other speeds** step through keyframes ([PlaybackStepper]): one picture a second, each
 *    `factor` seconds of footage apart, forward or backward, out of one stream to the player
 *    whose every picture is a keyframe ([TilePlayer.keyframesOnly] guards that too). The stepper
 *    opens the relay windows itself, ahead of time, so the player never reconnects between two
 *    pictures and never shows the spinner; a step whose picture is late keeps the last one and
 *    moves the time label. A speed change between two fast speeds turns the stepper around in
 *    place; a seek while stepping restarts it at the new time.
 *  * **Pause** stops the stream and keeps the last picture; resume reopens at the playhead.
 *
 * A switch between 1x and stepping, and a seek at 1x, reopens the player's stream but keeps its
 * one decoder ([TilePlayer.reopen]): the codec is flushed and admits nothing until the next
 * keyframe, so the old picture stays until the new one is decoded instead of a blank surface, and
 * the hardware slot is never released and re-acquired. The stream is only stopped for good
 * ([TilePlayer.stop]) when the screen goes away, the footage ends or the user pauses.
 *
 * Callbacks from the stream arrive on worker threads; everything that touches the player or the
 * timers is re-dispatched to the main thread through the engine's scope: [viewModelScope] on the
 * TV playback screen, or the owning ViewModel's scope when the phone screen embeds one engine
 * per camera ([externalScope]); that owner calls [close] when it drops the engine.
 */
class PlaybackViewModel(
    /** The camera whose recording plays. */
    val camera: Camera,
    private val account: AccountRepository,
    factory: PlaybackFactory,
    startDay: LocalDate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Time between two pictures at a fast speed; tests shorten it. */
    private val stepMillis: Long = PlaybackStepper.CADENCE_MILLIS,
    /** Monotonic milliseconds for the seek timing; tests pass their scheduler's time. */
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(PlaybackUiState(day = startDay))

    /** For the controls. */
    val state: StateFlow<PlaybackUiState> = stateFlow.asStateFlow()

    /** The single hardware player; the Activity binds its surface and overlay state. */
    val player: TilePlayer =
        factory.createSinglePlayer(
            camera,
            StreamOpener {
                _,
                _,
                onStatus,
                ->
                openCurrentWindow(onStatus)
            },
        )

    /** Watchdog period for [TilePlayer.tick]. */
    val watchdogMillis: Long = factory.watchdogMillis

    private var window: PlaybackWindow? = null

    // Written on the main thread when a window opens, read on the stream thread for every picture.
    @Volatile private var generation = 0

    @Volatile private var firstPtsUs: Long? = null

    @Volatile private var firstPictureAt: Long? = null

    /**
     * True from the first seek step until the seek is committed: the stream keeps delivering
     * pictures meanwhile, and letting them move the playhead would throw the seek away (the
     * reopen would land where the stream was, not where the user pointed).
     */
    @Volatile private var seeking = false
    private val seek = SeekController(clock)

    // Set on the main thread before the player reopens; read on the stream thread by the opener.
    @Volatile private var stepper: PlaybackStepper? = null
    private var seekJob: Job? = null
    private var started = false
    private val scope: CoroutineScope get() = externalScope ?: viewModelScope

    /**
     * Lists the day's footage for the time bar without playing anything: the phone screen shows
     * the bar under the live picture and only opens a window once the user scrubs.
     */
    fun listDay() {
        if (stateFlow.value.segments.isEmpty() && stateFlow.value.phase == PlaybackPhase.LOADING) {
            loadDay(stateFlow.value.day, initial = true)
        }
    }

    /** Stops everything for good; what [onCleared] does, for an owner that is not a ViewModelStore. */
    fun close() {
        started = false
        stopTransportAndHold()
    }

    /** The screen is visible: list the day and play. */
    fun onStart() {
        started = true
        if (stateFlow.value.segments.isEmpty() && stateFlow.value.phase == PlaybackPhase.LOADING) {
            loadDay(stateFlow.value.day, initial = true)
        } else if (stateFlow.value.phase == PlaybackPhase.PLAYING) {
            resumeTransport()
        }
    }

    /** The screen is gone: stop the stream and the timers (the phase is kept for a later resume). */
    fun onStop() {
        started = false
        stopTransportAndHold()
    }

    /** LEFT/RIGHT on the date chip: show [delta] days later (never after [today]). */
    fun changeDay(
        delta: Int,
        today: LocalDate = LocalDate.now(),
    ) {
        val day = stateFlow.value.day.plusDays(delta.toLong())
        if (day.isAfter(today)) return
        stopTransportAndHold()
        stateFlow.update {
            it.copy(day = day, segments = emptyList(), phase = PlaybackPhase.LOADING, position = day.atStartOfDay())
        }
        loadDay(day, initial = false)
    }

    /** UP/DOWN: cycle the logical focus BAR → DATE → SPEED. */
    fun moveControl(delta: Int) {
        val entries = PlaybackControl.entries
        stateFlow.update { it.copy(control = entries[(it.control.ordinal + delta).mod(entries.size)]) }
    }

    /**
     * LEFT/RIGHT pressed or auto-repeated on the bar (+1 forward, -1 back): the playhead moves by
     * the hold-dependent step at once; the reopen waits for the debounce.
     */
    fun seekBy(direction: Int) {
        val seconds = seek.press() * direction
        moveTarget { s -> PlaybackTimeline.shift(s.day, s.position, seconds) }
    }

    /** The seek key went up: the next press starts at the smallest step again. */
    fun endSeekHold() {
        seek.release()
    }

    /** A tap, click or drag sample on the bar at [fraction] of the day. */
    fun seekToFraction(fraction: Float) {
        seek.input()
        moveTarget { s -> PlaybackTimeline.timeAt(s.day, fraction) }
    }

    /** Moves the playhead to [target] of the state, clamped to the footage, and arms the debounced reopen. */
    private fun moveTarget(target: (PlaybackUiState) -> LocalDateTime) {
        seeking = true
        stepper?.pause()
        stateFlow.update { it.copy(position = PlaybackTimeline.clampToRecorded(it.day, it.segments, target(it))) }
        if (seekJob?.isActive == true) return
        seekJob =
            scope.launch {
                while (true) {
                    val wait = seek.dueIn() ?: return@launch
                    if (wait == 0L) break
                    delay(wait)
                }
                seekJob = null
                commitSeek()
            }
    }

    /** Reopens the stream at the playhead now; the debounce calls this once the quiet time has run out. */
    fun commitSeek() {
        seekJob?.cancel()
        seekJob = null
        seek.settle()
        seeking = false
        val s = stateFlow.value
        if (s.phase == PlaybackPhase.PAUSED ||
            s.phase == PlaybackPhase.LOADING ||
            s.phase == PlaybackPhase.ERROR
        ) {
            return
        }
        if (s.segments.isEmpty()) return
        stateFlow.update { it.copy(phase = PlaybackPhase.PLAYING) }
        val running = stepper
        if (running != null && !s.speed.isRealTime) {
            // The ring is cleared and the walk starts over at the new time; the player's stream
            // stays open, so the picture on screen is replaced by the one at the new time with no
            // spinner in between.
            running.seekTo(s.position)
        } else {
            resumeTransport()
        }
    }

    /** OK on the bar. */
    fun togglePause() {
        when (stateFlow.value.phase) {
            PlaybackPhase.PLAYING -> {
                stopTransportAndHold()
                stateFlow.update { it.copy(phase = PlaybackPhase.PAUSED) }
            }
            PlaybackPhase.PAUSED, PlaybackPhase.ENDED -> {
                if (stateFlow.value.segments.isEmpty()) return
                stateFlow.update { it.copy(phase = PlaybackPhase.PLAYING) }
                resumeTransport()
            }
            else -> Unit
        }
    }

    /** OK or RIGHT on the speed button (LEFT with [forward] false). */
    fun cycleSpeed(forward: Boolean = true) {
        val speed = if (forward) stateFlow.value.speed.next() else stateFlow.value.speed.previous()
        setSpeed(speed)
    }

    /** Switches to [speed]; takes effect at once while playing. */
    fun setSpeed(speed: PlaybackSpeed) {
        val previous = stateFlow.value.speed
        if (speed == previous) return
        stateFlow.update { it.copy(speed = speed) }
        if (!stateFlow.value.isPlaying) return
        val running = stepper
        if (running != null && !speed.isRealTime && !seeking) {
            running.setSpeed(speed.factor) // the same stream, the same keyframes, a new stride or direction
        } else {
            resumeTransport()
        }
    }

    /**
     * Periodic watchdog from the Activity: recreates a wedged decoder, and every
     * [DIAGNOSTICS_EVERY_TICKS] ticks logs the player's state line (decoder in/out counts, the
     * keyframe gate, flushes), which is what a TV logcat of a playback problem is read with.
     */
    fun tick() {
        // The player stack stamps everything with System.nanoTime, so the watchdog gets that clock.
        val now = System.nanoTime() / NANOS_PER_MILLI
        player.tick(now)
        if (++ticks % DIAGNOSTICS_EVERY_TICKS == 0) {
            val stepping = stepper?.describe()?.let { " $it" } ?: ""
            Log.i(TAG, "${stateFlow.value.speed.label} ${stateFlow.value.phase}$stepping ${player.diagnostics(now)}")
        }
    }

    private var ticks = 0

    override fun onCleared() {
        close()
    }

    // --- Day loading --------------------------------------------------------------------------

    private fun loadDay(
        day: LocalDate,
        initial: Boolean,
    ) {
        scope.launch {
            val segments =
                try {
                    withContext(io) { account.searchRecordings(camera, day) }
                } catch (e: IOException) {
                    Log.w(TAG, "${camera.key}: cannot list recordings for $day: ${e.message}")
                    stateFlow.update { it.copy(phase = PlaybackPhase.ERROR) }
                    return@launch
                }
            if (stateFlow.value.day != day) return@launch
            if (segments.isEmpty()) {
                stateFlow.update { it.copy(segments = emptyList(), phase = PlaybackPhase.NO_RECORDINGS) }
                return@launch
            }
            val start = if (initial) startPosition(segments) else segments.first().begin
            stateFlow.update { it.copy(segments = segments, position = start, phase = PlaybackPhase.PLAYING) }
            if (started) resumeTransport()
        }
    }

    /** Today opens near the end of the footage (what just happened); any other day at its start. */
    private fun startPosition(segments: List<RecordSegment>): LocalDateTime {
        val last = segments.last()
        return maxOf(last.begin, last.end.minus(INITIAL_LOOKBACK))
    }

    // --- Transport ----------------------------------------------------------------------------

    private fun resumeTransport() {
        stopTransport()
        val speed = stateFlow.value.speed
        player.keyframesOnly = !speed.isRealTime
        if (speed.isRealTime) playFrom(stateFlow.value.position) else startStepping()
    }

    /**
     * [stopTransport], and the seek key's hold is forgotten too: the screen, the day or the
     * playing state is going away, so a key-up may never arrive and the next press must start
     * small again. A plain [stopTransport] (a speed change, a seek commit) keeps the hold of a
     * key that is still down.
     */
    private fun stopTransportAndHold() {
        seek.release()
        stopTransport()
        player.stop()
    }

    /**
     * Stops every timer and forgets the current window; a pending debounced reopen is dropped
     * (whoever restarts reopens at the playhead). The player is left alone: the caller either
     * reopens it ([open], which keeps the decoder) or stops it for good.
     */
    private fun stopTransport() {
        stepper?.stop()
        stepper = null
        seekJob?.cancel()
        seekJob = null
        seek.settle()
        seeking = false
        generation++
    }

    private fun playFrom(time: LocalDateTime) {
        val s = stateFlow.value
        val next = PlaybackTimeline.windowForward(s.segments, time, MAX_WINDOW)
        if (next == null) {
            player.stop()
            stateFlow.update { it.copy(phase = PlaybackPhase.ENDED) }
            return
        }
        stateFlow.update { it.copy(position = next.begin, phase = PlaybackPhase.PLAYING) }
        open(next)
    }

    /** Opens one stream to the player (the stepper's pictures) and starts the stepper behind it. */
    private fun startStepping() {
        val s = stateFlow.value
        val stepping =
            PlaybackStepper(
                segments = s.segments,
                openWindow = { w -> account.openPlayback(camera, w) },
                scope = scope,
                onPosition = ::onStepped,
                onEnded = ::onSteppingEnded,
                cadenceMillis = stepMillis,
            )
        stepper = stepping
        window = null
        generation++
        player.reopen(scope)
        stepping.start(s.position, s.speed.factor)
    }

    private fun onStepped(time: LocalDateTime) {
        if (seeking) return
        stateFlow.update { if (it.isPlaying) it.copy(position = time) else it }
    }

    private fun onSteppingEnded() {
        player.stop()
        stateFlow.update { it.copy(phase = PlaybackPhase.ENDED) }
    }

    private fun open(next: PlaybackWindow) {
        window = next
        generation++
        firstPtsUs = null
        firstPictureAt = null
        player.reopen(scope)
    }

    private fun openCurrentWindow(onStatus: (StreamStatus) -> Unit): Flow<AccessUnit> {
        val stepping = stepper
        if (stepping != null) {
            // One stream for the whole fast-speed session: the player is "streaming" from here
            // on and every picture it gets is a keyframe the stepper chose.
            onStatus(StreamStatus.Connecting(1))
            onStatus(StreamStatus.Streaming)
            return stepping.frames
        }
        val w = checkNotNull(window) { "no playback window" }
        val myGeneration = generation
        return account
            .openPlayback(camera, w) { status ->
                onStatus(status)
                if (status is StreamStatus.Ended) onWindowEnded(myGeneration)
            }.onEach { unit -> onPicture(unit, w, myGeneration) }
    }

    private fun onPicture(
        unit: AccessUnit,
        w: PlaybackWindow,
        myGeneration: Int,
    ) {
        if (myGeneration != generation || seeking || !stateFlow.value.speed.isRealTime) return
        val now = clock()
        val pts = unit.presentationTimeUs
        val elapsedMillis =
            when {
                pts != null -> {
                    val base = firstPtsUs ?: pts.also { firstPtsUs = it }
                    (pts - base) / MICROS_PER_MILLI
                }
                else -> {
                    val base = firstPictureAt ?: now.also { firstPictureAt = it }
                    now - base
                }
            }
        if (elapsedMillis < 0) return
        val position = w.begin.plusNanos(elapsedMillis * NANOS_PER_MILLI)
        stateFlow.update { if (it.isPlaying) it.copy(position = minOf(position, w.end)) else it }
    }

    private fun onWindowEnded(myGeneration: Int) {
        scope.launch {
            val s = stateFlow.value
            if (myGeneration != generation || !s.isPlaying || !s.speed.isRealTime) return@launch
            // Roll into the next segment from the end of the window just played.
            val from = window?.end ?: s.position
            playFrom(from)
        }
    }

    /** Tunables. */
    companion object {
        /** Longest window asked for at 1x; a reopen every hour keeps the playhead honest. */
        val MAX_WINDOW: Duration = Duration.ofHours(1)

        /** Where today's playback starts: a few minutes before the latest footage. */
        val INITIAL_LOOKBACK: Duration = Duration.ofMinutes(5)

        /** The player's state line is logged every this many watchdog ticks (every 5 s at the default 500 ms). */
        const val DIAGNOSTICS_EVERY_TICKS: Int = 10

        private const val TAG = "PlaybackViewModel"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MICROS_PER_MILLI = 1_000L
    }
}
