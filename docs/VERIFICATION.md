# 통학 PoC 검증 결과

## 통학메이트 2단계 — 경로 확인과 순서 바꾸기

2026-11-02 `feat/tonghak-mate-ui` @ `e8563ff` 위 작업 트리에서 검증했다(검증 시점 미커밋).

- **경로 확인(L2):** 탑승을 마친 뒤 '운행 시작' 자리에 방문 순서 띠(가상 출발 15:30 기준 도착·복귀 시각, 정류장별 인원), 정류장 메모, `순서 바꾸기` · `이 순서로 · 운행 시작`을 둔다. 자동 계산 전에는 운행 시작 때 적용될 거리 기준 순서(`RoutePlanner.plan`)를 미리 보여준다. 왼쪽에는 평소와 달라진 점(지금은 탑승 기록 없는 아이와 내릴 친구가 없는 정류장만), 보호자 도착 알림 스위치(상태만 `route-settings`에 저장, 실제 발송 없음), 순서를 정하는 기준(처음 한 번 자동으로 펼침, 이후 버튼으로만)을 둔다
- **순서 바꾸기(L9):** 위·아래 버튼으로 한 칸씩 옮기면 `RouteTimeline`으로 도착·복귀 시각을 다시 계산하고 원래 시각을 취소선으로 보인다. 약속보다 이른 도착, 진입 방향, 복귀 마감 17:30 초과를 경고하고, 왼쪽에 복귀·총 소요 비교, 확인할 점 개수, 바꾸는 이유(선택)를 둔다. `취소` · `제안 순서로 되돌리기` · `이 순서로 정하기`
- **엔진 반영:** `ShuttleEngine.applyManualRoute`가 `RouteObjective.FIXED` 제안을 만들어 운행 전에는 `previewRoute`/`applyPreview`(→`applyRoute`)로, 정차 중(ALIGHTING)에는 지나온 지점과 지금 정류장을 고정한 채 남은 순서만 바꾼다. 주행 중·정차 신호 없음이면 거절하고, 바꾼 이유는 진단 기록에 남긴다. 내비는 기존처럼 다음 목적지로 넘어갈 때 새 순서의 다음 정류장을 받는다
- **이동 시간:** 기존 합성 행렬(`RoutePlanner.syntheticMatrix`, 시간 기준 '모의 분')을 쓰며 화면에 "합성 이동 시간 · 실제 도로 시간 아님"을 표시한다. 출발 시각·약속·마감·메모는 가상 예시(`SampleRoutePlan`)다
- **빌드:** `--rerun-tasks` 80개 작업 성공. 단위 **104개** 통과(이전 87 + `RouteTimelineTest` 7 + 엔진 순서 변경 4 + `RoutePlanningTest` 6). lint 오류 0 / 경고 38 / 정보 2로 변화 없음. [빌드](../evidence/route-reorder-2/build.log)
- **기기 시험:** 앱·시험 APK 해시 일치. **17개 통과**(60.8초) — 새 시험은 탑승 → 경로 확인 → 올림픽공원을 첫 번째로 옮김(진입 방향 경고 확인) → 이유 선택 → 정함 → 운행 시작 시 첫 안내가 올림픽공원 → 정차 중 남은 두 곳 순서 바꿈 → 인계·하차 후 다음 안내가 바뀐 순서의 국립중앙박물관인지 확인한다. [기기 시험](../evidence/route-reorder-2/device-tests.log), [화면](../evidence/route-reorder-2/device/), [설치 대조](../evidence/route-reorder-2/identity.json)
- **사용자 기록 보존:** 1단계 보관본 기준으로 운행 파일을 원본 바이트로 되돌렸고 설정·완료 보관본 3개는 그대로다. 새 `route-settings`는 시험이 비우고 복원해 빈 설정(기본값과 같음)으로 남았다

**설계상 결정:** 운행 파일(`TripState`)에는 필드를 추가하지 않았다. 모델에 `serialVersionUID`가 없어 필드를 더하면 저장된 기존 운행과 완료 보관본을 읽지 못하게 되기 때문이다. 경로 설정은 SharedPreferences, 순서 초안은 화면 상태, 바꾼 이유는 진단 기록에 둔다. 5단계(운행 방향 추가) 전에 직렬화 호환 방법을 먼저 정해야 한다. 디자인 흐름은 경로 확인 → 기관 승차지만 지금은 기존 흐름대로 탑승 기록 → 경로 확인 → 운행 시작이며, 순서는 4단계(L1·L13)에서 맞춘다.

**미검증:** 분할 창(841 dp)의 경로 확인·순서 바꾸기 화면, SDK 연결 모드에서 순서를 바꾼 뒤 실제 NaviHelper 요청은 확인하지 않았다. 정차 중 순서 바꾸기의 도착 시각도 가상 출발 15:30 기준 예시이며 현재 시각을 반영하지 않는다.

## 통학메이트 1단계 — 테마와 화면 골격

2026-11-02 `feat/tonghak-mate-ui` @ `1817b46` 위 작업 트리에서 검증했다(검증 시점 미커밋). 하원 흐름·버튼 순서·안전 조건·음성/음악 동작은 그대로 두고 모양만 바꿨다.

