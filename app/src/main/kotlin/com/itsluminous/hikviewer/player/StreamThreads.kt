package com.itsluminous.hikviewer.player

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The threads the stream pipelines run on: relay socket reads, demux, decryption, and the copy of
 * every picture into a decoder input buffer. One thread per open stream (they block in `read`),
 * all at [Process.THREAD_PRIORITY_BACKGROUND].
 *
 * Why not the coroutine `Dispatchers.IO`/`Default` pools: their threads run at the default
 * priority, and on the target TV (4x A55, API 29) twelve such threads plus the vendor's eight
 * `OMX.google.h264.decoder` threads (which the platform runs at -10, the same nice level as the
 * UI thread) left the main thread waiting behind decoding work. The pipelines need roughly 40 %
 * of one core in total and tolerate being scheduled late: a late read only grows the relay's TCP
 * backlog, and a late picture queues at the decoder input, where [LiveBudget] reads it as lag and
 * sheds a tile. That is the right order of sacrifice: the remote first, then the number of live
 * tiles. The codec callback thread ([TileDecoder]) is deliberately not here: releasing an output
 * buffer late holds the decoder, which would show as stutter rather than as lag.
 *
 * The pool is process-wide and grows with the number of concurrent streams (a cached pool: idle
 * threads die after a minute, so a stopped grid leaves none). The same dispatcher is used for the
 * socket side of the flow (`flowOn`) and for the collector that feeds the decoder, so the two
 * fuse into one coroutine and no picture crosses a channel.
 */
object StreamThreads {
    /** Thread name prefix, so a TV `top -H` shows what the pool costs. */
    const val THREAD_PREFIX: String = "stream-"

    private val count = AtomicInteger()

    private val factory =
        ThreadFactory { task ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                },
                THREAD_PREFIX + count.incrementAndGet(),
            ).also { it.isDaemon = true }
        }

    /** The dispatcher for stream pipelines and the players that collect them. */
    val dispatcher: CoroutineDispatcher = Executors.newCachedThreadPool(factory).asCoroutineDispatcher()
}
