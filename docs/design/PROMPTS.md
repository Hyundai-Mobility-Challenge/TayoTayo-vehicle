# 코딩 에이전트 요청문

Mac의 `PleosShuttlePoC` 폴더에서 Claude Code 또는 Codex에 붙여 넣는 요청문이다. 새 세션마다 **공통 시작**을 먼저 붙이고 해당 단계 요청을 이어 붙인다. 한 번에 한 단계만 진행한다.

권장 분담: 여러 파일 구조를 바꾸는 단계(1·2·5)는 Claude Code, 범위가 좁은 단계(0·3·4·6·7의 개별 화면)는 Codex도 가능.

## 공통 시작

```
이 repo는 Pleos 통학 앱 PoC(PleosShuttlePoC)야. 작업 전에 다음을 먼저 해줘.
1. AGENTS.override.md, AGENTS.md(있으면), CLAUDE.md(있으면)와 상위 지침을 읽고 따라.
2. docs/design/README.md, IMPLEMENTATION_PLAN.md, 그리고 이번 단계에 해당하는 SCREENS.md·FLOW.md·TOKENS.md 부분을 읽어.
3. git status, 현재 branch, 마지막 commit을 확인하고 알려줘. 미커밋 변경이 있으면 건드리기 전에 물어봐.
지켜야 할 원칙:
- 센서·카메라·좌석 신호나 기록으로 안전이나 하차 완료를 자동 확정하지 않는다. 도착·인계·차내 확인은 선생님이 직접 누른다.
- 문 해제를 자동 실행하지 않는다. 실제 아동·보호자 정보, 서버, 외부 발송, 외부 제출·배포는 하지 않는다. 데이터는 가상 예시만 쓴다.
- 이번 단계 범위 밖의 화면·기능·음량 설정은 바꾸지 않는다.
- android-architecture / android-compose / android-theme 스킬이 있으면 그 패턴을 따르되, 이 PoC는 단일 app 모듈이라 모듈 분리는 하지 않는다.
끝나면: bash ./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest 를 실행하고
(JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home), bash scripts/install-on-pleos.sh emulator-5554 로
설치한 뒤 바뀐 화면을 캡처해 evidence/ 아래에 두고, docs/VERIFICATION.md 에 실행 환경·명령·결과를 단계별로 기록해.
빌드·설치·기기 화면 확인 중 하지 못한 것은 성공으로 쓰지 말고 미실행으로 적어. 커밋은 단계가 끝나고 내가 확인한 뒤에 해.
```

## 0. 준비 — 브랜치와 화면 크기 확인

```
0단계: 구현 준비만 해줘. 앱 코드는 바꾸지 마.
1. feat/shuttle-poc 에서 feat/tonghak-mate-ui 브랜치를 만들고, 추적되지 않은 docs/design/ 을 "docs: 통학메이트 디자인 인계 자료" 로 커밋해.
2. Pleos 에뮬레이터에서 adb shell wm size, adb shell wm density 와 앱 창 크기(전체화면, 그리고 화면 분할로 열었을 때)를 측정해.
   분할은 다른 앱(순정 내비 등)과 나란히 띄워서 앱 창의 실제 폭·높이를 기록해.
3. 측정값으로 docs/design/TOKENS.md 의 '단위' 절을 갱신해: 캔버스 px → dp 환산 비율, 분할 창 크기,
   code-drafts/designsystem/theme/AppDimens.kt 의 compactWidthBreakpoint 제안값.
4. Pleos Window Policy 문서를 찾을 수 있으면 분할 정책을 요약해서 출처와 함께 같은 절에 적어. 못 찾으면 못 찾았다고 적어.
```

## 1. 테마와 화면 골격

