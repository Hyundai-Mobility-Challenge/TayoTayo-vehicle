# 디자인 토큰

기계가 읽는 값은 [tokens.json](tokens.json), Compose 초안은 [code-drafts/designsystem/theme](code-drafts/designsystem/theme)에 있다.

## 단위

캔버스 수치는 CSS px다. 가로 1280×920은 PoC 에뮬레이터 캡처(1682×1210px, `evidence/details-0.1.5/details-roster.png`)와 같은 비율로 잡았다.

### 측정값 (2026-11-02, 0단계)

`Pleos_Connect_v2.1.0` AVD(emulator-5554, Android 14, 사용자 10)에서 앱 0.1.6-poc로 측정했다. 근거: [측정 기록](../../evidence/design-prep-0/window-measurements.json), [전체 창](../../evidence/design-prep-0/window-full.png), [분할 창](../../evidence/design-prep-0/window-split-with-navi.png)

| 항목 | 값 |
|---|---|
| `wm size` / `wm density` | 2560×1440 / 160 → **1 dp = 1 px** |
| 고정 영역 | 상단 시스템 바 높이 90, 하단 차량 바 높이 140, 왼쪽 Driving view 폭 853 |
| 앱 창 — 혼자 실행 | **1682×1210 dp** (`[853,90][2535,1300]`, `land`). 오른쪽 끝 약 25px는 창 손잡이(⋮) |
| 앱 창 — 순정 내비와 분할 | **841×1210 dp** (`[853,90][1694,1300]`, `port`), 내비는 `[1718,90][2559,1300]`, 사이 구분선 24px |
| 분할 비율 | 고정. 구분선을 조금 끌면 50:50으로 되돌아오고, 끝까지 끌면 한쪽 앱이 1682 창을 차지하고 다른 앱은 뒤로 간다 |

분할은 앱 창 오른쪽 손잡이를 왼쪽으로 끌어(`adb shell input swipe 2547 715 1700 715`) 만들었고, 순정 내비가 오른쪽 창에 열렸다. 다른 앱과의 분할, 통학 앱을 오른쪽 창에 둔 경우, 실제 차량 디스플레이는 측정하지 않았다.

### 환산

- 캔버스 → 실제 창 비율: 가로 1682/1280 = **1.314**, 높이 1210/920 = **1.315**. 세로 분할 캔버스 620×920 → 841×1210은 가로 1.356, 높이 1.315
- **확정: dp(sp) = 캔버스 px × 1.3**, 정수로 반올림(2026-11-02 사용자 결정). 1.3이면 1280×920 → 1664×1196, 620×920 → 806×1196으로 두 캔버스 모두 실제 창(1682×1210, 841×1210) 안에 들어가고, 남는 폭(18 / 35 dp)은 여백으로 흡수한다. 초안 코드의 '1 디자인 px = 1 dp' 가정은 이 측정으로 폐기한다
- 이 비율은 이 에뮬레이터에서 캔버스 비례를 유지하는 값이다. 실제 차량 화면의 물리 크기(dp → mm)는 확인하지 않았다. Pleos 디자인 가이드의 글자 높이 기준(주요 정보 5 mm, 보조 3 mm 이상)은 실차 디스플레이 규격을 확인한 뒤 따로 점검해야 한다

주요 값 환산. 1단계에서 `canvasDp`/`canvasSp`(×1.3 후 정수 반올림)로 `AppDimens`·`AppTypography`에 반영했고, 4 dp 격자에는 맞추지 않았다(캔버스 비례를 그대로 유지):

| 토큰 | 캔버스 | ×1.3 |
|---|---|---|
| touchMin / secondaryButton | 64 | 83 |
| primaryButton (분할) | 80 (72) | 104 (94) |
| headerHeight (분할) | 72 (64) | 94 (83) |
| timelineWidth | 360 | 468 |
| playerBarHeight (분할) | 88 (76) | 114 (99) |
| screenPadding (분할) | 24 (20) | 31 (26) |
| displayEta (분할) | 104 (88) | 135 (114) |
| stopName (분할) | 48/58 (34/44) | 62/75 (44/57) |
| head1 · head2 · head3 · head4 | 40 · 36 · 30 · 26 | 52 · 47 · 39 · 34 |
| title · body1 · body2 · body3 | 24 · 20 · 19 · 17 | 31 · 26 · 25 · 22 |
| caption1 · caption2 | 15 · 14 | 20 · 18 |

### 가로/세로 분기 폭

`compactWidthBreakpoint`는 **1200 dp**를 제안했고 1단계에서 `app/.../designsystem/theme/AppDimens.kt`에 반영했다(실제 dp, 캔버스 환산 아님). 초안 값 840 dp는 측정한 분할 창 폭 841 dp보다 작아 분할 창이 가로 레이아웃으로 그려진다.

