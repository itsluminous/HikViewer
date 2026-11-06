package com.itsluminous.hikviewer.ui

/**
 * Deadline for the `awaitUi` / `await` pollers in the Activity tests. Streams and decoders run on real
 * worker threads under Robolectric, and in a full-suite run those threads compete with the other test
 * classes for the machine: the previous 5 s tripped once on an otherwise healthy run (a tile test that
 * passes alone in under a second). 20 s only decides how long a real failure takes to report.
 */
internal const val UI_DEADLINE_MILLIS = 20_000L

/** How often the pollers pump the main looper and re-check their condition. */
internal const val UI_POLL_MILLIS = 10L