```
1단계: 테마와 화면 골격. docs/design/IMPLEMENTATION_PLAN.md 의 1단계 범위만.
1. docs/design/code-drafts/designsystem/theme/ 를 app/src/main/java/com/hanhyo/pleosshuttle/designsystem/theme/ 로 옮기고,
   0단계에서 정한 dp 환산을 AppDimens·AppTypography 에 반영해.
2. IBM Plex Sans KR(Regular/Medium/SemiBold/Bold)을 공식 배포처에서 받아 res/font/ibm_plex_sans_kr_*.ttf 로 넣고
   OFL 라이선스 파일을 함께 둬. 출처 URL을 docs/design/TOKENS.md 에 적어.
3. ShuttleUi.kt 의 MaterialTheme(lightColorScheme(...)) 와 Ink·Teal·Mist·Amber 상수를 AppTheme 로 바꿔.
4. ShuttleUi.kt 를 화면별 파일(ui/ 아래)과 공통 부품(헤더, 정류장 타임라인, 동요 바, 주요/보조 버튼, 칩)으로 나눠.
5. 창 폭이 compactWidthBreakpoint 이상이면 가로(왼쪽 타임라인 360 + 본문 + 하단 동요 바),
   미만이면 세로 분할(한 열, 타임라인은 '다음·이후' 줄)로 그려.
기존 하원 흐름, 버튼 순서, 안전 조건, 음성·음악 동작은 그대로 두고 모양만 바꿔. 기존 단위·기기 시험이 모두 통과해야 해.
docs/design/canvas/ 의 L03·L05·L07·P02·P03·P04 를 수치 참고로 써.
```

## 2. 경로 확인과 순서 바꾸기

```
2단계: 경로 확인(L2)과 순서 바꾸기(L9). SCREENS.md·FLOW.md 의 해당 절을 기준으로.
1. docs/design/code-drafts/domain/RouteTimeline.kt 와 test/RouteTimelineTest.kt 를 app 모듈의 domain/ 과 test/domain/ 으로 옮기고
   실제 JUnit 으로 시험을 통과시켜.
2. L2: 평소와 달라진 점, 보호자 도착 알림 스위치(상태만 저장, 실제 발송 없음), '순서를 정하는 기준 보기'(처음 한 번 자동 표시 후
   버튼으로만), 방문 순서 띠, 정류장 메모를 구현해. 정류장 메모·규칙은 가상 데이터로.
3. L9: 위·아래 버튼으로 한 칸씩 이동, RouteTimeline 으로 도착 시각·복귀 시각 재계산, 원래 시각 비교, 경고(약속 시간보다 이른 도착,
   진입 방향, 마감 초과), 바꾸는 이유 선택, 취소·제안 순서로 되돌리기·이 순서로 정하기.
4. 정한 순서는 ShuttleEngine.previewRoute/applyRoute 와 RouteObjective.FIXED 로 반영하고, 내비에는 다음 정류장부터 새 순서를 넘겨.
5. 순서 바꾸기는 운행 전 또는 정차 중에만 열려. 이동 시간은 기존 합성 행렬을 쓰고 화면에 합성 값임을 숨기지 마.
엔진 상태 전이 시험(ShuttleEngineTest)에 순서 변경 경우를 추가해.
```

## 3. 상황별 동요와 하차 준비

```
3단계: 동요 고르기(L10·P5), 동요 바, 하차 준비(L4).
1. 재생목록 모델과 화면: 이번 주 노래, 교통안전, 숫자·한글 놀이, 영어 동요, 계절 노래, 잔잔한 노래. 곡명은 [곡명] 자리표시이고
   음원은 지금의 시험 멜로디(res/raw/test_melody.wav)를 그대로 써.
2. 자동 동작 설정: 운행 시작 시 재생, 정류장 접근 시 음량(끄기/30%/50%), 정차·인계 중 일시정지(항상, 끌 수 없음),
   출발 시 이어서 재생, 이번 주 노래 먼저. LocalAudioAdapter·AudioFocusPolicy 에 연결해.
3. 동요 고르기는 운행 전·정차 중에만 열리고, 주행 중에는 재생·일시정지·다음 곡만 돼.
4. L4 하차 준비: 안내 음성 문장, 음량 줄임, 정차 전에는 인계 시작 버튼 잠금. 접근 판단은 0단계·팀 결정에 따르고,
   정해지지 않았으면 기존처럼 수동 전환 버튼으로 두고 그렇게 적어.
기존 음성 안내·오디오 포커스 시험이 그대로 통과해야 하고, 음량 그룹 설정은 바꾸지 마.
```

## 4. 출발 전 화면

