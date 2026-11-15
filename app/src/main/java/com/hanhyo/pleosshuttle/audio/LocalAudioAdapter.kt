package com.hanhyo.pleosshuttle.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

enum class SpeechStatus { IDLE, INITIALIZING, SPEAKING, COMPLETED, STOPPED, FAILED }

data class AudioSnapshot(
    val ttsState: String = "안내 음성 대기 · Pleos 기기 내 한국어",
    val speechStatus: SpeechStatus = SpeechStatus.IDLE,
    val speechRequestId: Long? = null,
    val ttsBackend: String = "Pleos ON_DEVICE · ko-KR",
    val observedAtMillis: Long = System.currentTimeMillis(),
    val events: List<String> = emptyList(),
)

/**
 * 운행 음성 안내(Pleos ON_DEVICE TTS)만 다룬다. 발화 직전에 음성 안내용 오디오 포커스(일시 · 감쇄 허용)를 요청하고,
 * 끝나거나 다른 소리가 포커스를 가져가면 반환한다. 음악 재생 기능은 없다.
 */
class LocalAudioAdapter(
    context: Context,
    private val onChange: (AudioSnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(AudioManager::class.java)
    private var foreground = true
    private var closed = false
    private val speechGate = SpeechRequestGate()
    private var voice: VoiceHandle? = null
    private var speechTimeout: Runnable? = null

    private class VoiceHandle(val id: Long, val engine: PleosTextToSpeech) {
        var initialized = false
        var listener: EventListener? = null
        var focus: AudioFocusRequest? = null
    }

    @Volatile var snapshot = AudioSnapshot()
        private set

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun onForeground() = onMain {
        foreground = true
        event("포그라운드 복귀")
    }

    fun refresh() = onMain {
        snapshot = snapshot.copy(observedAtMillis = System.currentTimeMillis())
        onChange(snapshot)
    }

    fun onBackground() = onMain {
        cancelVoice("앱을 벗어나 안내 음성을 중단했습니다")
        foreground = false
        event("백그라운드 전환")
    }

    /** Pleos public ON_DEVICE synthesis only: no network voice or Android-engine success inferred. */
    fun speak(text: String) = onMain {
        if (!foreground || speechGate.active != null) {
            event("안내 음성 요청 생략 · 백그라운드 또는 이전 요청 진행 중")
            return@onMain
        }
        if (text.isBlank()) {
            snapshot = snapshot.copy(ttsState = "안내할 문장이 없습니다", speechStatus = SpeechStatus.FAILED)
            event("빈 안내 문장 요청 생략")
            return@onMain
        }
        val id = checkNotNull(speechGate.begin(SystemClock.elapsedRealtime()))
        snapshot = snapshot.copy(ttsState = "기기 내 한국어 음성 준비 중", speechStatus = SpeechStatus.INITIALIZING, speechRequestId = id)
        event("Pleos ON_DEVICE 초기화 요청 · ko-KR · 요청 $id")
        scheduleVoiceTimeout(id)
        try {
            val handle = VoiceHandle(id, PleosTextToSpeech(appContext, Mode.ON_DEVICE))
            voice = handle
            handle.engine.initialize(object : OnInitializeListener {
                override fun onSuccess() = voiceCallback(id) {
                    handle.initialized = true
                    prepareVoice(handle, text)
                }
                override fun onFailure(errorType: ErrorType) = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.FAILED, "음성 준비 실패 · $errorType · 다시 눌러 재시도")
                }
            })
        } catch (error: Exception) {
            finishVoice(id, SpeechStatus.FAILED, "음성 준비 실패 · ${error.javaClass.simpleName} · 다시 눌러 재시도")
        }
    }

    fun stopSpeech() = onMain {
        cancelVoice("안내 음성을 중단했습니다")
    }

    private fun prepareVoice(handle: VoiceHandle, text: String) {
        val id = handle.id
        if (!speechGate.accepts(id) || voice !== handle || speechGate.active?.phase != SpeechRequestGate.Phase.INITIALIZING) return
        try {
            val listener = object : EventListener {
                override fun onReady() = voiceCallback(id) { event("Pleos ON_DEVICE onReady · 요청 $id") }
                override fun onStart() = voiceCallback(id) {
                    snapshot = snapshot.copy(ttsState = "안내 중 · Pleos 기기 내 한국어", speechStatus = SpeechStatus.SPEAKING)
                    event("Pleos ON_DEVICE onStart · 실제 청취는 별도 · 요청 $id")
                }
                override fun onDone() = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.COMPLETED, "안내 완료 · Pleos 기기 내 한국어")
                }
                override fun onStop() = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.STOPPED, "음성 엔진이 안내를 중단했습니다")
                }
                override fun onError(errorType: ErrorType) = voiceCallback(id) {
                    finishVoice(id, SpeechStatus.FAILED, "안내 음성 실패 · $errorType · 다시 눌러 재시도")
                }
                override fun onUpdatedRms(rms: Double) = Unit
            }
            handle.engine.addEventListener(listener)
            handle.listener = listener
            handle.engine.setLocale(Locale.KOREA)
            handle.engine.setAudioAttributes(speechAttributes)
            startVoice(handle, text)
        } catch (error: Exception) {
            finishVoice(id, SpeechStatus.FAILED, "안내 음성 설정 실패 · ${error.javaClass.simpleName} · 다시 눌러 재시도")
        }
    }

    private fun startVoice(handle: VoiceHandle, text: String) {
        val id = handle.id
        if (!foreground || voice !== handle || !speechGate.start(id, SystemClock.elapsedRealtime())) return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) voiceCallback(id) {
                    finishVoice(id, SpeechStatus.STOPPED, "다른 소리의 요청으로 안내를 중단했습니다")
                }
            }, main).build()
        handle.focus = focus
        val result = runCatching { manager.requestAudioFocus(focus) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            finishVoice(id, SpeechStatus.FAILED, "안내 음성 재생 권한을 얻지 못했습니다 · 다시 눌러 재시도")
            return
        }
        if (!speechGate.accepts(id)) return
        scheduleVoiceTimeout(id)
        snapshot = snapshot.copy(ttsState = "안내 음성 요청 · 시작 대기")
        event("Pleos ON_DEVICE speak · ko-KR · isFinal=true · 요청 $id · 발화 내용은 로그에 저장하지 않음")
        try {
            handle.engine.speak(text, true)
        } catch (error: Exception) {
            finishVoice(id, SpeechStatus.FAILED, "안내 음성 요청 실패 · ${error.javaClass.simpleName} · 다시 눌러 재시도")
        }
    }

    private fun voiceCallback(id: Long, action: () -> Unit) {
        // Always queue SDK callbacks, including synchronous ones, so handle assignment and cleanup
        // finish before a callback can act. Each fresh SDK instance captures its request identity.
        main.post { if (!closed && foreground && speechGate.accepts(id)) action() }
    }

    private fun scheduleVoiceTimeout(id: Long) {
        speechTimeout?.let(main::removeCallbacks)
        val request = speechGate.active?.takeIf { it.id == id } ?: return
        val timeout = Runnable {
            if (!closed && speechGate.timedOut(id, SystemClock.elapsedRealtime())) {
                val step = if (speechGate.active?.phase == SpeechRequestGate.Phase.INITIALIZING) "음성 준비" else "안내 음성"
                finishVoice(id, SpeechStatus.FAILED, "$step 응답 시간 초과 · 다시 눌러 재시도")
            }
        }
        speechTimeout = timeout
        main.postDelayed(timeout, (request.deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun cancelVoice(message: String) {
        speechGate.active?.id?.let { finishVoice(it, SpeechStatus.STOPPED, message) }
    }

    private fun finishVoice(id: Long, status: SpeechStatus, message: String) {
        if (!speechGate.finish(id)) return
        // Invalidate first: stop/release/focus callbacks must not complete another request.
        val handle = voice?.takeIf { it.id == id }
        voice = null
        speechTimeout?.let(main::removeCallbacks)
        speechTimeout = null
        snapshot = snapshot.copy(ttsState = message, speechStatus = status)
        handle?.let {
            it.focus?.let { focus -> runCatching { manager.abandonAudioFocusRequest(focus) } }
            if (it.initialized) runCatching { it.engine.stop() }.onFailure { error -> event("음성 정리 stop 오류 ${error.javaClass.simpleName}") }
            it.listener?.let { listener -> runCatching { it.engine.removeEventListener(listener) }.onFailure { error -> event("음성 리스너 정리 오류 ${error.javaClass.simpleName}") } }
            runCatching { it.engine.release() }.onFailure { error -> event("음성 엔진 정리 오류 ${error.javaClass.simpleName}") }
        }
        event("$message · 요청 $id")
    }

    fun close() = onMain {
        cancelVoice("앱 종료로 안내 음성을 중단했습니다")
        closed = true
    }

    private fun event(message: String) {
        val now = System.currentTimeMillis()
        snapshot = snapshot.copy(observedAtMillis = now, events = (snapshot.events + "$now $message").takeLast(100))
        onChange(snapshot)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) action()
        } else main.post { if (!closed) action() }
    }
}
