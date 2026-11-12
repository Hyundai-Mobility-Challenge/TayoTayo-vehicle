# 구현 계획 (제안)

2026-11-01 Claude(Cowork) 작성. 팀 확정 전 제안이며, 아래 기존 코드 설명은 `feat/shuttle-poc` @ `aace9b1`을 읽고 적었다.

## 어디서 하나

- Mac의 `<project-root>`에서 코딩 에이전트(Claude Code, Codex 등)로 진행한다. Gradle 빌드, Pleos 에뮬레이터 설치(`scripts/install-on-pleos.sh`), SDK 관측이 그 환경에서만 된다
- 이 폴더(`docs/design/`)는 커밋되지 않은 새 파일로 놓여 있다. 첫 단계에서 브랜치를 만들고 함께 커밋할지 정한다
- repo의 `AGENTS.override.md`(2026-11-06 22:00까지 QMD·Headroom 사용 지침)와 상위 지침을 먼저 읽는다
- 각 단계는 기존 원칙을 유지한다: 센서·기록으로 안전·하차 완료를 자동 확정하지 않음, 도착은 선생님 확인, 문 해제 자동 실행 없음, 실제 아동 정보·서버·외부 제출 없음

## 기존 코드와의 대응

| 파일 | 지금 하는 일 | 통학메이트에서 할 일 |
|---|---|---|
| `ShuttleUi.kt` (659줄) | 모든 화면과 공통 부품, `MaterialTheme(lightColorScheme(Teal…))` | 화면별 파일로 분리, 공통 부품(헤더·타임라인·동요 바·주요 버튼), 새 테마 적용, 가로/세로 분할 레이아웃 |
| `ShuttleViewModel.kt` | 엔진·SDK·오디오 연결 | 오늘의 변경, 순서 초안, 재생목록·자동 동작 설정, 운행 방향 상태 추가 |
| `domain/ShuttleEngine.kt` | 단계 전이(`board`, `startDriving`, `confirmHandover`, `alight`, `completeCurrentStop`, `beginEndReview`, `finishTrip`, `previewRoute`, `applyRoute` 등) | 등원 방향, 동승 유지 아이의 종료 점검 정리, 하원 미탑승(보호자 직접 픽업) 처리 |
| `domain/RoutePlanner.kt` | 합성 비용 행렬, 최대 6개 지점 완전탐색, `FIXED` 평가 | 그대로 두고 `RouteTimeline`(초안)으로 도착 시각·경고 계산 추가 |
| `domain/ShuttleModels.kt` | `TripState`, `ChildRecord`, `Stop`, 관측 모델, 가상 `SampleTrip` | 운행 방향, 오늘의 변경, 인계 가능 보호자, 정류장 메모·규칙, 재생목록 모델(모두 가상 데이터) |
| `AutomaticTripGuidance.kt` | 단계별 자동 안내 문장(`tripCue`) | 하차 준비 문장(`곧 ○○이에요. △△ 친구 내릴 준비해요.`), 등원 문장 |
| `FlowGuide.kt` | 다음에 누를 버튼 안내(표시 전용) | 새 화면 흐름에 맞게 갱신 |
| `audio/LocalAudioAdapter.kt`, `AudioFocusPolicy.kt` | 시험 멜로디 재생, 안내 중 일시정지·복귀 | 재생목록, 접근 시 음량 비율(끄기/30%/50%), 출발 시 이어서 재생 설정 |
| `sdk/RealVehicleAdapter.kt` | 속도·기어·주차 브레이크·문·좌석/벨트 Area 관측 | 주행 중 잠금 신호, 종료 점검 확장 슬롯(좌석·벨트, 보조 정보) |
| `sdk/RealNaviAdapter.kt` | 다음 지점 하나씩 NaviHelper 안내 | 순서를 바꾼 뒤 다음 정류장부터 새 순서 전달 |
| `TripStore.kt` | 운행 저장, `completed-trips/<tripId>.bin` 보관 | 운행 기록 화면이 보관본을 읽음 |
| `MainActivity.kt`, `AndroidManifest.xml` | 단일 Activity, Gleo Intent 3개 | 분할 창 크기 변화 대응 확인 |

## 단계

각 단계는 빌드(`assembleDebug testDebugUnitTest lintDebug`)와 Pleos 에뮬레이터 화면 확인으로 끝낸다. 단계마다 `docs/VERIFICATION.md`에 실행 환경과 결과를 따로 남긴다.

### 1. 테마와 화면 골격
- `code-drafts/designsystem/theme`를 옮기고 IBM Plex Sans KR 글꼴과 OFL 라이선스를 넣는다
- `ShuttleUi.kt`를 화면별 파일로 나누고, 창 폭에 따라 가로(타임라인 360 + 본문)와 세로 분할(한 열)로 분기
- 기존 하원 흐름·안전 조건은 그대로. 화면 모양만 바뀐다
- 완료 기준: 기존 단위·기기 시험 통과, 가로·분할 두 상태 캡처

