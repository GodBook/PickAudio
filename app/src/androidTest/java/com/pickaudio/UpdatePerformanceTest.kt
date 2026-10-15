package com.pickaudio

import android.os.SystemClock
import android.os.Trace
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.model.Track
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same public production API is measured before and after the update on the USB device. */
@RunWith(AndroidJUnit4::class)
class UpdatePerformanceTest {
    @Test fun batchQueueCostAndPersistence() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication
        val coordinator = app.playbackCoordinator
        val prefix = "update_performance_"
        val tracks = List(1000) { Track("$prefix$it", "性能夹具 $it", "验证", "", 180000) }
        val samples = mutableListOf<Long>()
        try {
            repeat(20) {
                withContext(Dispatchers.Main) { coordinator.clearQueue() }
                coordinator.flushPersistence()
                withContext(Dispatchers.Main) { coordinator.addTracksToQueue(tracks) }
                coordinator.flushPersistence()
                withContext(Dispatchers.Main) {
                    Trace.beginSection("PickAudio.batchQueue1000")
                    try {
                        val start = SystemClock.elapsedRealtimeNanos()
                        coordinator.addTracksToQueue(tracks)
                        samples += SystemClock.elapsedRealtimeNanos() - start
                    } finally { Trace.endSection() }
                    assertEquals(2000, coordinator.queueEntries.value.size)
                    assertEquals(2000, coordinator.queueEntries.value.map { it.id }.distinct().size)
                }
                coordinator.flushPersistence()
            }
            android.util.Log.i("PickAudioPerformance", "batchQueue1000Nanos=${samples.joinToString(",")}")
        } finally {
            withContext(Dispatchers.Main) { coordinator.clearQueue() }
            coordinator.flushPersistence()
            app.database.withTransaction { tracks.forEach { app.database.trackDao().deleteById(it.id) } }
        }
        Unit
    }
}
