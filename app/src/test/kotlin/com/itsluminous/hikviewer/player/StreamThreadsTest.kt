package com.itsluminous.hikviewer.player

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamThreadsTest {
    @Test
    fun `pipelines run on named daemon threads at background priority`() = runBlocking {
        val (name, daemon, priority) =
            withContext(StreamThreads.dispatcher) {
                val t = Thread.currentThread()
                Triple(t.name, t.isDaemon, Process.getThreadPriority(Process.myTid()))
            }
        assertThat(name).startsWith(StreamThreads.THREAD_PREFIX)
        assertThat(daemon).isTrue()
        assertThat(priority).isEqualTo(Process.THREAD_PRIORITY_BACKGROUND)
    }
}
