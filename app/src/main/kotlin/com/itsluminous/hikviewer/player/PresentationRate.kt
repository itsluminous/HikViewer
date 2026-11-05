package com.itsluminous.hikviewer.player

/**
 * How many of a tile's decoded pictures are shown: every one, or every second one when the grid
 * is large. **Switched off** ([ENABLED]) until it has been measured on the TV with real streams.
 *
 * This is a presentation budget, not a decoding one. Every P-frame still has to be decoded
 * (it is the reference of the next), so `media.codec` costs exactly the same; what a skipped
 * picture saves is the step after decoding: the buffer is not handed to SurfaceFlinger, so there
 * is no composition, no GPU upload and no vsync spent on it. In a 16-tile grid on the MiTV the
 * software tiles' pictures already share a 15 Hz cadence ([RenderPacer]) so that one composition
 * shows them all; halving the presented rate halves the buffers each composition has to blend.
 * Whether that is worth a 6 fps picture on eight tiles is exactly what has to be measured
 * (`gfxinfo` janky %, idle CPU, with 16 tiles at the recorder's 12 fps), and the recorder was
 * offline the night this was written, so the policy ships disabled: [divisor] answers
 * [FULL_RATE] for every tile, and the plumbing ([TileDecoder.presentEvery], the coordinator's
 * assignment) is in place for the measurement. `docs/FOLLOW-UPS.md` has the recipe.
 *
 * Rules once enabled: with [REDUCED_RATE_TILE_THRESHOLD] tiles or fewer every tile presents at
 * the stream's rate; above it the software grid tiles present every [REDUCED_DIVISOR]th picture
 * (6 fps for a 12 fps recorder), the hero (and the single tile of a single-camera screen) always
 * presents every picture. Keyframe-only tiles are not touched: they are already at one still a
 * second, and whether one of them gets a live decoder is the [LiveBudget]'s decision from its
 * idle-CPU probe; once it does, it presents at the grid's rate like any other software tile. A
 * tile presenting at the reduced rate still counts as [TileLiveness.LIVE]: pictures reach the
 * screen six times a second, and the dot and the status line say "live" for it.
 */
object PresentationRate {
    /**
     * Whether the reduced rate is ever applied. False until the MiTV measurement says it helps;
     * flipping it is the whole switch, the plumbing and the tests of the rule are in place.
     */
    const val ENABLED: Boolean = false

    /**
     * Grids with more tiles than this present their software tiles at the reduced rate. Eight is
     * the MiTV's live ceiling on a 12 fps recorder: below it every live tile is one of eight and
     * the composer has room; above it keyframe tiles' still uploads compete with the live tiles
     * for the same GPU, which is where `gfxinfo` showed 56-76 % janky frames in round 6.
     */
    const val REDUCED_RATE_TILE_THRESHOLD: Int = 8

    /** Every Nth decoded picture is shown above the threshold. */
    const val REDUCED_DIVISOR: Int = 2

    /** Every picture. */
    const val FULL_RATE: Int = 1

    /**
     * The divisor for one tile: 1 means every decoded picture is presented, 2 every second one.
     * [isHero] is the tile shown large (or alone); it is never reduced. Always 1 while [ENABLED]
     * is false; [rule] is the policy itself, for the tests and for the day it is switched on.
     */
    fun divisor(
        tileCount: Int,
        isHero: Boolean,
    ): Int = if (ENABLED) rule(tileCount, isHero) else FULL_RATE

    /** The policy regardless of the switch. */
    fun rule(
        tileCount: Int,
        isHero: Boolean,
    ): Int = if (tileCount > REDUCED_RATE_TILE_THRESHOLD && !isHero) REDUCED_DIVISOR else FULL_RATE
}
