package com.itsluminous.hikviewer.core.testutil

/** Loads files from `src/test/resources/fixtures`. */
object Fixtures {
    fun bytes(name: String): ByteArray =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)
}
