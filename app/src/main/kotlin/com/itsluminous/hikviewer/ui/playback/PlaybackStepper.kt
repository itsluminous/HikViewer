package com.itsluminous.hikviewer.ui.playback

import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.core.stream.AccessUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * Fast forward and reverse for recorded playback: one keyframe every [cadenceMillis], each
 * `factor` seconds of footage apart, out of one continuous stream to the player.
 *
 * The relay has no speed or seek control on this path and sends a window at real time (25 fps
 * for 25 fps of footage, `PLAYBACK.md`), so nothing faster than 1x can come out of a single
 * open stream, forward or backward; the only way to a picture ten seconds further on is to ask
 * for a window there (random access costs ~0.45 s to the first picture). The earlier design did
 * exactly that, one reopen of the player per hop, and the screen showed the reopen: a picture,
 * the connecting spinner for the two seconds the next window took, the next picture.
 *
 * This class separates the two: the **player sees one stream** ([frames]) for the whole time a
 * fast speed is on, every picture of it an IRAP with its parameter sets, so its decoder is never
 * flushed or rebuilt between steps and its state never leaves `Live` (no spinner). Behind it the
 * stepper opens windows on its own ([openWindow]), one [window] long at the time of each step it
 * is about to need, up to [prefetchSteps] steps ahead and at most [MAX_FETCHES] at a time, and
 * keeps the keyframes they deliver in a bounded ring keyed by recorder time. A ticker presents
 * the ring's entry for the current step every [cadenceMillis]; when the step's keyframe has not
 * arrived yet the last picture stays on screen and only the time label moves, and the keyframe
 * is shown the moment it arrives unless the next step is already due. A seek clears the ring and
 * starts over at the new time; a speed change keeps it (the same keyframes serve either
 * direction) and only turns the walk around.
 *
 * Window length is a trade-off the relay decides: at real-time delivery a window longer than one
 * GOP only ties a relay session up for footage no step will show, so [WINDOW] is a GOP plus the
 * relay's rounding and each fetch is cancelled once it has delivered the steps it covers. A
 * relay that turned out to deliver faster than real time would let one long window serve many
 * steps (the fetch stays open while its window still covers an upcoming step), which this class
 * already does for any [window] it is given; see `docs/FOLLOW-UPS.md` for the measurement.
 *
 * Presented pictures carry a synthetic, increasing presentation time: a decoder shown pictures
 * whose times run backwards may hold or reorder them, and the time on screen is the step's
 * recorder time ([onPosition]), not the stream's clock. Runs entirely on [scope] (the owner's
 * main-thread scope); the window flows do their own I/O on their own dispatcher.
 */