- 분할 841 dp < 1200 → 세로 한 열, 전체 1682 dp ≥ 1200 → 가로(타임라인 + 본문)
- 1200 dp에서 가로 레이아웃 본문 폭은 1200 − 468 − 31×2 ≈ 670 dp로 카드 한 열이 들어간다. 다른 차량에서 분할·전체 창 폭이 달라도 두 상태 사이에 여유가 있다
- 창 크기는 앱 실행 중에도 바뀐다(분할 ↔ 전체). 분기는 시작 시 한 번이 아니라 현재 창 폭으로 매번 판단해야 한다

### Pleos 창 정책

'Window Policy'라는 이름의 공식 문서는 찾지 못했다(2026-11-02, document.pleos.ai 탐색). 같은 내용을 다루는 공식 문서는 [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)이며 요약은 다음과 같다. 수치(px·dp·비율)는 없다.

- 화면은 운전자 가까이 고정된 Driving view와 앱 영역으로 나뉜다. 앱은 Driving view를 가리거나 닫을 수 없다. 앱 영역에는 최대 2개의 앱이 놓이고, 차량·디스플레이 구성에 따라 window 1개 또는 2개로 나뉜다
- `portrait`는 window 하나, `landscape`는 한 앱이 두 영역을 합쳐 쓰는 형태다. portrait에는 핵심 기능과 주요 정보, landscape에는 부가 정보와 확장 기능을 두도록 나눠 설계한다
- landscape 앱이 떠 있으면 새 앱도 landscape로, portrait 앱 둘이 떠 있으면 새 앱은 primary window에 portrait로 열린다. 화면에서 빠진 앱은 종료되지 않고 백그라운드에 남는다
- 사용자는 window bar나 제스처로 앱의 위치와 크기를 바꿀 수 있으므로, 작은 크기에서도 핵심 기능이 동작하도록 반응형으로 구성한다
- 문서는 "`screenOrientation`을 별도로 지정하지 않으면 Activity는 기본적으로 portrait로 실행"된다고 적고 있다. 이 앱은 `screenOrientation`을 지정하지 않았는데, 이번 측정에서는 혼자 실행했을 때 1682 dp `land` 창으로 열렸다. 직전 화면 상태에 따른 결과일 수 있어 원인은 확정하지 않았다. 매니페스트 변경 여부는 1단계 이후에 판단한다

참고: [Form Factor 테스트 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)는 12.9인치 세로·14.6인치 가로 가상 디스플레이(DISPLAY2)로 레이아웃을 점검하는 방법을 설명하지만 해상도·밀도 수치는 적지 않았다. 이 가상 디스플레이에서는 측정하지 않았다.

## 색

라이트가 기본(사용자 결정). 다크는 첫 시안 값을 쌍으로 남겨 두었다(야간 모드 여부는 미정).

| 토큰 | 라이트 | 다크 | 쓰임 |
|---|---|---|---|
| background | `#FFFFFF` | `#0F1114` | 화면 바탕 |
| surface | `#F3F4F6` | `#1A1D22` | 패널, 동요 바, 헤더 칩 |
| surfaceRaised | `#FFFFFF` | `#242830` | 패널 안 카드, 보조 버튼 |
| surfaceInset | `#EBEDF0` | `#14171B` | 주행 중 안내, 기관 메모 |
| outline | `#DDE0E5` | `#2E333B` | 칩·카드 테두리, 아바타 바탕 |
| outlineStrong | `#CDD1D7` | `#3A3F47` | 보조 버튼 테두리 |
| outlineMuted | `#A9AEB6` | `#4A5059` | 예정 정류장 원, 점선 |
| textPrimary | `#16191D` | `#F4F1E8` | 본문 |
| textSecondary | `#4F555E` | `#B3B7BE` | 보조 문구 |
| textTertiary | `#646A73` | `#8C9199` | 캡션, 비활성 글자 |
| disabledContainer | `#E4E7EB` | `#2A2E35` | 비활성 주요 버튼 |
| primary | `#FFC93C` | `#FFC93C` | 주요 버튼·로고 **채우기 전용** |
| onPrimary | `#1F1600` | `#1F1600` | 노란 채우기 위 글자 |
| primaryStroke | `#A86F00` | `#FFC93C` | 현재 단계 테두리, 외곽선 버튼 |
| primaryContent | `#7A5000` | `#FFD566` | 노란 계열 글자 |
| primaryMarker | `#8A5A00` | `#FFC93C` | 번호 원 안 글자 |
| primaryContainer | `#FFF3CC` | `#3A3115` | 변경 칩, 안내 음성 배너 |
| primaryContainerOutline | `#E3C25E` | `#5C4C1C` | 미확인 변경 카드 테두리 |
| onPrimaryContainer | `#3D2E00` | `#FFF3D1` | 안내 음성 문장 |
| info | `#2A72C9` | `#8EC9FF` | 완료 표시, 체크, 스위치 켜짐 |
| onInfo | `#FFFFFF` | `#0F1114` | 블루 채우기 위 체크·글자 |
| infoContent | `#1D5FAF` | `#A6D4FF` | 블루 계열 글자, 음악 |
| infoContainer | `#E6F0FC` | `#172A3B` | 정차 확인, 탑승 카드, 미리 듣기 행 |
| danger | `#D0551F` | `#FF9466` | 예외 테두리·아이콘 |
| dangerContent | `#A83E10` | `#FFAA85` | 예외 글자 |
| dangerContainer | `#FDECE4` | `#3B1F16` | 예외 배너, 경고 |
| onDangerContainer | `#5A2410` | `#FFE1D4` | 예외 배너 안 본문 |
| neutralTag | `#EBEDF0` | `#2E333B` | 결석·빠짐 칩 바탕 |
| onNeutralTag | `#3A3F47` | `#D3D6DA` | 결석·빠짐 칩 글자 |