- **변경:** `docs/design/code-drafts/designsystem/theme`를 `designsystem/theme`로 옮기고 크기·글자를 `canvasDp`/`canvasSp`(캔버스 px × 1.3)로 환산했다. 기존 `ShuttleUi.kt`의 자동차 화면 밀도 1.4배 보정과 `MaterialTheme(lightColorScheme(Teal…))`·`Ink`/`Teal`/`Mist`/`Amber` 상수를 없애고 `AppTheme`를 적용했다. `ShuttleUi.kt`(659줄)는 `ui/`의 화면 파일 7개와 `ui/component/`의 공통 부품(헤더, 정류장 타임라인, 동요 바, 버튼, 칩, 카드, 입력란)으로 나눴다. 창 폭 1200 dp 이상은 왼쪽 타임라인(468 dp) + 본문 + 하단 동요 바, 미만은 '다음 · 이후' 줄 + 한 열이다. 시험이 쓰는 testTag는 모두 유지했다
- **글꼴:** IBM Plex Sans KR 1.1.0 hinted TTF 4종을 IBM 공식 저장소에서 받아 `res/font`에 넣고 OFL 라이선스를 `assets/licenses/`에 두었다. 출처는 [TOKENS.md 글자](design/TOKENS.md#글자). 디버그 APK가 47.6 MB에서 51.3 MB로 늘었다
- **빌드:** `--rerun-tasks`로 80개 작업 모두 실행, 성공(종료 0). 단위 **87개** 통과(기존 82 + 새 타임라인 상태 시험 `TripDisplayTest` 5). lint 오류 0 / 경고 38 / 정보 2로 0단계와 같고, 새 파일에서 나온 경고는 없다. [빌드](../evidence/theme-skeleton-1/build.log)
- **설치·기기 시험:** Pleos_Connect_v2.1.0 / emulator-5554 / 사용자 10에 앱·시험 APK를 업데이트 설치했고 두 해시 모두 빌드본과 일치했다. `ShuttleFlowDeviceTest`·`AudioDeviceTest`·`SpeechDeviceTest`·`PleosTtsRuntimeDeviceTest` **16개 통과**(57.0초). 이 시험은 전체 창(1682×1210 dp)에서 돌았다. [기기 시험](../evidence/theme-skeleton-1/device-tests.log), [설치 대조](../evidence/theme-skeleton-1/identity.json)
- **화면:** 전체 창은 시험이 찍은 10장(탑승·출발 준비·이동·인계·하차·실내 확인·종료·명단·시험 도구·진단), 분할 창(841 dp)은 기존 사용자 운행(하원 완료)의 운행 화면과 명단을 손으로 찍었다. [시험 화면](../evidence/theme-skeleton-1/device/), [전체 창](../evidence/theme-skeleton-1/full-ended.png), [분할 운행](../evidence/theme-skeleton-1/split-ended.png), [분할 명단](../evidence/theme-skeleton-1/split-details.png)
- **사용자 기록 보존:** 시험 전 운행 파일·설정·완료 보관본 3개를 `${HOME}/Documents/PleosShuttlePoC-archives/20261102-081037-before-ui-step1`에 보관했다. 시험과 앱 실행으로 운행 파일에 '운행과 기록 복원' 진단 항목이 붙어(운행 ID·종료 상태·명단은 그대로) 원본 바이트로 되돌렸고, 다섯 파일 모두 보관본과 해시가 일치함을 확인했다

**미검증:** 분할 창에서 진행 중인 운행(타임라인 '다음 · 이후', 인계 버튼)은 기기에서 확인하지 못했다. 분할 상태에서 흐름 시험을 다시 돌렸지만 시험이 앱을 전체 창으로 열어(화면 폭 1682px) 분할 검증이 되지 않았다([기록](../evidence/theme-skeleton-1/device-split-guided.log)). 사용자 운행을 바꾸지 않으려고 분할에서 새 모의 운행을 시작하지 않았다. 색 대비 실측, 실제 차량 화면, SDK 연결 모드 화면, 다크 모드는 확인하지 않았다. `ProcessRestoreDeviceTest`와 SDK 계측은 실행하지 않았다. Gradle 로그는 4,463바이트/109줄로 Headroom 조건보다 작아 호출하지 않았다.

## 통학메이트 0단계 — 브랜치와 화면 크기 확인

2026-11-02 `feat/shuttle-poc` @ `9932943`에서 `feat/tonghak-mate-ui`를 만들고 디자인 인계 자료(`docs/design/`)를 `7b14248`로 커밋했다. 앱 코드는 바꾸지 않았다. 디자인 README의 기준 `aace9b1`은 지금 브랜치 이력에 없지만 `9932943`과 트리가 같다(`git diff aace9b1 HEAD` 차이 없음).

- **실행 환경:** macOS, Zulu 21(`JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home`), `Pleos_Connect_v2.1.0` / emulator-5554 / Android 14 / 사용자 10. 측정 전 에뮬레이터가 꺼져 있어 `-port 5554 -no-snapshot-save`로 새로 부팅했다
- **화면·창 측정:** `wm size` 2560×1440, `wm density` 160(1 dp = 1 px). 앱 창은 혼자 실행 시 1682×1210 dp, 순정 내비와 분할 시 841×1210 dp(구분선 24px, 50:50 고정). 측정값·환산 제안·분기 폭·Pleos 창 정책 요약은 [TOKENS.md 단위](design/TOKENS.md#단위)에 적었다. [측정 기록](../evidence/design-prep-0/window-measurements.json), [전체 창](../evidence/design-prep-0/window-full.png), [분할 창](../evidence/design-prep-0/window-split-with-navi.png)
- **빌드:** `bash ./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest`를 처음 실행했을 때 80개 작업이 모두 up-to-date라 시험·lint가 다시 돌지 않았다([기록](../evidence/design-prep-0/build-up-to-date.log)). `--rerun-tasks`로 다시 실행해 80개 작업을 모두 실행했고 성공했다(종료 0). 단위 **82개** 통과, lint 오류 0 / 경고 38 / 정보 2. [빌드](../evidence/design-prep-0/build.log)
- **설치:** `bash scripts/install-on-pleos.sh emulator-5554`로 업데이트 설치(앱 데이터 유지). 설치 APK SHA-256이 빌드본 `7d9df310…`과 일치했다. 설치 후 기존 사용자 운행의 '하원 완료' 화면이 그대로 열렸다. [설치 대조](../evidence/design-prep-0/identity.json), [설치 후 화면](../evidence/design-prep-0/after-install.png)

**미실행:** 기기 계측 시험(`am instrument`)은 앱 코드 변경이 없어 이번 단계에서 실행하지 않았다. 실제 차량 디스플레이, Form Factor 가상 디스플레이(12.9인치 세로·14.6인치 가로), 내비 외 앱과의 분할, 통학 앱을 오른쪽 창에 둔 분할은 측정하지 않았다. Gradle 로그는 4,463바이트/109줄로 Headroom 적용 조건보다 작아 호출하지 않았고, 과거 이력 조사가 필요하지 않아 QMD 검색도 하지 않았다.

## 0.1.5-poc 명단·상세 정보 밀도 개선

2026-10-30 구현 `03b4bfa`, 화면 시험 `76619a3`, 최종 정정 버튼 보완 포함 소스 `fea9b61`. 명단은 네 명의 이름·하차 지점·기록 상태와 정정 버튼으로 정리했다. 상단은 명단/운행 기록/테스트 도구 세 메뉴로 줄였고, 별도 조작·관측·로그를 필요한 때 열도록 분리했다. 기본 하원 흐름과 동일한 간결한 오디오 바를 쓴다.

- **빌드·설치:** 앱·시험 APK 빌드와 lint 성공(오류0, 경고34, 정보2). Pleos Connect v2.1.0 API34 사용자10에 설치하고 양쪽 APK 해시 일치를 확인했다. [빌드](../evidence/details-0.1.5/build.log), [시험 빌드](../evidence/details-0.1.5/test-build.log), [설치 대조](../evidence/details-0.1.5/identity.json)
- **화면 검증:** 기기9개 시험 통과(39.778초). 네 명이 스크롤 없이 보임, 기술 로그 기본 숨김, 정정 사유 없이 변경 불가, 로그 펼침·접힘, 하원 복귀 후 명단·기록 유지 확인. 기존 자동 음성/전체 하원/문 시험/복원/기록 보관/음악 바 경로도 같은 화면 시험에서 확인했다. [시험 결과](../evidence/details-0.1.5/device-tests.log), [명단](../evidence/details-0.1.5/details-roster.png), [시험 선택](../evidence/details-0.1.5/details-tools.png), [접힌 진단](../evidence/details-0.1.5/details-diagnostics.png)
- **사용자 기록:** 사용자 운행 `fictional-afternoon-1793369458128`은 이미 ENDED/차내0/기록8이었다. `${HOME}/Documents/PleosShuttlePoC-archives/20261030-233311-before-015`에 보관하고 시험 후 운행·설정 바이트 일치를 확인했다. 초기화하지 않고 완료 기록의 명단 상세를 열어두었다. [보존 대조](../evidence/details-0.1.5/preservation.json)

이번 변경은 상세 UI에 한정한다. 이전 단위82개·오디오 전용6개는 다시 실행하지 않았으며 이전 결과를 새 실행으로 합산하지 않는다. 실차·팀원의 사용 편의성은 미검증이다. 기존 두 프로젝트·오디오 엔진·음량은 변경하지 않았다. 최종 실제 Gradle 로그는 4,440바이트/95줄로 Headroom 적용 조건보다 작아 호출하지 않았다. 새 이력 조사가 필요하지 않아 QMD 검색도 반복하지 않았다.


## 0.1.4-poc 자동 안내와 선생님 기록 중심 흐름

2026-10-30 정본 `<project-root>`, `feat/shuttle-poc`. 구현 `6961d1f`, 시험 포함 소스 `0f864ec`. Pleos Connect v2.1.0 / API34 / 사용자10에 업데이트 설치했고 앱·시험 APK 해시를 빌드본과 대조했다. [설치 동일성](../evidence/operational-flow-0.1.4/identity.json), [검증 소스 해시](../evidence/operational-flow-0.1.4/verified-source-sha256.txt)

- **흐름 변경:** 운행 시작 한 번으로 합성 거리 순서를 계산·적용한다. 도착·정차 확인 후 인계와 하차만 기록하며, 마지막 아이 기록 뒤 다음 지점·안내로 자동 전환한다. 복귀 후 실내 직접 확인과 종료 저장은 한 버튼이다. 문 시험·진단은 상세로 분리했고 정상 기록에 문 시험이나 미지원 API 사유를 요구하지 않는다. 미하차를 남기는 예외에는 기존 사유 절차를 유지한다.
- **자동 음성:** 운행 시작·도착 확인·다음 지점·종료 단계의 한국어 음성을 자동 요청한다. UI 갱신, 아이별 기록, 같은 화면 복귀로 중복 요청하지 않는다. 앱 프로세스를 다시 열면 현재 안내가 한 번 나올 수 있다. 음성 실패 표시·다시 듣기·배경 중지·음악 복귀 정책은 유지한다.
- **검증:** 단위 **82개**, 기기 **14개** 모두 통과(기기 53.567초). lint 오류0/경고34/정보2. 세 지점·출발지 복귀·종료를 스크롤·탭 변경·음성 버튼·문 시험 없이 끝냈다. 안내 단계 키9개 중복 없음, 모의 목적지4곳, 첫 자동 한국어 발화 시작·완료, 탑승4/하차4와 저장 ENDED를 확인했다. [빌드](../evidence/operational-flow-0.1.4/build.log), [단위·lint](../evidence/operational-flow-0.1.4/unit-lint.json), [기기](../evidence/operational-flow-0.1.4/device-tests.log), [단계·음성 기록](../evidence/operational-flow-0.1.4/guided-complete-flow.json)
- **중간 실패:** 첫 실행 중 Pleos 에뮬레이터 프로세스가 종료돼 시험이 중단됐다(원인 미확정). 재실행·원본 복원 후 음성 시험 하나가 완료 콜백 중간 상태를 읽어 실패했다. 시험을 오디오 소유 스레드에서 전체 완료 처리 뒤 읽도록 수정한 후 최종14개가 통과했다. 앱 오디오 엔진 코드는 바꾸지 않았다. [중단 로그](../evidence/operational-flow-0.1.4/device-interrupted.log), [수정 전 시험](../evidence/operational-flow-0.1.4/device-before-race-fix.log)
- **보존·체험 준비:** 사용자 완료 운행 `fictional-afternoon-20261030-223033`의 원본을 `${HOME}/Documents/PleosShuttlePoC-archives/20261030-230256-before-014`에 보관했다. 최종 시험 후 운행·설정 바이트 일치를 확인했다. 앱의 보관 버튼으로 완료 운행을 `completed-trips`에 저장하고 ID·명단·8건·종료 기록·방문 순서 일치를 확인했다. 새 모의 운행 `fictional-afternoon-1793369458128`의 PREPARING/미탑승4명/기록0건 첫 화면을 열어두었다. 기존 미완료 운행 보관본도 유지한다. [보존 대조](../evidence/operational-flow-0.1.4/preservation.json), [현재 첫 화면](../evidence/operational-flow-0.1.4/ready-new-trip.png)

음량은 미디어28/45·안내40/45로 유지했다. 이전 사용자가 확인한 목소리 청취와 이번 자동 발화 콜백 검증은 구별한다. 새 자동 흐름의 실제 청취·사용 편의성은 사용자 확인 대기다. 위치 기반 도착 자동 판정, 실제 차량·현장 인계, 이번 변경의 실제 NaviHelper 자동 요청은 미검증이다. 모의에서 실제 차량·내비 어댑터를 만들지 않았으며 기존 SDK 검증을 이번 버전 결과로 확대하지 않는다. PleosTest `0ecc72f`, PleosProbe `1f8e3e3`는 변경 없이 유지했다.

보조 도구 지침은 실제 적용했다. QMD Usage ID `9edb600e-9a05-47cd-927a-f74b3527cd50`로 과거 내비·도착 경계를 검색하고 원문 확인 후 Basic Memory 대비 neutral 피드백을 저장했다. Headroom은 최종 실제 Gradle 로그가 4,666바이트/101줄(종료0)로 32KiB/200줄 조건에 못 미쳐 호출하지 않았다. [사용 범위 기록](../evidence/operational-flow-0.1.4/tool-trial.json)


## 0.1.3-poc 동승 선생님 중심 화면

2026-10-30 사용자가 주 사용자를 **동승 선생님**으로 확정했다. 구현 `1381795`, 시험 포함 `a0cbb51`, versionCode 4. 정본은 `<project-root>`이며 기존 두 프로젝트는 변경하지 않았다.

- 기본 화면은 현재 지점·기록상 차내 인원·어린이 한 명과 다음 행동만 표시한다. 인계 확인 뒤 같은 자리에서 하차 기록으로 전환하고 다음 아이로 진행한다. 운전자 확인은 해당 단계에만 구분한다. 기술 설명·전체 절차·정정·차량·시험·진단은 `명단 · 상세`로 이동했다. 버튼은 Material의 shape-aware 구성요소를 사용한다.
- 기록/문 요청 실패, 정차 미확인, 주변 위험, 종료 전 좌석 점유 경고는 기본 화면에서도 확인할 수 있다. 문 해제나 기록 처리를 자동 실행하지 않으며 기존 상태 전이·안전 조건은 유지했다. 음악·음성은 하단 두 버튼으로 시작/중지한다.
- 앱·시험 APK 빌드, 단위 **75개**, 기기 **14개**(화면 8·음악 2·음성 3·TTS 비교 1) 통과. lint 오류 0 / 경고 34 / 정보 2. 기기 시험 50.264초. [빌드](../evidence/teacher-flow-0.1.3/build.log), [단위·lint 집계](../evidence/teacher-flow-0.1.3/unit-lint-results.json), [기기 결과](../evidence/teacher-flow-0.1.3/device-tests.log)
- 기본 화면에서 세 지점과 종료 저장까지 스크롤·탭 전환 없이 진행했다. 인계 전 하차 버튼과 다음 아이 버튼의 부재, 인계 후 버튼 교체, 이동 중 하차/문 조작 부재를 확인했다. 기존 정차·만료·복원·보관 시험은 명시적으로 상세 화면에서 실행했다. [단계별 기록](../evidence/teacher-flow-0.1.3/guided-complete-flow.json), [화면 안내](WALKTHROUGH.md)
- Pleos Connect v2.1.0 / API 34 / 사용자 10 설치 앱과 시험 APK 해시가 빌드본과 일치했다. 음성 시험은 실제 Activity를 열어 복원 진단을 갱신하므로, 종료 후 앱을 멈추고 시험 전 운행 파일 원본을 명시적으로 복원했다. 재조회 시 운행 파일·모의 설정 바이트 일치, 없던 보조 파일은 계속 없음. [설치 동일성](../evidence/teacher-flow-0.1.3/identity.json), [복원 대조](../evidence/teacher-flow-0.1.3/preservation.json)

이번 검증은 구현과 모의 흐름 확인이다. 새 화면의 실제 사용 편의성은 사용자 확인 대기이며 실차·현장 인계는 미검증이다. 오디오 엔진·음량은 변경하지 않았고, 이전에 보고된 시험음 잡음의 해결을 뜻하지 않는다.

## 0.1.2-poc 전체 사용 흐름 후속 검증

2026-10-30 `feat/shuttle-poc`의 시험 소스 `61765fd`로 첫 지점만 확인하던 무스크롤 시험을 세 지점 전체와 출발지 복귀·실내 확인·종료 저장까지 확대했다. 앱 구현·버전·음량은 변경하지 않았다.

- 앱·시험 APK 빌드 성공, Pleos Connect v2.1.0 / API 34 / 사용자 10에서 화면 시험 **8개 통과**(38.452초). 이번에는 화면 시험을 재실행했으며 앞선 단위 75개·기기 14개 전체를 다시 실행한 것은 아니다.
- 모든 필수 버튼이 표시되고 활성화됐는지 확인한 뒤 클릭했다. 이 사례에서 수동 스크롤이나 탭 변경을 호출하지 않았다. 사유 입력 후 키보드 완료 동작도 포함한다.
- 첫 지점의 모의 문 해제, 이후 두 지점의 수동 문 처리 기록, 네 명 전원 하차와 기록 8건(탑승 4/하차 4), 저장 파일의 ENDED 상태를 확인했다. 센서 범위 미확인은 그대로 남았다. 실제 차량·내비 어댑터가 생성되지 않은 것도 확인했다.
- 시험마다 기존 운행 파일과 모의 설정을 격리하고 종료 시 복원한다. 시험 뒤 앱을 다시 열어 서울숲 하차 단계·기록상 차내 4명·남은 3지점·문 수락 후 잠금 유지 설정을 확인했다. [복원 화면](../evidence/guided-complete-0.1.2/restored-current-trip.png), [앱·시험 APK 설치 해시 대조](../evidence/guided-complete-0.1.2/identity.json). 사용자 체험의 편의성, 실제 차량·보호자 인계는 별도 검증 대상이다.

[체험 안내와 화면 7장](WALKTHROUGH.md) · [단계별 기록](../evidence/guided-complete-0.1.2/guided-complete-flow.json) · [화면 시험 결과](../evidence/guided-complete-0.1.2/device-tests.log) · [시험 APK 빌드](../evidence/guided-complete-0.1.2/build.log) · [앱 빌드](../evidence/guided-complete-0.1.2/app-build.log)

## 0.1.2-poc 한국어 음성 안내 수정

2026-10-30 정본 `<project-root>`의 `feat/shuttle-poc`에서 수정하고 같은 Pleos AVD(API 34, 사용자 10)에 업데이트 설치했다. 구현 `cb1a534`, 시험 포함 소스 `f890159`이며 빌드 APK와 설치 APK의 SHA-256이 일치한다. [빌드·설치·소스 동일성](../evidence/tts-0.1.2/build-install-identity.json), [소스 해시](../evidence/tts-0.1.2/verified-source-sha256.txt)

- **원인:** Android 기본 TTS는 엔진을 찾지 못해 초기화 -1을 반환했다. CAAS SpeechService는 일반 조회에 나타나지만 기본 TTS의 `MATCH_DEFAULT_ONLY` 조회에는 나타나지 않는다. 앱의 queries 선언 누락과는 구분된다.
- **수정:** Pleos TextToSpeech 3.3.1의 ON_DEVICE, `ko-KR`, 완결 문장 요청으로 연결했다. 서버 등록·별도 엔진 설치 없이 초기화 성공, onStart, onDone, 비영(非零) RMS를 관측했다. 이것만으로 실제 청취를 판정하지 않는다. [기본 Android / Pleos 비교](../evidence/tts-0.1.2/pleos-tts-runtime.json)
- **조작:** 하단 `음성 안내`가 다음 지점 이름을 읽고 `음성 중지`로 준비·재생 중인 안내를 취소한다. 안내 중 음악은 쉬고 이전 재생 의도가 유지된 경우에만 돌아온다. 앱을 벗어나면 안내와 자동 복귀를 중지한다.
- **검증:** 빌드·단위 **75개**, 기기 **14개**(음성 어댑터 3·엔진 비교 1·음악 2·사용 흐름 8) 통과. lint 오류 0, Warning 34, Information 2. [빌드](../evidence/tts-0.1.2/build.log), [단위](../evidence/tts-0.1.2/unit-results.json), [기기](../evidence/tts-0.1.2/device-tests.log)
- **복귀·취소:** [음악 복귀](../evidence/tts-0.1.2/speech-music-device.json), [초기화 취소·발화 중지·재시도](../evidence/tts-0.1.2/speech-cancel-retry-device.json), [배경 전환](../evidence/tts-0.1.2/speech-background-device.json)을 실제 앱 어댑터에서 확인했다. 요청 세대·실패 후 재시도·시간 제한의 단위 시험도 통과했다.
- **화면·청취:** 앱의 버튼으로 서울숲 안내를 재생하고 화면의 `안내 완료` 및 하단 다섯 버튼을 확인했다. 후속 청취에서 안내 음량 20/45는 알아듣기 어려웠고 45/45에서는 문장이 잘 들린다고 사용자가 확인했다. 다만 지지직거림이 남았다. 사용자 요청으로 40/45로 낮춰 다시 재생했다(이후 완전 재시작 뒤 40에서 목소리가 괜찮다는 사용자 확인을 받음). 음악은 28/45와 기존 설정을 유지했다. [화면](../evidence/tts-0.1.2/speech-completed.png), [청취·화면 기록](../evidence/tts-0.1.2/audibility-and-visual-review.json)

기존 하차 단계·차내 기록 4명·남은 3지점·ACCEPT_ONLY 상태를 보존했다. PleosTest/PleosProbe는 기존 커밋과 clean 상태를 유지했다. 이번 변경에서 차량·순정 내비 SDK 동작, 강제 프로세스 종료, 순정 내비 음성과의 실제 겹침은 다시 시험하지 않았다. 아래 이전 버전 기록을 이번 버전의 재시험 결과로 취급하지 않는다.

### 0.1.2 후속 음질 비교

비교 계측 소스 `debf1be`에서 **시험음→한국어 안내 1개**, 기존 음성·음악 수명주기 **3개**를 실행해 통과했다. 실제 두 소스의 안내 채널 일치와 원래 운행 파일 바이트 복원을 확인했다. 앱 APK는 기존과 동일하다. [비교 로그](../evidence/tts-quality-0.1.2/comparison-device.log), [전환 재시험](../evidence/tts-quality-0.1.2/speech-lifecycle-recheck.log), [앱·운행 파일 동일성](../evidence/tts-quality-0.1.2/baseline.json)

TTS 트랙의 데이터 부족은 관측되지 않았고, 내부 믹서의 처리 주기 지연은 관측됐다. 지지직거림과의 인과관계는 확정하지 않았다. 현재 에뮬레이터 버전과 출력 장치는 알려진 macOS 다채널 이슈 조건과 맞지 않는다. 재비교 후 사용자는 **시험음과 목소리 둘 다 잡음이 섞여 들린다**고 보고했다(원문 “반반이야 둘다”). 공통 출력 경로가 우선 조사 대상이며 구체적인 원인·실차 음질은 계속 미검증이다. [상세 판단과 팀원 재현 순서](AUDIO.md#지지직거림을-좁히는-비교-시험)

완전 재시작 후 같은 비교 시험 1개도 통과했다. 운행 저장 파일·음량을 보존했고 사용자는 **시험음은 심하게 지지직거리지만 목소리는 괜찮다**고 확인했다. 현재 40/45 음성 청취는 양호하며 시험음 잡음과 정확한 원인은 남아 있다. [재시작 근거](../evidence/tts-quality-0.1.2/cold-restart/assessment.json)

## 0.1.1-poc 화면 흐름·소리 개선 검증

2026-10-30 `<project-root>`에서 빌드하고 `Pleos_Connect_v2.1.0` / emulator-5554 / Android 사용자 10에 업데이트 설치했다. 설치 APK와 빌드 APK의 SHA-256 일치를 확인했다. [빌드·설치 동일성](../evidence/flow-audio-0.1.1/build-install-identity.json)

- 상단에 전체 흐름·현재 담당자·다음 할 일을 표시하고 완료 후 다음 버튼을 자동으로 드러낸다. 입력 중 자동 이동은 보류하고 확인 동작은 직접 누르게 유지했다.
- 음악 조작을 하단에 고정하고 실제 재생 위치·길이를 갱신한다. 출력 경로와 TTS 결과는 별도로 표시한다.
- 단위 시험 **70개**, 실제 화면 **8개**와 오디오 **2개**, 총 기기 시험 **10개** 통과. lint는 오류 0, Warning 33, Information 2다. [단위 결과](../evidence/flow-audio-0.1.1/unit-results.json), [기기 결과](../evidence/flow-audio-0.1.1/device-tests.log), [빌드](../evidence/flow-audio-0.1.1/build.log)
- 새 화면 시험은 스크롤 명령 없이 4명 탑승 → 순서 계산·적용 → 안내 → 하차·문 확인 → 인계·하차 → 사유 → 운행 복귀까지 다음 버튼의 실제 표시를 확인했다. 기존 전체 하원·만료·복원·손상 파일·Intent 시험도 통과했다. [화면](../evidence/flow-audio-0.1.1/guided-flow-device.png)
- 시험 후 기존 사용자의 하차 단계·기록상 차내 4명·남은 3지점·문 수락 후 잠금 유지 시나리오를 다시 확인했다. 기존 PleosTest/PleosProbe 저장소도 변경 없이 유지했다.
- 음악은 차량 미디어 음량 **20/45 → 28/45** 조정 후 사용자가 청취 크기가 좋다고 확인했다. **당시 TTS는 초기화 -1로 미동작**이었다(0.1.2 수정은 위 참조). Mac 음량과 내비·음성 그룹은 유지했다. [소리 진단](../evidence/flow-audio-0.1.1/audio-diagnosis.json)

검증한 앱 소스는 `67bcc28`이다. 오디오 수정 `b033e21`, 화면 흐름 `4cae2c9`, 버전 구분 `67bcc28`을 로컬 커밋했으며 푸시하지 않았다.

이번 변경에서 실제 차량 SDK 제어·순정 내비·강제 프로세스 종료 시험은 다시 실행하지 않았다. 아래 0.1.0 기록은 그 버전의 검증 이력으로 보존하며 새 버전의 재실행 결과로 취급하지 않는다. 실차·실제 어린이 운송은 검증 범위 밖이다.

## 0.1.0-poc 기준 버전과 결과

- 앱 `com.hanhyo.pleosshuttle`, `0.1.0-poc` / versionCode `1`
- 앱 APK SHA-256: `116e650410459142d4836bc3b7074f99008cf69230ca5fae7538b891b09bcacd`
- AVD: `emulator-5556` / `Pleos_Connect_v2.1.0`, Android 14 / API 34, product `crp_avd_arm64`, hardware `ranchu`
- Vehicle SDK/Service `2.3.2 / 2.3.22`, NaviHelper SDK/Service `3.0.5 / 3.0.4`
- 최종 앱 빌드 이후 앱 소스·APK는 유지하고 계측의 정리·캡처 처리만 수정해 테스트 APK를 다시 빌드함
- 코드·계측 기준 커밋: `cce0ef7`, [빌드·설치 동일성](../evidence/build-install-identity.json)에서 소스와 APK 해시를 연결함. AVD에 설치된 APK 해시도 위 값과 일치함

| 확인 항목 | 실제 결과 | 근거·범위 |
|---|---|---|
| 앱·계측 APK 빌드 | 성공 | [최종 빌드 로그](../evidence/final-verified-build.log), [소스 해시](../evidence/verified-source-sha256.txt) |
| 단위 테스트 | 60개 통과, 실패·오류·건너뜀 0 | [단위 결과](../evidence/final-unit-results.json), 명단/상태 27·경로 10·오디오 7·문 조건 9·연결 세대 3·Area 보존 4 |
| lint | 오류 0, Warning 33, Information 2 | `app/build/reports/lint-results-debug.xml`, 경고가 없는 결과는 아님 |
| 모의 사용 흐름 | 6개 모두 통과, 23.006초 | [흐름 계측](../evidence/device-flow-final.log), 전체 하원·만료/이동·복원·손상 파일·모의 입력 유지·로컬 Intent 포함 |
| 실제 오디오 | 2개 통과 | [재생 관측](../evidence/audio-player-device.json), [포커스 관측](../evidence/audio-focus-device.json), 실제 청취·순정 내비 음성과의 겹침은 별도 |
| 실제 프로세스 복원 | 준비·검증 각 1개 통과, PID `20959 → 28930` | [종료 전](../evidence/process-restore-prepare.json), [재실행 후](../evidence/process-restore-verify.json), 동일 UID·운행/탑승 이력·DISCONNECTED 유지, 원본 복원 true |
| 실제 SDK 값 | 0m/s·P·주차 브레이크 false·문 잠금 true, 점유/벨트 5개 Area 관측 | [최종 SDK 관측](../evidence/sdk-runtime-observation.json), 해당 AVD의 결과이며 모든 차량/좌석 지원 보장은 아님 |
| SDK 실행기·재구독 | 1개 통과, 18.163초 | [SDK 계측 로그](../evidence/sdk-runtime-final.log), 2회 연결 세션에서 종료 후 콜백·중복 등록·정리 오류 0 |
| 실제 내비 SDK | 요청 이름·좌표와 ROUTE_GUIDING 일치, 취소 이벤트·빈 목적지·SAFE_GUIDING 확인 | [최종 SDK 관측](../evidence/sdk-runtime-observation.json)의 navigationTrial |
| 순정 내비 화면 | 목적지·27분/26km·80m 좌회전 안내·경로선·안내 종료 표시 확인 | [호스트 캡처](../evidence/native-navigation-guiding.png)·[화면 대조 기록](../evidence/visual-review.json), 배경 지도 타일은 비어 보이므로 완전 렌더링 미확인 |
| 실제 문 해제 | **차단, 요청 0회** | [첫 SDK 시도](../evidence/sdk-runtime-first-final-attempt.json)의 unlockTrial에서 주차 브레이크 미체결 관측, 입력 변경·반복 요청·자동 재잠금 없음 |

초기 시험에서 관측 시각 차이와 화면 캡처 충돌을 수정했고, Intent 시험은 본문 완료 뒤 ActivityScenario 정리에서 실패해 시험 정리 코드를 보완한 다음 위 6개를 다시 통과함
`initial-sdk-read-before-area-fix.json`는 복합 Area를 잘못 펼치던 구현의 이력이므로 **최종 지원 Area·미지원 판단 근거에서 제외**함
관측 JSON 저장, SDK 실행기 전체 통과, 화면을 눈으로 대조한 사실은 각각 구분함
내비는 첫 캡처 충돌 시험과 최종 시험에서 각각 요청 1회·취소 1회씩 수행했고, 문 조건 시험은 첫 시도에서만 실행해 실제 요청 0회였음
최종 JSON의 `nativeScreenVerified=false`는 계측 코드가 이미지 판독을 하지 않는다는 뜻으로 유지하며, 위 화면 확인은 별도의 호스트 캡처 대조 결과임

## 기능별 확인 범위

| 기능 | 구현·관측 | 남은 경계 |
|---|---|---|
| 탑승·인계·하차·정정·종료 | 가상 어린이 수동 기록, 이력 보존, 직접 실내 확인, 완료 기록 보관 후 새 운행을 UI로 검증 | 실제 교사·운전자·보호자의 현장 사용 미검증 |
| 차량·좌석 | 실제 SDK/AAOS 값·출처·시각·오류 관측, 가상 좌석과 자동 매핑하지 않음 | 실제 승하차 전이와 모든 좌석 지원을 입증한 것은 아님 |
| 문 그룹 | SDK 원본 HOOD와 ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT를 보존, 시험 대상은 후자의 전체 그룹 | 단일 어린이 문 제어가 아님, 해제 호출은 조건 미충족으로 실행하지 않음 |
| 방문 순서 | 승인 지점의 직선거리 합성 비용 완전탐색, 기본 3개 지점의 6가지 순서와 출발지 복귀 비용 비교 | 실제 도로 거리·시간 행렬이나 도로 최단 경로가 아님 |
| 다음 지점 안내 | 앱이 전체 목록을 관리, NaviHelper는 다음 지점 하나씩 안내, 서울시청 통학 PoC 시험·37.5665, 126.9780 반영과 순정 화면의 안내 표시 확인 | 배경 지도 전체 렌더링은 미확인, 위치 도착으로 하차 자동 기록 안 함 |
| 음악·포커스 | 생성 시험 멜로디의 재생 위치·일시정지·정지·배경 복귀, TRANSIENT_LOSS·일시정지·수동 정지 유지 관측 | MAY_DUCK 요청은 수락됐지만 duck 콜백이 없고 앱 음량은 1, **실제 감쇄를 관측했다고 표현하지 않음** |
| TTS | 0.1.2에서 Pleos ON_DEVICE 한국어 발화 시작·완료, 음악 복귀·취소·재시도·배경 정리 확인 | 45/45에서 실제 청취 확인, 지지직거림 잔존. 최종 설정 40/45. 순정 내비 안내와 별도 |
| Gleo | 초안의 로컬 Intent 3개가 화면/재생만 실행하고 운행 단계·문 요청 수를 바꾸지 않음을 검증 | 스키마 제출·승인·활성화·음성 호출 미검증 |
| 주변·안전 기능 | 모의 경고와 운전자 직접 확인, 일반 문 잠금과 별개 표시 | 주변 카메라·접근 물체·SEA·어린이 잠금의 현재 앱 접근 미확인 |

음원은 직접 생성한 4.70초 시험 멜로디이며 동요 녹음이 아님
공식 플랫폼 전체 지원·실차·실제 어린이 운송·순정 내비 음성과 음악의 실제 청취는 검증하지 않음
미수신·만료·권한 거절을 빈 좌석·0km/h·위험 없음·완료로 바꾸지 않으며 모든 센서가 비어 있어도 안전을 자동 확정하지 않음

## 요청한 9개 시나리오 재현

처음은 **모의 체험 / 정차 · P / 가상 어린이 4명 / 문 잠김**으로 시작
미완료 운행이 있으면 먼저 기록을 확인하며, 완료 운행은 `완료 기록 보관 후 새 모의 운행`으로 보존한 뒤 새로 체험

| 시나리오 | 조작 순서 | 확인할 결과 |
|---|---|---|
| 1 정상 하원 | 4명 탑승 → 순서 계산·미리보기·적용 → 운행·다음 지점 안내 → 하차 선택·주변 확인·문 최종 확인 → 대면 인계·하차 → 사유와 함께 복귀 반복 → 출발지 복귀·실내 직접 확인·종료 | 문 상태가 하차 기록을 자동 생성하지 않음, 다음 지점의 문이 이미 해제돼 있으면 사유와 수동 문 처리 기록 사용 |
| 2 신호 정차 | 운행 중 `신호 정차 · D` 선택, 하차 버튼은 누르지 않음 | 운행 단계 유지·문 요청 0회, 속도 0만으로 P/하차 상태를 만들지 않음 |
| 3 이동·미확인·만료·경고 | 이동 중·속도/기어 미확인·접근 물체 경고 각각 선택 → 정상 정차에서 문 확인 준비 후 상태 변경 → 관측 갱신 중단에서 5초 경과 | 이유와 함께 제한, 대기 확인 무효화, 오래된 확인으로 뒤늦게 요청하지 않음 |
| 4 수락 후 잠금 유지 | 해당 시나리오 → 하차 선택·주변 확인·문 최종 확인 → 결과 대기 | 수락과 시간 초과·잠금 유지 표시, 해제 완료로 판정하거나 반복 요청하지 않음 |
| 5 명단·센서 불일치와 잔류 | 좌석 점유 불일치에서 하차 기록과 종료 화면 비교 → 미하차 기록을 남긴 경우와 센서 연결 끊김도 확인 | 명단과 Area 관측 분리, 미하차가 남으면 종료 불가, 직접 실내 확인·최신 정차 확인 필요 |
| 6 재실행·배경 복귀 | 미완료 운행에서 홈·복귀 → 강제 종료·재실행 → 연결 끊김/갱신 중단 상태에서도 반복 | 이력·단계·모의 입력 유지, 해제/주변/실내 확인은 다시 요구, 실제 프로세스 시험은 아래 명령 참고 |
| 7 합성 계산·안내 실패 | 비용 출처 확인 → 적용 전 수동 순서 조정 → 다음 지점 확인, 실제 내비 실패 상태와 비교 | 승인 지점만 재정렬, 실제 도로 최단 경로라고 표현하지 않음, 실패해도 다음 지점 이름·좌표 제공 |
| 8 음악·포커스 | 시험 멜로디 → 일시 포커스 요청·반환 → 감쇄 요청·반환 → 수동 일시정지 후 반환 → 배경 복귀, TTS는 별도 | 콜백·재생 위치·앱 음량·실제 소리 구분, 진단 포커스는 순정 내비 음성이 아님 |
| 9 모의 자동 해제 격리 | 모의 자동 해제 보기 → 이동·미확인·경고에서도 반복 → 요청 수 확인 | 별도 모의 표현만 바뀌고 실제 요청 0회, 모의 흐름에서 실제 차량/내비 어댑터를 만들지 않음 |

관측 5초·문 최종 확인 5초·주변 직접 확인 30초·실내 확인 60초는 **PoC 시험용 만료값**이며 실제 운송 안전 기준이 아님
미확인이 남은 운행 화면 복귀는 사유와 운전자 확인을 기록하는 앱 전환이며 실제 차량 출발을 막거나 허용하는 제어가 아님

## 자동 시험 재현

저장소 루트에서 실행하며 설치 절차는 [README](../README.md)를 참고
기기 번호만 믿지 말고 현재 AVD 이름·fingerprint와 Android 사용자 번호를 먼저 확인

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
bash ./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest

POC_ADB="$HOME/Library/Android/sdk/platform-tools/adb"
POC_SERIAL=emulator-5554
"$POC_ADB" -s "$POC_SERIAL" emu avd name
"$POC_ADB" -s "$POC_SERIAL" shell am get-current-user
"$POC_ADB" -s "$POC_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$POC_ADB" -s "$POC_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$POC_ADB" -s "$POC_SERIAL" shell am instrument --user 10 -w -e class \
  com.hanhyo.pleosshuttle.ShuttleFlowDeviceTest,com.hanhyo.pleosshuttle.audio.AudioDeviceTest,com.hanhyo.pleosshuttle.audio.SpeechDeviceTest,com.hanhyo.pleosshuttle.audio.PleosTtsRuntimeDeviceTest \
  com.hanhyo.pleosshuttle.test/androidx.test.runner.AndroidJUnitRunner
```

`ShuttleFlowDeviceTest`는 이 PoC의 운행 파일·모의 설정을 백업·격리·복원하고 실제 SDK 모드를 선택하지 않음
`ProcessRestoreDeviceTest`는 기본 전체 시험에서는 건너뛰며 **준비 → 실제 강제 종료 → 새 프로세스 검증**으로 실행
이 AVD에서 확인한 Android 사용자는 `10`이며 다른 환경에서는 조회 결과에 맞춰야 함

```bash
POC_USER=10
"$POC_ADB" -s "$POC_SERIAL" shell am instrument --user "$POC_USER" -w \
  -e class com.hanhyo.pleosshuttle.ProcessRestoreDeviceTest -e stage prepare \
  com.hanhyo.pleosshuttle.test/androidx.test.runner.AndroidJUnitRunner
"$POC_ADB" -s "$POC_SERIAL" shell am force-stop --user "$POC_USER" com.hanhyo.pleosshuttle
"$POC_ADB" -s "$POC_SERIAL" shell am instrument --user "$POC_USER" -w \
  -e class com.hanhyo.pleosshuttle.ProcessRestoreDeviceTest -e stage verify \
  com.hanhyo.pleosshuttle.test/androidx.test.runner.AndroidJUnitRunner
```

이 명령은 새 시험의 순서임
`process-restore-backup`이 이미 있으면 준비 단계가 덮어쓰기를 거절하며, 중단된 시험은 검증/원본 복원을 먼저 처리해야 함
완료된 보관본도 증거로 남으므로 무조건 삭제하거나 기존 보관본 위에서 준비를 반복하지 않음

SDK 기본 계측은 읽기 전용이며, 다음 스크립트는 **공개 목적지 요청·취소를 포함하는 내비 시험**과 호스트 화면 캡처를 수행함
정확한 Pleos fingerprint가 아니면 중단하고, 기존 활성 경로가 있으면 내비 변경 시험을 차단하며 자신의 시험 경로만 취소함
문 제어는 이 스크립트에 포함하지 않음

```bash
python3 scripts/verify-sdk-on-pleos.py emulator-5556
```

호스트가 PNG를 저장해도 자동으로 화면 일치 판정이 되는 것은 아님
이름·좌표·안내 모드의 SDK 관측과 순정 화면을 따로 비교하고, `evidence/sdk-runtime-final.log`의 시험 완료 및 취소 후 목적지 없음도 확인

## 증거와 남은 확인

주요 근거는 위 표의 로그·JSON이며 앱 내부 생성 파일에는 `flow-device-evidence.json`, `gleo-local-intent-evidence.json`, `sdk-runtime-observation.json`, `diagnostics.txt`가 있음
파일 존재만으로 전체 시험 통과를 추정하지 않고 생성 시각·APK·시험 종료 상태를 함께 확인

| 남은 항목 | 상태 |
|---|---|
| 실제 문 잠금 해제 | **차단 유지** · 주차 브레이크 미체결, 요청 0회, 해제 성공 미검증 |
| 순정 내비 배경 지도 전체 렌더링 | **미확인** · 목적지·회전 안내·경로선은 보이지만 배경 지도 타일은 비어 보임 |
| 순정 내비 음성과 음악의 실제 감쇄·청취 | **미검증** · 진단용 포커스 요청과 구분 |
| TTS 음질 | **재시작 후 40/45에서 음성 청취 양호** · 같은 시험음은 심한 지지직거림 잔존. 전체 오디오 해결이나 정확한 원인·실차 음질은 미검증 |
| Gleo 음성/스키마 활성화 | **미검증** · 로컬 Intent 시험과 구분 |
| 공식 차량별 지원·실차·실제 사용자 운영 | **범위 밖/미검증** · 현재 AVD 결과를 일반화하지 않음 |

공개 API 근거는 [API_FINDINGS.md](API_FINDINGS.md), 음원 출처와 포커스 정책은 [AUDIO.md](AUDIO.md)에 연결함

## 로컬 변경 이력

| 커밋 | 변경 단위 | 확인 근거 |
|---|---|---|
| `015d46d` | 명단·운행 상태·방문 순서 | 도메인·경로 단위 37개 |
| `b31a4da` | 생성 음원·재생·오디오 포커스 | 정책 단위 7개, AVD 오디오 2개 |
| `1c61a03` | 실제 차량·내비 어댑터 | SDK 조건 단위 16개, 실제 관측·내비·제어 차단 결과 |
| `418d05f` | Compose 사용 흐름·저장·앱 구성 | 최종 앱 빌드·lint, UI 6개·프로세스 복원 |
| `cce0ef7` | AVD 계측·증거 추출 도구 | 위 최종 계측 결과와 호스트 캡처 |

문서·증거는 후속 로컬 커밋으로 보관함. 원격 저장소·푸시·외부 배포는 수행하지 않음


## 0.1.6 팀원 체험 편의 기능 (2026-10-31)
음악 정지/시험 멜로디 표시, 진행 중 가상 운행 보관 후 새 체험, 세 단계 선택, 보관 요약, Android 텍스트 공유 창을 추가했다. 실제 발송은 하지 않았다.
Zulu 21에서 assembleDebug, testDebugUnitTest(82개), assembleDebugAndroidTest, lintDebug 통과. Pleos AVD API 34 / 사용자 10에 설치했다. ShuttleFlowDeviceTest 10개 최종 통과(42.862초). 첫 실행은 재실행 화면이 사라지는 오류 2건으로 실패했으며 원인은 확정하지 않았다. 안정된 연결에서 동일 전체 클래스 재검사는 통과했다.
새 검사는 보관 내용 일치, 새 운행 ID, 세 단계 상태, 저장 재조회, 가상 기록 미생성, 진단 내용, 음악 정지 버튼 노출을 확인했다. 공유 대상 앱에서 실제 수신 및 팀원 사용성은 미검증이다. 시험은 기존 체크포인트를 격리 후 복원하며 생성한 시험 보관 파일만 제거했다.