class PlaybackStepper(
    private val segments: List<RecordSegment>,
    private val openWindow: (PlaybackWindow) -> Flow<AccessUnit>,
    private val scope: CoroutineScope,
    /** The playhead for the time label, called on [scope]. */
    private val onPosition: (LocalDateTime) -> Unit,
    /** The footage ran out in the direction of travel; the stepper has stopped itself. */
    private val onEnded: () -> Unit,
    private val cadenceMillis: Long = CADENCE_MILLIS,
    private val window: Duration = WINDOW,
    private val prefetchSteps: Int = PREFETCH_STEPS,
    ringCapacity: Int = RING_CAPACITY,
) {
    private val out =
        MutableSharedFlow<AccessUnit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The player's stream: one keyframe per presented step, every one with its parameter sets. */
    val frames: Flow<AccessUnit> = out

    private val ring = KeyframeRing(ringCapacity)
    private val fetches = ArrayList<Fetch>()
    private var ticker: Job? = null
    private var factor = 0
    private var target: LocalDateTime? = null

    /** The step whose keyframe was not there when it was due; shown when it arrives. */
    private var due: LocalDateTime? = null

    /** Recorder time of the picture on screen. */
    private var shown: LocalDateTime? = null
    private var presented = 0
    private var late = 0
    private var opened = 0
    private var failed = 0

    private val direction: Int get() = if (factor < 0) -1 else 1
    private val stepSeconds: Long get() = Math.abs(factor) * cadenceMillis / MILLIS_PER_SECOND

    /** How far a keyframe may sit from its step: a GOP or two, but always less than a stride. */
    private val tolerance: Long get() = minOf(KeyframeRing.TOLERANCE_SECONDS, stepSeconds - 1).coerceAtLeast(0)

    /** Starts stepping from [position] at [factor] (never 0 or 1). */
    fun start(
        position: LocalDateTime,
        factor: Int,
    ) {
        require(factor != 0 && factor != 1) { "stepping needs a fast speed, not $factor" }
        this.factor = factor
        restartAt(position)
    }

    /**
     * Turns the walk around or changes its stride; the ring's keyframes stay. The next picture is
     * one new stride from the one on screen, not from the step that was already lined up.
     */
    fun setSpeed(factor: Int) {
        require(factor != 0 && factor != 1) { "stepping needs a fast speed, not $factor" }
        if (this.factor == factor) return
        this.factor = factor
        val from = due ?: shown ?: target
        due = null
        if (from != null) target = from.plusSeconds(direction * stepSeconds)
        reconcileFetches()
        ensureFetched()
    }

    /** The user is choosing a new time: nothing moves until [seekTo] (or [stop]). */
    fun pause() {
        ticker?.cancel()
        ticker = null
        cancelFetches()
        due = null
    }

    /** Forgets every keyframe and starts over at [position] in the current direction. */
    fun seekTo(position: LocalDateTime) {
        ring.clear()
        restartAt(position)
    }

    /** Stops the ticker and every window; the player's stream stays open but gets nothing more. */
    fun stop() {
        pause()
        target = null
    }

    /** One line for the diagnostics log. */
    fun describe(): String =
        "stepper factor=$factor target=${target?.let(PlaybackTimeline::label)} ring=${ring.size} " +
            "fetches=${fetches.size} opened=$opened failed=$failed presented=$presented late=$late"

    private fun restartAt(position: LocalDateTime) {
        pause()
        target = position
        shown = null
        ticker =
            scope.launch {
                while (true) {
                    step()
                    delay(cadenceMillis)
                }
            }
    }

    /** Presents the current step, moves the target on, and sees that the next steps are on their way. */
    private fun step() {
        var t = target ?: return
        val hop = PlaybackTimeline.windowForHop(segments, t, direction, window)
        if (hop == null) {
            end()
            return
        }
        // Landed in a gap: the window snapped into the nearest footage, follow it there.
        if (t.isBefore(hop.begin) || !t.isBefore(hop.end)) t = hop.begin
        val entry = ring.serve(t, tolerance)
        if (entry != null) {
            present(entry)
        } else {
            due = t
            late++
            onPosition(t)
        }
        target = t.plusSeconds(direction * stepSeconds)
        reconcileFetches()
        ensureFetched()
    }

    private fun present(entry: KeyframeRing.Entry) {
        due = null
        shown = entry.time
        val u = entry.unit
        val pts = (presented++) * MICROS_PER_SECOND
        out.tryEmit(AccessUnit(u.codec, u.data, pts, isKeyframe = true, config = u.config))
        onPosition(entry.time)
    }

    private fun end() {
        stop()
        onEnded()
    }

    /** The steps about to be presented: the one still waiting for its picture, then the next ones. */
    private fun upcoming(): List<LocalDateTime> {
        val first = target ?: return emptyList()
        return listOfNotNull(due) + (0 until prefetchSteps).map { i -> first.plusSeconds(direction * stepSeconds * i) }
    }

    /**
     * The steps from the one waiting (or the next) onwards, as far as a window could reach, that
     * fall inside [fetch]'s window.
     */
    private fun stepsInside(fetch: Fetch): List<LocalDateTime> {
        val first = due ?: target ?: return emptyList()
        val reach = (window.seconds / stepSeconds).toInt() + prefetchSteps + 1
        return (0 until reach).map { i -> first.plusSeconds(direction * stepSeconds * i) }.filter(fetch::covers)
    }

    /** Opens a window for every upcoming step the ring and the open windows do not cover. */
    private fun ensureFetched() {
        for (t in upcoming()) {
            if (fetches.size >= MAX_FETCHES) return
            val hop = windowFor(t) ?: continue
            fetches += Fetch(hop).also { it.start() }
        }
    }

    /** The window to open for the step at [t], or null when the ring or an open window covers it already. */
    private fun windowFor(t: LocalDateTime): PlaybackWindow? {
        if (ring.has(t, tolerance) || fetches.any { it.covers(t) }) return null
        val hop = PlaybackTimeline.windowForHop(segments, t, direction, window) ?: return null
        return hop.takeUnless { h -> fetches.any { it.window == h } }
    }

    /**
     * Closes windows that have nothing more to give: every step ahead that they cover is in the
     * ring already, or (after a seek or a turn) they cover none. A relay session is not kept open
     * for footage no step will show; one that still covers steps to come stays open however far
     * ahead they are, which is what lets a relay faster than real time serve a long window.
     */
    private fun reconcileFetches() {
        val done = fetches.filter { f -> stepsInside(f).all { ring.has(it, tolerance) } }
        done.forEach { it.cancel() }
        fetches.removeAll(done)
    }

    private fun cancelFetches() {
        fetches.forEach { it.cancel() }
        fetches.clear()
    }

    /** One window being read into the ring. */
    private inner class Fetch(
        val window: PlaybackWindow,
    ) {
        private var job: Job? = null
        private var firstPtsUs: Long? = null
        private var keyframes = 0

        fun covers(t: LocalDateTime): Boolean = !t.isBefore(window.begin) && t.isBefore(window.end)

        fun start() {
            opened++
            job =
                scope.launch {
                    openWindow(window)
                        .filter { it.isKeyframe && it.config != null }
                        .catch { failed++ } // the step is asked for again at the next tick
                        .collect(::onKeyframe)
                    fetches.remove(this@Fetch)
                }
        }

        fun cancel() {
            job?.cancel()
            job = null
        }

        private fun onKeyframe(unit: AccessUnit) {
            // The window's first keyframe is taken to be at its begin: the relay starts at the
            // GOP holding the asked-for time and the stream's clock says nothing about wall time.
            val pts = unit.presentationTimeUs
            val base = firstPtsUs ?: pts.also { firstPtsUs = it }
            val offsetMillis =
                if (pts != null && base != null) (pts - base) / MICROS_PER_MILLI else keyframes * MILLIS_PER_SECOND
            keyframes++
            val time = window.begin.plusNanos(offsetMillis * NANOS_PER_MILLI)
            ring.add(time, unit)
            val waiting = due
            if (waiting != null) ring.serve(waiting, tolerance)?.let(::present)
            reconcileFetches()
        }
    }

    /** Tunables. */
    companion object {
        /** One picture a second on screen at every fast speed; `factor` seconds of footage between them. */
        const val CADENCE_MILLIS: Long = 1_000

        /** Footage asked for per step: one GOP plus the relay's rounding (see the class note on length). */
        val WINDOW: Duration = Duration.ofSeconds(3)

        /** Steps whose windows are opened ahead of their time; two covers a first picture 1.5 s away. */
        const val PREFETCH_STEPS: Int = 2

        /** Windows open at once: the step being shown and the next; more would risk the relay's 6103. */
        const val MAX_FETCHES: Int = 2

        /** Keyframes kept; a minute of footage at a one-second GOP. */
        const val RING_CAPACITY: Int = 64

        private const val MILLIS_PER_SECOND = 1_000L
        private const val MICROS_PER_MILLI = 1_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