규칙:

- 흰 바탕에서 노란색은 대비가 낮아 **채우기에만** 쓰고, 테두리·글자는 호박색(`primaryStroke`·`primaryContent`)으로 바꾼다.
- 한 화면에 노란 채우기 주요 버튼은 하나. 같은 행동이 여러 개면 첫 번째만 채우고 나머지는 외곽선.
- 색만으로 상태를 구분하지 않는다. 글자(완료·확인함·예외 처리 중)와 아이콘을 함께 쓴다.
- 대비 목표: 본문 4.5:1 이상, 큰 글자·UI 테두리 3:1 이상. 위 라이트 값은 이 기준으로 골랐으나 기기 화면에서 실측하지 않았다.

## 글자

글꼴: IBM Plex Sans KR (400/500/600/700, SIL OFL 1.1). 차량에 Google Play 서비스가 없을 수 있어 다운로드 글꼴 대신 `res/font`에 넣는다.

- 출처: IBM 공식 저장소 [IBM/plex](https://github.com/IBM/plex) 릴리스 `@ibm/plex-sans-kr@1.1.0`(커밋 `1da12f0`)의 `packages/plex-sans-kr/fonts/complete/ttf/hinted/IBMPlexSansKR-{Regular,Medium,SemiBold,Bold}.ttf`. [릴리스 페이지](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-sans-kr%401.1.0)
- 앱 위치: `app/src/main/res/font/ibm_plex_sans_kr_{regular,medium,semibold,bold}.ttf`(각 약 2.5 MB, 합계 약 10 MB). 라이선스 `packages/plex-sans-kr/LICENSE.txt`를 `app/src/main/assets/licenses/IBM-Plex-Sans-KR-OFL.txt`로 함께 넣어 APK에 포함한다

| 토큰 | 크기/행간 | 굵기 | 쓰임 |
|---|---|---|---|
| displayEta | 104/104 | 700 | 남은 시간(가로) — 분할은 88 |
| stopName | 48/58 | 700 | 이동 중 정류장 이름 — 분할은 34/44 |
| head1 | 40/50 | 700 | 하차 준비 정류장, 완료 제목 |
| head2 | 36/46 | 700 | 정차·인계, 예외 제목 |
| head3 | 30/40 | 700 | 일반 화면 제목 |
| head4 | 26/32 | 700 | 헤더 운행 이름 |
| title | 24/30 | 700 | 아이 이름, 주요 버튼 글자 |
| body1 | 20/28 | 600 | 정류장 행, 보조 버튼 |
| body2 | 19/28 | 400–600 | 본문, 변경 내용 |
| body3 | 17/24 | 400 | 보조 문구, 칩 |
| caption1 | 15/20 | 400–700 | 타임라인 보조, 상태 라벨 |
| caption2 | 14/20 | 400–700 | 작은 칩, 메모 출처 |

숫자는 `tabular-nums`(고정폭 숫자)로 맞춘다.

## 크기

| 토큰 | 값 | 비고 |
|---|---|---|
| touchMin | 64 | 차량용 최소 터치. 미리 듣기·위아래 버튼 높이 46–52는 폭 64로 보완 |
| primaryButton | 80 (분할 72) | 화면 맨 아래 |
| secondaryButton | 64 | |
| headerHeight | 72 (분할 64) | |
| timelineWidth | 360 | 가로 전체화면만 |
| playerBarHeight | 88 (분할 76) | |
| screenPadding | 24 (분할 20) | |
| panelPadding | 28×32 / 20×16 | 본문 / 왼쪽 |
| gap | 16 / 12 / 8 | 영역 / 카드 / 칩 |
| radius | 20 패널 · 16–18 버튼·카드 · 14 행 · 999 칩 | |
