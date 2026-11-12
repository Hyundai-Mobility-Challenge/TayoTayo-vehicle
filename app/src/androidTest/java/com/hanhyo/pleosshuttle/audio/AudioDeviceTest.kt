package com.hanhyo.pleosshuttle.audio

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Observes actual Android media/focus APIs, never claims that a human heard the output. */
@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    @get:org.junit.Rule val isolatedTrip = com.hanhyo.pleosshuttle.IsolatedTripRule()
    @Test fun `로컬_시험_음원은_재생_위치가_진행하고_일시정지와_정지에_반응한다`() {
        val checkpoints = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var adapter: LocalAudioAdapter
            scenario.onActivity { activity ->
                adapter = LocalAudioAdapter(activity) { }
                adapter.play()
            }
            try {
                Thread.sleep(600)
                var pausedPosition = 0
                scenario.onActivity {
                    adapter.refresh()
                    checkpoints.put(record("playing", adapter.snapshot))
                    assertTrue(adapter.snapshot.events.joinToString("\n"), adapter.snapshot.isPlaying)
                    assertTrue(adapter.snapshot.playbackPositionMillis > 0)
                    assertTrue(adapter.snapshot.durationMillis > 0)
                    adapter.pause()
                    adapter.refresh()
                    pausedPosition = adapter.snapshot.playbackPositionMillis
                    checkpoints.put(record("paused", adapter.snapshot))
                    assertFalse(adapter.snapshot.isPlaying)
                    assertNull(adapter.snapshot.outputRouteLabel)
                }
                Thread.sleep(250)
                scenario.onActivity {
                    adapter.refresh()
                    assertTrue(kotlin.math.abs(pausedPosition - adapter.snapshot.playbackPositionMillis) <= 50)
                    adapter.play()
                }
                Thread.sleep(250)
                scenario.onActivity {
                    adapter.refresh()
                    checkpoints.put(record("resumed", adapter.snapshot))
                    assertTrue(adapter.snapshot.isPlaying)
                    adapter.onBackground()
                    adapter.onForeground()
                    adapter.refresh()
                    checkpoints.put(record("returnedFromBackground", adapter.snapshot))
                    assertFalse(adapter.snapshot.isPlaying)
                    adapter.stop()
                    adapter.refresh()
                    checkpoints.put(record("stopped", adapter.snapshot))
                    assertFalse(adapter.snapshot.isPlaying)
                    assertEquals(0, adapter.snapshot.playbackPositionMillis)
                    assertEquals(0, adapter.snapshot.durationMillis)
                    assertNull(adapter.snapshot.outputRouteLabel)
                }
            } finally {
                scenario.onActivity { adapter.close() }
                save("audio-player-device.json", checkpoints)
            }
        }
    }

    @Test fun `실제_포커스_시험의_요청과_콜백을_기록하고_수동정지_후_재생하지_않는다`() {
        val checkpoints = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var adapter: LocalAudioAdapter
            scenario.onActivity { activity ->
                adapter = LocalAudioAdapter(activity) { }
                adapter.play()
                assertTrue(adapter.snapshot.events.joinToString("\n"), adapter.snapshot.isPlaying)
                adapter.beginFocusProbe(mayDuck = false)
            }
            try {
                Thread.sleep(600)
                scenario.onActivity {
                    adapter.refresh()
                    checkpoints.put(record("transientProbeHeld", adapter.snapshot))
                    if (adapter.snapshot.focusState.contains("TRANSIENT_LOSS")) {
                        assertFalse(adapter.snapshot.isPlaying)
                    }
                    adapter.pause()
                    adapter.endFocusProbe()
                }
                Thread.sleep(350)
                scenario.onActivity {
                    adapter.refresh()
                    checkpoints.put(record("manualPauseAfterProbeReturn", adapter.snapshot))
                    assertFalse(adapter.snapshot.isPlaying)
                    adapter.play()
                    adapter.beginFocusProbe(mayDuck = true)
                }
                Thread.sleep(600)
                scenario.onActivity {
                    adapter.refresh()
                    checkpoints.put(record("duckProbeHeld", adapter.snapshot))
                    if (adapter.snapshot.focusState.contains("CAN_DUCK")) {
                        assertEquals(.2f, adapter.snapshot.volume, .001f)
                    }
                    adapter.endFocusProbe()
                }
                Thread.sleep(350)
                scenario.onActivity {
                    adapter.refresh()
                    checkpoints.put(record("duckProbeReturned", adapter.snapshot))
                    if (adapter.snapshot.focusState.endsWith("GAIN")) {
                        assertEquals(1f, adapter.snapshot.volume, .001f)
                    }
                }
            } finally {
                scenario.onActivity { adapter.close() }
                save("audio-focus-device.json", checkpoints)
            }
        }
    }

    private fun record(label: String, snapshot: AudioSnapshot) = JSONObject().apply {
        put("checkpoint", label)
        put("timeMillis", snapshot.observedAtMillis)
        put("musicState", snapshot.musicState)
        put("focusState", snapshot.focusState)
        put("isPlaying", snapshot.isPlaying)
        put("positionMillis", snapshot.playbackPositionMillis)
        put("durationMillis", snapshot.durationMillis)
        put("outputRouteLabel", snapshot.outputRouteLabel ?: JSONObject.NULL)
        put("applicationVolume", snapshot.volume.toDouble())
        put("events", JSONArray(snapshot.events))
        put("audibleOutputVerified", false)
        put("nativeNavigationVoiceVerified", false)
    }

    private fun save(name: String, checkpoints: JSONArray) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.filesDir.resolve(name).writeText(JSONObject().apply {
            put("scope", "Android MediaPlayer and local OS focus probe; not native navigation speech")
            put("checkpoints", checkpoints)
        }.toString(2))
    }
}
