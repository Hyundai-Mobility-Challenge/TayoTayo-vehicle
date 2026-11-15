package com.hanhyo.pleosshuttle.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanhyo.pleosshuttle.MainActivity
import com.hanhyo.pleosshuttle.TripStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Audible A/B probe only: a preloaded sine, then app TTS, with the same audio attributes.
 * Both route observations and human hearing are needed; a passed test does not establish clean audio.
 */
@RunWith(AndroidJUnit4::class)
class SpeechQualityComparisonDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun `같은_안내_용도의_시험음과_한국어_발화를_차례로_완료하고_기존_운행을_보존한다`() {
        assumeTrue("Exact verified Pleos AVD required", Build.FINGERPRINT == VERIFIED_FINGERPRINT &&
            Build.HARDWARE == "ranchu" && Build.PRODUCT == "crp_avd_arm64")
        check(context.packageName == "com.hanhyo.pleosshuttle")
        val tripFile = File(context.filesDir, "trip-v1.bin")
        assumeTrue("An existing, settled trip checkpoint is required", tripFile.exists() &&
            !File(tripFile.path + ".bak").exists() && !File(tripFile.path + ".new").exists())
        val originalBytes = tripFile.readBytes()
        val originalTrip = checkNotNull(TripStore(context).load())
        // 앱은 이전 빌드의 모의 운행을 버리므로(운행 없음으로 시작) 이어서 쓸 수 있는 운행일 때만 비교한다.
        // 차량 관측 어댑터는 시작되지만 읽기 전용이다(문 제어 없음).
        assumeTrue("A restorable (institution) trip checkpoint is required", com.hanhyo.pleosshuttle.domain.StoredTrips.restorable(originalTrip) != null)
        val report = JSONObject().put("startedAtMillis", System.currentTimeMillis())
            .put("fingerprint", Build.FINGERPRINT)
            .put("scope", "Preloaded local sine then Pleos ON_DEVICE through navigation/speech attributes; no microphone capture, volume change, route or vehicle control")
            .put("audibleOutputVerified", false)
            .put("audibleOrder", JSONArray(listOf("440 Hz sine for 2.5 seconds", "800 ms silence", "다음 지점은 서울숲입니다.")))
            .put("routeEquivalence", "Same requested audio attributes; compare recorded tone device with host-observed TTS route")
        val checkpoints = JSONArray()
        val cleanupErrors = JSONArray()
        var scenario: ActivityScenario<MainActivity>? = null
        var audio: LocalAudioAdapter? = null
        var thrown: Throwable? = null
        var completed = false
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val active = checkNotNull(scenario)
            active.onActivity {
                audio = it.model.audio
                it.model.audio.stopSpeech()
            }
            playSine(report, checkpoints)
            SystemClock.sleep(800)
            val appAudio = checkNotNull(audio)
            active.onActivity { appAudio.speak("다음 지점은 서울숲입니다.") }
            awaitSpeech(appAudio, SpeechStatus.SPEAKING)
            checkpoints.put(speechRecord("speechStarted", appAudio))
            awaitSpeech(appAudio, SpeechStatus.COMPLETED)
            checkpoints.put(speechRecord("speechCompleted", appAudio))
            val afterPlayback = checkNotNull(TripStore(context).load())
            val recordsPreserved = originalTrip.tripId == afterPlayback.tripId &&
                originalTrip.phase == afterPlayback.phase && originalTrip.children == afterPlayback.children &&
                originalTrip.ledger == afterPlayback.ledger && originalTrip.routeOrder == afterPlayback.routeOrder &&
                originalTrip.currentStopIndex == afterPlayback.currentStopIndex && originalTrip.endRecord == afterPlayback.endRecord
            report.put("tripRecordsPreservedBeforeRestore", recordsPreserved)
            assertTrue("Audio comparison must not alter attendance, route, phase, or completion", recordsPreserved)
            completed = true
        } catch (failure: Throwable) {
            thrown = failure
            report.put("harnessError", failure.toString())
        } finally {
            instrumentation.runOnMainSync {
                runCatching { audio?.stopSpeech() }
                    .onFailure { cleanupErrors.put("Audio cleanup: $it") }
            }
            runCatching {
                scenario?.close()
                scenario?.let { check(it.state == Lifecycle.State.DESTROYED) }
                instrumentation.waitForIdleSync()
            }.onFailure { cleanupErrors.put("Activity close: $it") }
            // Starting/stopping the Activity invalidates transient safety confirmations and saves
            // diagnostics. Restore the exact original checkpoint after the Activity is destroyed.
            if (scenario == null || scenario?.state == Lifecycle.State.DESTROYED) {
                runCatching {
                    listOf(".bak", ".new").forEach { suffix ->
                        val temporary = File(tripFile.path + suffix)
                        check(!temporary.exists() || temporary.delete())
                    }
                    tripFile.writeBytes(originalBytes)
                    check(tripFile.readBytes().contentEquals(originalBytes))
                    check(TripStore(context).load() == originalTrip)
                }.onFailure { cleanupErrors.put("Checkpoint restore: $it") }
            } else {
                // Preserve recovery bytes rather than race a still-running Activity's writer.
                File(context.filesDir, "speech-quality-original-trip.bin").writeBytes(originalBytes)
                cleanupErrors.put("Activity not destroyed; recovery checkpoint saved as speech-quality-original-trip.bin")
            }
            val bytesRestored = tripFile.exists() && tripFile.readBytes().contentEquals(originalBytes)
            report.put("finishedAtMillis", System.currentTimeMillis())
                .put("checkpoints", checkpoints).put("cleanupErrors", cleanupErrors)
                .put("originalTripBytesRestored", bytesRestored)
                .put("result", if (completed && bytesRestored && cleanupErrors.length() == 0)
                    "PLAYBACK_VERIFIED_HEARING_SEPARATE" else "FAILED")
            File(context.filesDir, "speech-quality-comparison.json").writeText(report.toString(2))
        }
        thrown?.let { throw it }
        assertEquals(report.toString(2), 0, cleanupErrors.length())
        assertTrue(report.toString(2), report.getBoolean("originalTripBytesRestored"))
        assertEquals(report.toString(2), "PLAYBACK_VERIFIED_HEARING_SEPARATE", report.getString("result"))
    }

    private fun playSine(report: JSONObject, checkpoints: JSONArray) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusLost = AtomicBoolean(false)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    focusLost.set(true)
            }.build()
        val pcm = sine()
        var track: AudioTrack? = null
        var focusRequested = false
        try {
            var focusResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED
            instrumentation.runOnMainSync {
                focusRequested = true
                focusResult = manager.requestAudioFocus(focus)
            }
            report.put("toneFocusResult", focusResult)
            assertEquals("Tone requires granted foreground audio focus", AudioManager.AUDIOFOCUS_REQUEST_GRANTED, focusResult)
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track = output
            assertEquals(AudioTrack.STATE_NO_STATIC_DATA, output.state)
            val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            report.put("tone", JSONObject().put("frequencyHz", 440).put("sampleRateHz", SAMPLE_RATE)
                .put("channelCount", 1).put("durationMillis", 2500).put("fadeMillis", 80)
                .put("peakAmplitudeFraction", 0.15).put("framesPrepared", pcm.size).put("framesWritten", written)
                .put("staticBufferBytes", pcm.size * 2).put("systemVolumeChanged", false))
            assertEquals("Entire tone must be preloaded before playback", pcm.size, written)
            output.play()
            val deadline = SystemClock.elapsedRealtime() + 5_500
            var recordedActive = false
            while (output.playbackHeadPosition < pcm.size && SystemClock.elapsedRealtime() < deadline) {
                check(!focusLost.get()) { "Tone lost audio focus during comparison" }
                if (!recordedActive && output.playbackHeadPosition > SAMPLE_RATE / 4) {
                    checkpoints.put(toneRecord("toneActive", output))
                    recordedActive = true
                }
                SystemClock.sleep(25)
            }
            checkpoints.put(toneRecord("toneCompleted", output))
            assertTrue("Static tone did not advance to its end within 5.5 seconds", output.playbackHeadPosition >= pcm.size)
            assertTrue("An active tone checkpoint is required", recordedActive)
        } finally {
            try {
                track?.let { output ->
                    try { output.stop() } finally { output.release() }
                }
            } finally {
                if (focusRequested) instrumentation.runOnMainSync { manager.abandonAudioFocusRequest(focus) }
            }
        }
    }

    private fun sine(): ShortArray {
        val frames = SAMPLE_RATE * 5 / 2
        val fade = SAMPLE_RATE * 80 / 1000
        return ShortArray(frames) { index ->
            val envelope = min(1.0, min(index.toDouble() / fade, (frames - 1 - index).toDouble() / fade))
            (sin(2.0 * PI * 440 * index / SAMPLE_RATE) * Short.MAX_VALUE * 0.15 * envelope).toInt().toShort()
        }
    }

    private fun toneRecord(label: String, track: AudioTrack): JSONObject {
        val routed = track.routedDevice
        return JSONObject().put("checkpoint", label).put("atMillis", System.currentTimeMillis())
            .put("playbackHeadFrames", track.playbackHeadPosition).put("underrunCount", track.underrunCount)
            .put("playState", track.playState).put("sampleRateHz", track.sampleRate)
            .put("deviceId", routed?.id ?: JSONObject.NULL)
            .put("deviceType", routed?.type ?: JSONObject.NULL)
            .put("deviceAddress", routed?.address ?: JSONObject.NULL)
    }

    private fun speechRecord(label: String, audio: LocalAudioAdapter): JSONObject {
        lateinit var result: JSONObject
        instrumentation.runOnMainSync {
            val snapshot = audio.snapshot
            result = JSONObject().put("checkpoint", label).put("atMillis", System.currentTimeMillis())
                .put("status", snapshot.speechStatus.name).put("backend", snapshot.ttsBackend)
                .put("requestId", snapshot.speechRequestId ?: JSONObject.NULL)
                .put("ttsState", snapshot.ttsState)
        }
        return result
    }

    private fun awaitSpeech(audio: LocalAudioAdapter, status: SpeechStatus) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            var current = SpeechStatus.IDLE
            var detail = ""
            instrumentation.runOnMainSync { current = audio.snapshot.speechStatus; detail = audio.snapshot.ttsState }
            check(current != SpeechStatus.FAILED) { detail }
            if (current == status) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Speech did not reach $status within 15 seconds")
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val VERIFIED_FINGERPRINT = "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"
    }
}