```
docs/design/ 을 기준으로 1단계만 진행해줘. code-drafts/designsystem/theme 를 app 모듈로 옮기고
IBM Plex Sans KR 글꼴(OFL)을 res/font 에 넣어 AppTheme 를 적용해. ShuttleUi.kt 를 화면별 파일로 나누고
창 폭에 따라 가로(타임라인+본문)와 세로 분할(한 열) 레이아웃으로 분기해줘. 기존 하원 흐름과 안전 조건은
바꾸지 마. 끝나면 assembleDebug testDebugUnitTest lintDebug 를 돌리고 Pleos 에뮬레이터에 설치해
가로·분할 화면을 캡처해 docs/VERIFICATION.md 에 기록해줘.
```

### 2. 경로 확인과 순서 바꾸기
- `code-drafts/domain/RouteTimeline.kt`와 시험을 옮긴다
- L2(달라진 점·보호자 알림 스위치·기준 보기·정류장 메모)와 L9(위·아래 이동, 도착 시각 재계산, 경고, 이유)를 구현
- 정한 순서는 `previewRoute`/`applyRoute`(`RouteObjective.FIXED`)로 반영. 운행 중에는 정차 상태에서만 열림
- 보호자 알림은 화면 스위치만(실제 발송 없음)

```
2단계: docs/design/code-drafts/domain/RouteTimeline.kt 와 시험을 app 모듈로 옮기고, SCREENS.md 의 L2·L9 를
구현해줘. 순서를 정하면 ShuttleEngine.previewRoute/applyRoute 로 반영하고 내비에는 다음 정류장부터 새 순서를
넘겨. 순서 바꾸기는 운행 전 또는 정차 중에만 열려야 해. 보호자 알림은 스위치 상태만 저장하고 실제 발송은 하지 마.
```

### 3. 상황별 동요와 하차 준비
- L10 동요 고르기, 동요 바, 자동 동작 설정. 음원은 지금의 시험 멜로디를 쓰고 곡명은 자리표시로 둔다
- L4 하차 준비: 안내 문장, 음량 비율, 정차 전 버튼 잠금. 접근 판단 방식은 아래 '먼저 확인할 것' 결정 후

### 4. 출발 전 화면
- L1 오늘의 통학(변경 확인 후 진행), L13 기관 승차(한 명씩 승차·벨트, 타지 않음 기록), L11 아이 상세(가상 보호자 목록)

### 5. 등원 흐름
- 운행 방향을 상태에 추가하고 정류장 행동을 `인계받기 → 승차·벨트 확인`으로, 마지막을 기관 인계로 바꾼다(L8, 기관 인계)

### 6. 예외·종료·기록
- L6 선택지 3개와 기록, 동승 유지 아이를 L7에서 정리해야 종료, L12 운행 기록(보관본 읽기)

### 7. 주행 중 잠금과 분할 다듬기
- `FLOW.md`의 잠금 표대로 버튼을 막고, 분할 창에서 타임라인 접기·동요 바 축약을 확인

## 먼저 확인할 것

| 항목 | 왜 | 방법 |
|---|---|---|
| 화면 밀도와 앱 창 크기 | 캔버스 px → dp 환산 | 에뮬레이터 `adb shell wm density`, `wm size`, 앱 창 크기 로그 |
| 분할 창 실제 폭·비율 | 세로 레이아웃 기준(현재 620×920 가정)과 분기 폭 | Pleos에서 분할 실행 후 측정. 공식 Display Layout 문서에는 수치 없음, Window Policy 문서 미확인 |
| 정류장 접근 감지 | 하차 준비 자동 전환(시안 300m) | 현재 PoC는 위치 기반 도착 판정이 없음. FusedLocation 사용 여부 또는 수동 유지 결정 |
| 주행 중 잠금 신호 | 기록·순서 바꾸기·동요 목록 잠금 | 0.1.0에서 관측한 속도·기어·주차 브레이크 신호를 재확인 |
| 실제 이동 시간 | 순서 바꾸기 도착 시각 | 지점쌍 도로 시간 API는 공개 SDK에서 확인하지 못했음. 승인된 공급원 전까지 합성 값 |
| 보호자 알림·전화·사진 | L2 알림, L6 전화, L11 인계 가능자 | 연동 방식·개인정보 처리 결정 전에는 화면만 |
| 음원 권리 | 동요 재생목록 | 사용 가능한 음원 확인 전에는 시험 멜로디 |

## 하지 않을 것

- 센서·카메라·좌석 신호로 '잔류 없음'이나 하차 완료를 자동 확정
- 위험 미검출을 문 해제나 출발 승인으로 사용
- 실제 아동·보호자 정보 저장, 서버·외부 발송, 외부 제출·배포
