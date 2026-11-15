package com.hanhyo.pleosshuttle.audio

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the app's voice guidance adapter (Pleos ON_DEVICE TTS), not the native navigation voice. */
@RunWith(AndroidJUnit4::class)
class SpeechDeviceTest {
    @get:org.junit.Rule val isolatedTrip = com.hanhyo.pleosshuttle.IsolatedTripRule()
    @Test fun `음성_안내를_끝까지_읽고_이어서_새_안내를_요청할_수_있다`() {
        withAudio("speech-device.json") { scenario, audio, checkpoints ->
            scenario.onActivity { audio.speak("다음 지점은 서울숲입니다.") }
            await(audio, SpeechStatus.SPEAKING)
            checkpoints.put(record("speaking", audio))
            await(audio, SpeechStatus.COMPLETED)
            checkpoints.put(record("completed", audio))
            val firstId = audio.snapshot.speechRequestId
            scenario.onActivity { audio.speak("보호자와 대면 인계를 확인해 주세요.") }
            await(audio, SpeechStatus.COMPLETED)
            assertNotEquals(firstId, audio.snapshot.speechRequestId)
            checkpoints.put(record("secondCompleted", audio))
        }
    }

    @Test fun `초기화_취소와_발화_중지_후에도_새_음성_안내를_요청할_수_있다`() {
        withAudio("speech-cancel-retry-device.json") { scenario, audio, checkpoints ->
            scenario.onActivity {
                audio.speak("이전 초기화 요청은 취소합니다.")
                audio.stopSpeech()
            }
            val cancelledId = audio.snapshot.speechRequestId
            assertEquals(SpeechStatus.STOPPED, audio.snapshot.speechStatus)
            SystemClock.sleep(800)
            assertEquals(SpeechStatus.STOPPED, audio.snapshot.speechStatus)
            checkpoints.put(record("pendingInitCancelled", audio))
            scenario.onActivity { audio.speak("다음 지점은 국립중앙박물관입니다.") }
            await(audio, SpeechStatus.SPEAKING)
            assertNotEquals(cancelledId, audio.snapshot.speechRequestId)
            scenario.onActivity { audio.stopSpeech() }
            await(audio, SpeechStatus.STOPPED)
            checkpoints.put(record("speechStopped", audio))
            scenario.onActivity { audio.speak("음성 안내를 다시 시작했습니다.") }
            await(audio, SpeechStatus.COMPLETED)
            checkpoints.put(record("retryCompleted", audio))
        }
    }

    @Test fun `앱을_벗어나면_안내를_중지하고_복귀해도_자동으로_다시_읽지_않는다`() {
        withAudio("speech-background-device.json") { scenario, audio, checkpoints ->
            scenario.onActivity { audio.speak("하차 전 주변을 직접 확인해 주세요.") }
            await(audio, SpeechStatus.SPEAKING)
            scenario.moveToState(Lifecycle.State.CREATED)
            await(audio, SpeechStatus.STOPPED)
            checkpoints.put(record("backgroundStopped", audio))
            scenario.moveToState(Lifecycle.State.RESUMED)
            SystemClock.sleep(800)
            assertEquals(SpeechStatus.STOPPED, audio.snapshot.speechStatus)
            checkpoints.put(record("foregroundStillStopped", audio))
        }
    }

    private fun withAudio(name: String, body: (ActivityScenario<MainActivity>, LocalAudioAdapter, JSONArray) -> Unit) {
        val checkpoints = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var audio: LocalAudioAdapter
            scenario.onActivity { audio = it.model.audio }
            try { body(scenario, audio, checkpoints) }
            finally {
                checkpoints.put(record("final", audio))
                scenario.onActivity { audio.stopSpeech() }
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve(name).writeText(
                    JSONObject().put("scope", "Pleos ON_DEVICE app adapter and callbacks; actual hearing checked separately")
                        .put("audibleOutputVerified", false).put("checkpoints", checkpoints).toString(2))
            }
        }
    }

    private fun await(audio: LocalAudioAdapter, expected: SpeechStatus) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var observed = SpeechStatus.IDLE
        while (SystemClock.elapsedRealtime() < deadline) {
            // finishVoice updates status before releasing the engine. Read on
            // its owning thread after the entire callback, not halfway through it from this thread.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { observed = audio.snapshot.speechStatus }
            if (observed == expected) return
            SystemClock.sleep(40)
        }
        assertEquals(audio.snapshot.toString(), expected, observed)
    }

    private fun record(label: String, audio: LocalAudioAdapter) = audio.snapshot.let { snapshot ->
        JSONObject().put("checkpoint", label).put("timeMillis", System.currentTimeMillis())
            .put("status", snapshot.speechStatus.name).put("requestId", snapshot.speechRequestId ?: JSONObject.NULL)
            .put("backend", snapshot.ttsBackend).put("ttsState", snapshot.ttsState)
            .put("events", JSONArray(snapshot.events))
    }
}
