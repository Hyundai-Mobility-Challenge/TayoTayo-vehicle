# Pleos 통학 PoC 공식 API 확인

확인일: 2026-10-30. 공식 문서와 로컬에 설치된 공개 AAR/Android 35 SDK를 대조했다. API가 있다는 사실, AVD에서 호출한 사실, 목표 상태를 관측한 사실은 별개다. 이 문서는 API 조사 근거이며 단독으로 런타임 성공을 뜻하지 않는다.

| 범위 | 확인 내용 | 출처 |
|---|---|---|
| 빌드 | 공모전 API 28+, Gradle 8+, Java/Kotlin. 공식 샘플 AGP 8.8.2, Gradle 8.10.2, Kotlin 2.0.0, JDK 17, compile/target 35, Vehicle SDK 2.3.2 / service 2.3.22. sample min23과 제품 요구 min28은 구별한다. | [공모전](https://appdevchallenge.auto-hmg.io/guide/development), [샘플](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app) |
| Vehicle 의존성 | `ai.pleos.playground:Vehicle:2.3.2`, 공식 Maven 저장소 사용. | [연동](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup) |
| 일반 문 잠금 | `checkDoorLockCapabilities` → Area/AccessType → `getDoorLockState`/`registerDoorLockState` → `setDoorLock` → 새 실제 상태 관측. 요청 완료 콜백은 요청값이며 차량 관측값이 아니다. | [샘플 동작](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app), [Door API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/set-door-lock) |
| 좌석/벨트 | `checkSeatOccupancyCapabilities`, `getCurrentSeatOccupancy`, `registerSeatOccupancy`/unregister. 벨트는 `checkSeatBeltBuckledCapabilities`, `getCurrentSeatBeltBuckledState`, `registerSeatBeltBuckledState`/unregister. 각 실제 지원 Area를 UI 가상 좌석과 분리한다. | 설치 Vehicle 2.3.2 공개 인터페이스, [지원 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability) |
| 속도/기어/주차 | Vehicle SDK Gear 메서드는 deprecated이고 Google GEAR_SELECTION을 지정한다. Cruise target speed는 실제 속도가 아니다. 공개 AAOS `CarPropertyManager`의 PERF_VEHICLE_SPEED/GEAR_SELECTION/PARKING_BRAKE_ON을 조회한다. 속도는 m/s, 후진은 음수. 속도는 `Car.PERMISSION_SPEED` dangerous, 기어/주차는 `Car.PERMISSION_POWERTRAIN` normal 권한. 미수신/권한 오류를 0/P로 바꾸지 않는다. | 설치 AAR의 공개 annotation, [Android properties](https://developer.android.com/reference/android/car/VehiclePropertyIds) |
| 주행 UX | `CarUxRestrictionsManager.registerListener`/`onUxRestrictionsChanged` 및 activeRestrictions 사용. Android35 optional/android.car.jar 설치 확인. | [Pleos 주행 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide) |
| 카메라/접근 물체/SEA/어린이 잠금 | 조사한 Vehicle 2.3.2 공개 인터페이스에서 앱용 영상 프레임, SEA 제어, 어린이 잠금 API를 확인하지 못했다. `vehicle-data` CameraView 상수는 영상 API가 아니다. 모두 “현재 앱 접근 미확인”. 일반 문 잠금과 혼동하지 않는다. 특수 미제공 property 직접 구현 전 기술지원 검토 필요. | [하드웨어 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture), [가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability) |
| 경로/행렬 | `requestRoute`, `getDestinationInfo`, `getRouteStateInfo`, 이벤트로 다음 지점 순차 안내. 경유지 최대 2개. 공개 NaviHelper 3.0.5에서 지점쌍 실제 도로 시간/거리 행렬 API는 발견하지 못했다. 합성 비용 계산과 실제 안내를 분리한다. | [목적지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [경유지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings) |
| 음악/포커스 | 재생 전 AudioAttributes/AudioFocusRequest. LOSS 정지, TRANSIENT 일시정지, CAN_DUCK 감쇄, GAIN 복귀. 내비·음악은 MAY_DUCK 기반 공존. TTS 결과와 음악 결과는 분리. | [오디오 포커스](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development) |
| 한국어 안내 음성 | TextToSpeech 3.3.1, ON_DEVICE 명시, TTS_SERVICE 권한, Locale.KOREA(ko-KR), 완결 문장은 speak(text, true). 초기화·시작·완료와 실제 청취는 구분. | [모드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [언어](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale), [발화](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop) |
| Virtual Console | Door Lock, Seat Occupancy, Gear Selection 설정은 에뮬레이터 입력이다. 참가자 앱 제어 API/권한을 증명하지 않는다. | [공식 Virtual Console](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide) |

## Gleo 스키마/Intent 범위

`assets/schema.json`에 packageName, appDescription, version, actionCalls를 둔다. actionCalls는 최대 3개이고 type=function, function.name은 대문자/밑줄, description은 영어, parameters.type=object, properties와 required를 지정한다. component=activity는 startActivity, service는 문서상 sendBroadcast를 뜻한다. function별 DEFAULT Intent filter가 필요하다.

PoC의 세 function은 `OPEN_DROPOFF_PREPARATION`, `SHOW_NEXT_STOP`, `PLAY_TEST_MUSIC`이며 무매개변수다. 문 해제 action은 없다. 로컬 Intent의 도착은 Gleo 음성 인식/호출 성공이 아니다. 공식 문서는 스키마 검토·승인 후 활성화를 명시한다. 이 PoC에서 제출하거나 승인받지 않는다. AVD에 Gleo 패키지가 있어도 승인/활성화를 뜻하지 않는다.

```json
{
  "packageName": "com.hanhyo.pleosshuttle",
  "appDescription": "An emulator-only shuttle workflow demonstration.",
  "version": "0.1.0",
  "actionCalls": [{
    "type": "function",
    "function": {
      "component": "activity",
      "name": "SHOW_NEXT_STOP",
      "description": "Shows the next approved test stop and the remaining passenger records.",
      "parameters": {"type": "object", "properties": {}},
      "required": []
    }
  }]
}
```

[Schema 요건](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability) · [전달/승인/탐색 시점](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema) · [Intent 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)

문서에는 actionParameters를 개별 extra로 전달한다는 설명과 ACTION_PARAM JSON 예제가 함께 있어 무매개변수 action으로 의존을 줄였다. 반환 broadcast를 사용하는 경우 공식 action은 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`, extras는 packageName와 response(JSON 문자열)이다. 본 앱은 Gleo 승인을 가정하지 않는다.

## 어댑터 구현의 검증 경계

- 문 시험은 SDK가 반환한 **원본 Area 전체**의 일반 잠금에 한 번 요청한다. `ROW_2_RIGHT` 단일 Area가 실제 제공되면 우선 선택하고, 그렇지 않으면 그 문을 포함하는 원본 복합 Area를 조회한다. 복합 Area를 개별 문 Area로 쪼개거나 재생성하지 않는다. 선택 그룹을 UI에 모두 표시하고 `prepareUnlockConfirmation(confirmedAreaKey)`에 현재 정확한 그룹 key를 전달해야 한다. 그룹 내 여러 문의 잠금이 함께 바뀔 수 있으며 개별 어린이 문 제어라고 표현하지 않는다. 허용한 실제 Build fingerprint/product + ranchu + debuggable이 모두 맞아야 하며 모의 입력을 받는 메서드가 없다.
- 최종 확인 토큰은 어댑터가 발급하고 5초 뒤 만료한다. 차량 운행/문/UX 상태 변경, 입력 만료, 백그라운드/연결 해제에 무효화한다. 요청 전 실제 속도와 기어를 다시 조회한다. 요청 후 오류·목표 미관측은 세션 재시도 차단이며 자동 재잠금은 없다.
- AAOS 연속 속도는 원본 timestamp age까지 확인한다. ON_CHANGE 기어/주차는 상태가 오래 유지될 수 있어 성공한 현재 조회 시각과 원본 최종 변경 timestamp를 별도로 보존한다.
- Navi는 이름·좌표·안내 mode를 대조한다. SDK 일치 관측이 순정 내비 화면 확인을 대신하지 않는다. 시작/종료/백그라운드에서 기존 경로를 자동 취소하지 않는다.
- 읽기 listener는 foreground 진입마다 한 번 등록하고 background에서 같은 인스턴스로 해제한다. 늦은 이전 세션 callback은 epoch로 무시한다.
- AAOS 연결은 화면 lifecycle과 별도 세대로 관리한다. 연결 해제/재연결마다 이전 속도·기어·주차·UX 관측과 최종 확인을 무효화하고, 비동기 조회가 시작한 manager 인스턴스와 연결 세대가 현재와 모두 같을 때만 결과를 반영한다. 문 요청 직전 조회에 연결이 없거나 4초 안에 응답하지 않으면 제어 continuation을 실행하지 않고 거절 결과만 반환한다.

## 현재 시험 대상 식별 (2026-10-30)

사용자가 시작한 `emulator-5556` / `Pleos_Connect_v2.1.0`에서 읽은 값이다. 아래 fingerprint와 product, hardware=ranchu, debuggable 앱을 동시에 요구한다.

- fingerprint: `Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys`
- product: `crp_avd_arm64`
- hardware: `ranchu`
- Android: 14; 외부 adb 관측 `ro.kernel.qemu=1`
- 설치 패키지: `ai.pleos.playground.service.vehicle`, `ai.pleos.playground.service.navi`, `ai.pleos.gleoai.model.provider`, `ai.pleos.playground.service.adas`

Gleo model provider와 ADAS 서비스의 존재는 공개 제어 API 접근·Gleo schema 승인·음성 호출 성공의 근거가 아니다. 앱은 숨겨진 system property API를 사용하지 않고 공개 Build 필드와 debuggable만 검사한다. 실제 차량에 이 앱을 설치하거나 제어하지 않는다.

주차 브레이크 지원 여부는 조회 성공과 분리한다. AAOS property config 또는 실제 값으로 한 번 지원을 확인하면 로컬 지원 이력을 유지하고, 이후 조회 실패/미수신/오래된 값에서는 신선한 true가 다시 관측되기 전까지 문 시험을 차단한다. UX 제한 true도 정차 속도/P 기어와 별개로 차단한다.

## SDK 계측 시험의 기본값과 선택 플래그

`SdkRuntimeDeviceTest`는 확인된 Pleos fingerprint/product/hardware에서만 동작한다. 기본값은 **읽기 전용**으로, 시작/중복 시작/정지/중복 정지를 두 번 수행하고 앱 쪽 구독 호출 키와 해제 오류, 종료 이후 늦은 콜백을 기록한다. SDK 기능의 성공을 JUnit 통과로 대체하지 않는다. 결과는 앱 내부 `sdk-runtime-observation.json`의 기능별 상태를 읽는다.

- `allowNavigationTrial=true`: 초기 목적지 없음 + SAFE_GUIDING + 경로 탐색 중 아님 + 약관 동의 상태가 현재 조회로 확인될 때 서울시청 시험 지점(37.5665, 126.9780)을 딱 한 번 요청한다. 이름/좌표/안내 모드가 모두 일치하면 SDK 목표 상태 관측으로 기록한다. 순정 화면은 PNG를 저장해 별도 눈으로 확인한다. finally에서 자신의 시험 경로만 한 번 취소하고 목적지 없음/비활성 상태를 다시 확인한다. 최근 시험 목적지 기록은 남을 수 있다.
- `allowUnlockTrial=true`: SDK가 반환한 선택된 원본 문 그룹 전체를 evidence에 표시하고, 실제 어댑터가 해당 그룹의 유효 토큰을 발급할 수 있을 때만 명시적 시험 호출을 한 번 한다. 권한·실제 최신 차량 상태·AVD 조건을 우회하지 않는다. 수락 콜백과 실제 false 관측은 별개로 저장한다. 자동 재시도·재잠금·합성 차량 입력·특권 권한 채택은 없다.

어떤 플래그도 지정하지 않으면 두 변경 시험은 `NOT_AUTHORIZED_NOT_RUN`이다. 확인된 AVD가 아니면 SDK 호출 전에 상태를 저장하고 skip한다. 모든 대기는 제한 시간이 있으며, 타임아웃이나 부분 응답은 성공으로 바꾸지 않는다. 최초 작성 시 이 계측 시험은 아직 실행하지 않았다.

## 차량 속도 읽기 권한을 UI에서 허용하는 절차

현재 앱은 `android.car.permission.CAR_SPEED`를 manifest에 선언하고, `MainActivity`의 `ActivityResultContracts.RequestPermission`으로 Android 권한 대화상자를 연다. 계측 시험 자체는 권한을 부여하거나 권한 대화상자를 조작하지 않는다.

1. 확인된 `emulator-5556`에서 앱을 열고 운행 준비의 **Pleos SDK 연결**을 선택한다.
2. **차량 관측** 탭의 **차량 속도 조회 권한 요청**을 누른 뒤 Android 시스템 대화상자에서 허용한다. 반환 시 앱이 관측을 새로 요청한다.
3. **관측 새로고침** 후 실제 속도의 값·출처·시각·오류를 확인한다. 권한 허용 자체는 속도 수신 성공이 아니다. SDK 계측 결과에는 각 권한의 현재 GRANTED/DENIED와 속도 관측 상태를 따로 저장한다.
4. 권한 거부, 대화상자 미제공, UX 제한으로 상세 조작 불가, 실제 속도 미수신/오래됨이 남으면 그 상태로 기록한다. 설정이나 차량 입력을 바꾸어 문 시험 조건을 만들지 않는다.

`CAR_POWERTRAIN`은 normal 권한이며 별도의 runtime 요청이 없다. Pleos 권한의 선언/OS 허용과 해당 서비스 API 사용 가능 여부도 별개다. `pm grant`, `adoptShellPermissionIdentity`, 특권 권한 우회 없이 앱 UI가 제공하는 속도 읽기 요청만 사용한다.

NaviHelper의 `requestRoute`와 `cancelRoute`는 동기 반환값이 없는 호출이므로, 예외 없이 반환해도 `UNVERIFIED`로 기록한다. 실제 목적지 이름·좌표와 안내 모드의 새 응답이 일치한 경우에만 `TARGET_OBSERVED`로 바뀐다. 취소 전송 역시 취소 이벤트 및 목적지 없음/안내 비활성 상태를 별도로 확인한다.
