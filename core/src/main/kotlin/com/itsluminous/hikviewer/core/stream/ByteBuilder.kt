package com.itsluminous.hikviewer.core.stream

/**
 * A growable byte buffer that is reused instead of re-created: `ByteArrayOutputStream` is
 * synchronized, grows by copying and is usually thrown away after one `toByteArray()`, which on
 * the stream hot path meant two or three copies of every NAL plus a fresh object per picture.
 * This one keeps its backing array across [reset], so a steady stream allocates only the final
 * [toByteArray] copy. Not thread-safe.
 */
internal class ByteBuilder(
    initialCapacity: Int = DEFAULT_CAPACITY,
) {
    private var buffer = ByteArray(initialCapacity)

    /** Bytes written since the last [reset]. */
    var size: Int = 0
        private set

    /** Appends one byte. */
    fun append(b: Byte) {
        ensureCapacity(size + 1)
        buffer[size++] = b
    }

    /** Appends `bytes[from, to)`. */
    fun append(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ) {
        val n = to - from
        if (n <= 0) return
        ensureCapacity(size + n)
        bytes.copyInto(buffer, size, from, to)
        size += n
    }

    /** The byte at [index]. */
    operator fun get(index: Int): Byte = buffer[index]

    /** Copies the contents out; the builder keeps its capacity. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    /** Copies `[from, to)` out. */
    fun toByteArray(
        from: Int,
        to: Int,
    ): ByteArray = buffer.copyOfRange(from, to)

    /** Discards the contents, keeps the capacity. */
    fun reset() {
        size = 0
    }

    /** Drops the first [count] bytes, shifting the rest down (for a sliding parse window). */
    fun dropFirst(count: Int) {
        if (count <= 0) return
        if (count >= size) {
            size = 0
            return
        }
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    /** Direct view of the backing array; valid until the next append. Only `[0, size)` is data. */
    fun array(): ByteArray = buffer

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var capacity = buffer.size
        while (capacity < needed) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 4096
    }
}
