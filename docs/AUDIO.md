# 로컬 음악과 안내 음성

이 앱의 음악은 `scripts/generate_test_tune.py`가 직접 합성한 **4.70초 시험 멜로디**다. 동요 녹음이나 실제 어린이 음성이 아니다. 다운로드한 음악을 사용하지 않는다. 스크립트의 짧은 시험 악보와 WAV는 이 PoC에서 사용·수정·배포할 수 있다. MediaPlayer는 파일을 반복 재생하며 재생·일시정지·정지를 구분한다.

음악은 Android `USAGE_MEDIA`로 포커스를 요청한다. 포커스 요청이 수락돼야 재생한다. 영구 손실은 정지, 일시 손실은 일시정지, `CAN_DUCK` 콜백은 앱 볼륨을 20%로 낮춘다. `GAIN`은 볼륨을 복구하며 **포커스 손실 직전에 재생 중이었고 사용자가 일시정지하지 않았을 때만** 재생을 재개한다. 백그라운드 전환은 일시정지하고 자동 재개 의도를 취소한다. [Android 오디오 포커스](https://developer.android.com/media/optimize/audio-focus)

Android/AAOS 오디오 정책은 믹서에서 자동으로 음량을 낮출 수 있으며, 이 경우 앱에 `CAN_DUCK` 콜백이 오지 않을 수 있다. 앱 볼륨 표시만으로 실제 출력 음량을 검증했다고 하지 않는다. API 35를 대상으로 하는 앱은 포커스 요청 당시 최상위 앱 또는 적절한 foreground service여야 하는 제약이 있으므로, 이 로컬 PoC는 전경 재생만 지원한다.

`beginFocusProbe(mayDuck)`와 `endFocusProbe()`는 진단용 실제 Android 포커스 요청·반환이다. `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`를 쓰지만 **순정 내비 음성이나 내비 엔진 호출은 아니다.** 요청 결과, 음악 포커스 콜백, 재생/일시정지, 앱 볼륨 변화를 따로 기록한다. AAOS에서 동시 포커스를 허용하면 요청 수락 후에도 음악 콜백이 없을 수 있다. 순정 내비 안내와 실제 소리의 겹침·감쇄는 별도로 확인해야 한다.

TTS는 **Pleos TextToSpeech 3.3.1의 ON_DEVICE 모드**로 연결한다. 한국어 `Locale.KOREA`(`ko-KR`)와 `speak(text, true)`로 완결 문장을 요청한다. 일반 Android 기본 엔진과 다른 공개 SDK 경로이며 서버 등록·키·서버 음성은 사용하지 않는다. SDK가 가져오는 INTERNET 권한도 병합에서 제거한다. [Pleos TTS 모드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [언어](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale), [문장 완료](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop)

음악은 음성 초기화 중 계속 재생하다가 발화 직전에 일시정지한다. 완료·사용자 음성 중지 뒤에는 기존 음악 재생 의도와 현재 사용자 조작을 대조해 복귀한다. 음악 재생 버튼을 누르면 준비 중인 음성도 취소하고, 앱을 벗어나면 안내와 자동 음악 복귀를 취소한다. 요청마다 새 엔진을 사용하고 요청 번호로 늦은 콜백을 무시한다. 초기화 10초·발화 20초 제한 후에는 실패로 표시하고 다시 누를 수 있다. `onStart`·`onDone` 콜백과 실제 청취 확인은 각각 기록한다.

검증 순서:

1. 전경에서 음악 재생 → 일시정지 → 재생 → 정지를 선택해 상태와 실제 소리를 확인한다.
2. 음악을 재생하고 일시 포커스 시험을 요청한다. 수락 결과와 음악의 실제 콜백·정지 상태를 기록한다. 포커스를 반환했을 때 재생 복귀를 확인한다.
3. 같은 시험 중 음악을 수동 일시정지한 뒤 포커스를 반환한다. 자동 재생되지 않아야 한다.
4. duck 시험 요청·반환 때 앱 콜백과 시스템 출력 음량을 각각 확인한다.
5. 음악 재생 중 홈 화면으로 나갔다가 복귀한다. 소리가 멈추고 자동으로 다시 켜지지 않아야 한다.
6. 음성 안내 버튼은 별도 시험한다. 초기화 실패/요청/시작/완료 콜백/실제 청취를 구분하고 음성 중지 뒤 다시 요청한다.
7. 순정 내비로 다음 공개 시험 지점을 안내하는 동안 실제 안내 음성과 음악의 겹침을 관찰한다. 내비 안내 음성이 나오지 않으면 그 통합 항목은 미검증으로 남긴다.

순수 상태 정책 테스트는 포커스 변경에 대한 코드 분기만 검증한다. 실제 오디오 장치 출력, 순정 내비 음성, Gleo 음성 호출은 테스트 통과로 추정하지 않는다.

## 0.1.1-poc에서 확인한 작은 소리 문제

2026-10-30 Pleos AVD의 시험 멜로디가 실제 미디어 버스까지 전달됐고 음소거도 없었지만, 차량 미디어 음량이 20/45(−25 dB)여서 작게 출력됐다. 사용자는 처음에 “아주 작게 들려요”라고 확인했다. 일반 에뮬레이터 음량 올리기를 8회 사용해 **28/45(−17 dB)**로 조정했고 사용자가 “지금 크기가 좋아요”라고 확인했다. Mac 음량 44%, 음원 파일, 내비·음성 그룹 음량은 바꾸지 않았다.

AAOS에서는 Android STREAM_MUSIC이 15/15로 보여도 차량의 별도 미디어 그룹 음량은 낮을 수 있다. 이 환경의 값은 실차 기본값이나 권장 안전 음량이 아니다. [차량 음량 그룹 문서](https://source.android.com/docs/automotive/audio/volume-management)

앱은 재생 위치와 길이를 0.5초마다 갱신하고 진단 탭에서 실제 출력 경로를 보여준다. 재생 시간 진행과 출력 경로만으로 실제 청취를 판정하지 않는다. 0.1.1의 음성 시험 버튼은 **Android TTS 초기화 -1**을 반환했다. 이 기록은 0.1.1 당시의 결과이며, 0.1.2에서 TTS 연결 방식을 수정했다. 순정 내비 음성은 별도 항목이다.

[음량·청취·TTS 결과](../evidence/flow-audio-0.1.1/audio-diagnosis.json), [기기 시험 10개](../evidence/flow-audio-0.1.1/device-tests.log)

## 일반 Android TTS 초기화 실패의 원인

같은 AVD에서 `android.intent.action.TTS_SERVICE`를 제한 없이 조회하면 CAAS SpeechService가 보이지만, Android TTS가 사용하는 `MATCH_DEFAULT_ONLY`로 조회하면 0개다. 실제 Android 기본 TTS 초기화도 -1, defaultEngine은 null이었다. Manifest의 앱 가시성 선언이 빠져 있던 것은 아니다.

공식 Pleos SDK로 별도 연결하면 초기화 성공 후 `onReady → onStart → onDone`과 RMS 값을 관측했다. 이전 Probe의 `Locale.KOREAN`(`ko`)·비최종 조각 `false` 호출과 달리 지원 언어 `ko-KR`·완결 문장 `true`로 재시험했다. 두 차이 각각이 과거 ON_DEVICE_ENGINE_ERROR의 단독 원인이었는지는 분리 시험하지 않았으므로 단정하지 않는다.

0.1.2의 단위 75개·기기 14개를 통과했고 하단 버튼에서 `안내 완료`를 확인했다. 실제 청취 결과는 [청취 기록](../evidence/tts-0.1.2/audibility-and-visual-review.json)에 별도로 남긴다. [최신 검증](VERIFICATION.md)

## 안내 음량 조정과 실제 청취

0.1.2 후속 청취에서 사용자는 음성은 아주 작게 들리지만 내용을 알아들을 수 없다고 확인했다. 실제 활성 AudioTrack은 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE / CONTENT_TYPE_SPEECH`로 `bus1_navigation_out`에 연결됐고 해당 음량은 20/45(−25 dB)였다. 음악은 다른 `bus0_media_out`의 28/45(−17 dB)였다.

일반 설정의 사운드 → 내비게이션 조작으로 안내 그룹을 45/45(0 dB)까지 높인 뒤 사용자가 문장이 잘 들린다고 확인했다. 지지직거림은 여전히 있다고 했으며, 약간 줄여 달라는 요청에 따라 최종 **40/45(−5 dB)**로 낮춰 다시 재생했다. 이후 같은 40/45의 완전 재시작 비교에서 사용자가 목소리는 괜찮다고 확인했다(아래 후속 결과). 음악 28/45와 나머지 그룹은 전후 조회에서 동일했다. 이 값은 현재 AVD의 조정 결과이며 앱이 음량을 강제로 설정하지 않는다. 같은 내비게이션 그룹을 쓰는 다른 안내에도 적용된다.

음성 엔진 초기화 문제와 낮은 안내 음량은 각각 해결했으나, 지지직거림의 원인을 에뮬레이터로 확정하거나 실차에서는 없다고 판단하지 않는다. 이번 조정은 앱 코드·APK 변경 없이 수행했으므로 기존 75개 단위/14개 기기 시험은 0.1.2 코드의 이전 실행 결과로 유지한다. [사용자 청취·전후 음량·활성 경로](../evidence/tts-0.1.2/audibility-and-visual-review.json)

## 지지직거림을 좁히는 비교 시험

2026-10-30 앱 코드·음량을 유지한 채 비교용 계측 `SpeechQualityComparisonDeviceTest`를 추가했다(`debf1be`). 440 Hz의 일정한 시험음을 2.5초 재생하고 800ms 뒤 실제 앱의 한국어 TTS를 재생한다. 시험음은 피크 15%와 시작·끝 80ms 페이드를 적용해 만들며 외부 음원을 사용하지 않는다. 두 소스는 22,050 Hz 모노, NAVIGATION_GUIDANCE/SPEECH이며 실제 `bus1_navigation_out` 출력을 확인했다. 이 시험은 소리의 차이를 사람이 비교하기 위한 것으로, 통과가 깨끗한 음질을 보장하지 않는다.

- 비교 계측 **1개 통과**, 원래 운행 기록·저장 파일 바이트 복원 및 호스트 해시 대조 통과. 음악 복귀·취소·재시도·배경 전환 **3개도 재실행해 통과**했다. 앱 APK는 기존 0.1.2와 동일하며 전체 75개 단위/14개 기기 시험을 새로 다시 실행한 결과는 아니다.
- 실제 TTS 발화 중 0.2/0.8/1.4초 및 종료 관측에서 CAAS AudioTrack의 Underruns는 0, normal mixer partial/empty도 0이었다. 스냅샷 사이의 모든 순간을 보장하지는 않는다. 정적 버퍼 시험음의 underrun 0은 스트리밍 시험과 구분한다.
- 같은 약 2.5초 구간의 FastMixer 최상단 underruns는 1844→1924, overruns는 15→16이었다. AOSP Android 14 소스에서 이 값은 **오디오 처리 루프가 기준 주기보다 늦거나 짧았던 횟수**다. PCM 공급 부족 횟수나 실제 들린 잡음 횟수로 해석하지 않는다. 출력 경로의 시간 불규칙성은 관측됐지만 소리와의 인과관계는 미확정이다. [FastThread 기준](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/services/audioflinger/FastThread.cpp#219), [FastMixer 기준](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/services/audioflinger/FastMixer.cpp#287)
- 호스트는 Emulator 37.2.12, MacBook Pro 내장 스피커 2채널/48,000 Hz, CAAS 4.2.10-coral-aosp다. 공식 릴리스 노트의 macOS 다채널 출력 잡음 수정은 이 버전에 포함되고 현재 선택된 장치는 2채널이므로 그 알려진 문제를 원인으로 단정할 수 없다. 22,050→48,000 Hz 변환 자체도 Android의 정상 경로다. [에뮬레이터 릴리스 노트](https://developer.android.com/studio/releases/emulator), [샘플레이트 변환](https://source.android.com/docs/core/audio/src)
- 공개 TTS SDK에서 앱이 PCM·재생 버퍼·출력 샘플레이트를 직접 바꾸는 경로는 확인되지 않았다. 임의로 미디어 용도로 바꾸거나 시스템 설정·SDK 버전을 변경하지 않았다. 재생을 놓쳐 한 차례 다시 비교한 뒤 사용자는 **“반반이야 둘다”**라고 답했다. 시험음과 목소리 모두 깨끗한 소리와 잡음이 섞여 들린다는 보고로 기록한다. 공통 출력 경로를 우선 조사할 근거는 생겼지만 TTS 엔진·리샘플링·에뮬레이터·호스트 중 정확한 원인이나 상대적인 잡음 정도는 확정하지 않는다. 실차 품질도 미검증이다.

[비교 결과](../evidence/tts-quality-0.1.2/speech-quality-comparison.json), [발화 전·중·후 관측](../evidence/tts-quality-0.1.2/summary.json), [비교 중 출력](../evidence/tts-quality-0.1.2/comparison-flinger-samples.json), [판단 범위](../evidence/tts-quality-0.1.2/assessment.json)

### 팀원 재현

현재 앱을 설치한 Pleos_Connect_v2.1.0의 **기존 모의 운행**에서 실행한다. 다른 기기·실제 SDK 운행·미완료 저장 복구 파일이 있으면 재생 전에 건너뛴다. 테스트 실행 전 음량을 확인하고, 사람은 시험음과 목소리의 잡음을 각각 기록한다. 아래 명령은 이 환경의 기기 번호·Android 사용자 10을 기준으로 한다.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home bash ./gradlew assembleDebugAndroidTest
POC_ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$POC_ADB" -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$POC_ADB" -s emulator-5554 shell am instrument --user 10 -w \
  -e class com.hanhyo.pleosshuttle.audio.SpeechQualityComparisonDeviceTest \
  com.hanhyo.pleosshuttle.test/androidx.test.runner.AndroidJUnitRunner
```

원래 운행 파일은 Activity 종료 후 복원한다. 정리가 실패하면 JSON의 cleanupErrors 및 `speech-quality-original-trip.bin` 복구본 유무를 확인한 뒤 재실행한다. 진단 파일은 앱 내부 `files/speech-quality-comparison.json`이며 실제 청취 결과는 별도로 남긴다.

## 완전 재시작 후 비교

사용자 승인으로 Pleos AVD만 정상 종료한 뒤 `-no-snapshot-load`로 새 프로세스를 시작했다. 앱 데이터를 지우지 않았으며 다른 휴대전화 에뮬레이터는 유지했다. Android 사용자 10·fingerprint·운행 파일 SHA-256·음량 그룹을 대조해 기존 운행과 안내 40/45·음악 28/45가 유지됨을 확인했다.

동일 비교 시험 1개가 10.590초에 통과했고, 사용자는 **“삐는 엄청 지지직 거렸고 목소리는 괜찮았어”**라고 답했다. 현재 40/45의 한국어 안내 청취는 양호한 것으로 확인됐다. 시험음의 심한 잡음은 남아 있으므로 전체 오디오 문제가 해결됐거나 TTS 엔진/에뮬레이터 중 하나가 원인이라고 확정하지 않는다. 앱 코드는 유지했다.

이번 cold-start 시험음+음성 전체 관측 27개에서 FastMixer 시간 기준 카운터는 late 185/short 58, normal mixer partial 5/empty 0이었다. 이전의 발화만 약 2.5초 관측한 값과 구간이 달라 단순 증감으로 개선 여부를 판단하지 않는다. 현재 앱은 실행 가능한 기존 하차 화면으로 돌아왔다.

[재시작·청취 판단](../evidence/tts-quality-0.1.2/cold-restart/assessment.json), [전후 상태](../evidence/tts-quality-0.1.2/cold-restart/after.json), [시험 결과](../evidence/tts-quality-0.1.2/cold-restart/device.log), [오디오 관측](../evidence/tts-quality-0.1.2/cold-restart/audio-samples.json)
