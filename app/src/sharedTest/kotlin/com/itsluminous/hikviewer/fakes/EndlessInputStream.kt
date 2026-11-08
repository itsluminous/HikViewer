package com.itsluminous.hikviewer.fakes

import java.io.InputStream

/**
 * A stream that never ends, standing in for a video the user picked instead of a backup. It
 * records [served] and [closed] so a test can show the reader stopped at its cap rather than
 * pulling everything; past [limit] it fails loudly, so a reader without a cap cannot hang the test.
 */
class EndlessInputStream(
    private val limit: Int,
) : InputStream() {
    /** Bytes handed out so far. */
    var served: Int = 0
        private set

    /** True once [close] ran. */
    var closed: Boolean = false
        private set

    override fun read(): Int {
        serve(1)
        return 'x'.code
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = minOf(len, CHUNK)
        serve(n)
        b.fill('x'.code.toByte(), off, off + n)
        return n
    }

    override fun close() {
        closed = true
    }

    private fun serve(n: Int) {
        served += n
        check(served <= limit) { "the reader pulled more than $limit bytes: it has no cap" }
    }

    /** Most bytes one `read` hands out. */
    companion object {
        const val CHUNK: Int = 8 * 1024
    }
}