```
4단계: 오늘의 통학(L1·P1), 기관 승차(L13), 아이 상세·인계 가능자(L11).
1. L1: 오늘의 변경(결석, 하차 지점 변경, 인계자 변경)을 가상 데이터로 보여주고 모두 확인해야 경로 확인으로 넘어가.
   결석 아이는 경로와 명단에서 빠지고, 지점·인계자 변경은 해당 정류장과 인계 화면에 반영돼야 해.
2. L13: 기관에서 한 명씩 탑승·벨트 확인, '타지 않음으로 기록'(사유 남김), 모두 처리해야 출발.
3. L11: 인계 화면에서 아이 이름을 누르면 열림. 가상 인계 가능 보호자 목록(사진 자리표시, 관계, 전화 버튼은 동작 없이 표시),
   '목록에 없는 사람이 왔어요' → 예외 화면으로 연결.
실제 사진·연락처는 넣지 마.
```

## 5. 등원 흐름

```
5단계: 등원 흐름(L8, 기관 인계). FLOW.md 의 '등원' 상태도와 등원·하원 차이 표를 기준으로.
1. TripState 에 운행 방향(등원/하원)을 추가하고 ShuttleEngine 전이를 방향에 따라 나눠.
   등원: 정류장마다 인계받기 → 승차·벨트 확인, 예외는 '아이가 안 나왔어요'(전화·대기·오늘 미탑승), 마지막은 기관 도착 → 기관 인계 → 종료 점검.
2. 저장된 기존 하원 운행 파일이 그대로 복원돼야 해(방향 기본값 하원). 복원 시험을 추가해.
3. 안내 음성 문장(AutomaticTripGuidance)과 FlowGuide 를 등원에 맞게 추가해.
4. 오늘의 통학에서 등원/하원을 고를 수 있게 해.
ShuttleEngineTest 에 등원 전체 흐름과 예외 경우를 추가해.
```

## 6. 예외·종료·기록

```
6단계: 보호자 미도착(L6), 종료 점검(L7·P4), 운행 기록(L12).
1. L6: 전화(동작 없이 표시) · 3분 더 기다리기 · 동승 유지(기관에서 인계). 고른 내용과 시각을 기록에 남겨.
2. L7: 기록 대조 타일, 동승 유지한 아이는 '기관 선생님께 인계함'으로 정리해야 종료 가능, 직접 확인 3항목,
   좌석·벨트 확장 슬롯(보조 정보 문구, 직접 확인을 대신하지 않음).
3. L12: TripStore 의 completed-trips 보관본을 읽어 시간순 기록과 요약을 보여줘. 센서 정보는 기록으로 바꾸지 마.
기존 미하차 사유·정정 규칙과 종료 조건 시험이 그대로 통과해야 해.
```

## 7. 주행 중 잠금과 분할 다듬기

```
7단계: FLOW.md 의 '주행 중 잠금' 표를 적용하고 세로 분할 화면을 다듬어.
1. 속도·기어 관측(RealVehicleAdapter)으로 주행 중에는 기록, 순서 바꾸기, 동요 목록, 예외 처리를 막고 보기·안내 다시 듣기·음악 조작만 허용.
   차량 정보가 미확인이면 기존처럼 기록을 막아.
2. 분할 창에서 P1~P5 기준으로 헤더 축약, 타임라인 접기, 동요 바 아이콘 위주로 정리.
3. 모의 모드와 SDK 연결 모드 둘 다에서 화면을 캡처해 비교해.
잠금 조건 시험을 추가해.
```

## 마무리 점검

```
지금까지 단계를 점검해줘.
1. docs/design/SCREENS.md 의 화면별로 구현됨/일부/미구현을 표로 정리하고, 캔버스와 다른 점을 적어.
2. 전체 빌드·단위·기기 시험을 다시 실행하고 결과를 docs/VERIFICATION.md 에 기록해.
3. README.md 와 docs/WALKTHROUGH.md 를 통학메이트 흐름(등원·하원)에 맞게 갱신해.
4. 남은 확인 항목(IMPLEMENTATION_PLAN.md '먼저 확인할 것')의 현재 상태를 갱신해.
```
