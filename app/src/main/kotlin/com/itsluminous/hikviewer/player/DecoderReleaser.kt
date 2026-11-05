package com.itsluminous.hikviewer.player

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Tears decoders down off the caller's thread.
 *
 * `MediaCodec.stop()` + `release()` take tens to hundreds of milliseconds on a software decoder.
 * Until round 3 every [TilePlayer] ran them on whichever thread asked, under the player's lock:
 * the main thread (focus change, surface destroyed, the stall watchdog) and, through the lock,
 * every stream thread waiting to queue its next picture. The grid skipped 52 frames per release.
 * Now a player only detaches its decoder under the lock and hands it here; one daemon thread
 * releases them in order, which also keeps `media.codec` from being hit by several teardowns at
 * once. The thread idles for the life of the process (a releaser per screen would outlive it).
 */
object DecoderReleaser {
    /** The thread's name, so a test can prove where releases run. */
    const val THREAD_NAME: String = "codec-release"

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).also { it.isDaemon = true } }

    /**
     * Releases [decoder] on the release thread. The future completes when it is gone; a player
     * waits on it only when the surface the decoder drew into is about to be destroyed.
     */
    fun release(decoder: TileDecoder): Future<*> = executor.submit { decoder.release() }
}
