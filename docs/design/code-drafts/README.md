# 코드 초안

앱 모듈 밖에 둔 초안이다. 이 폴더의 파일은 Gradle 빌드에 포함되지 않으며, 옮긴 뒤 반드시 빌드·시험으로 확인한다.

| 초안 | 옮길 위치 | 상태 |
|---|---|---|
| ~~`domain/RouteTimeline.kt`~~ | `app/src/main/java/com/hanhyo/pleosshuttle/domain/` | 2026-11-02 2단계에서 앱으로 옮김. `StopRule.memo` 추가 |
| ~~`test/RouteTimelineTest.kt`~~ | `app/src/test/java/com/hanhyo/pleosshuttle/domain/` | 2026-11-02 옮김. 실제 Gradle JUnit에서 7개 통과 |
| ~~`designsystem/theme/**`~~ | `app/src/main/java/com/hanhyo/pleosshuttle/designsystem/theme/` | 2026-11-02 1단계에서 앱으로 옮김(이 폴더에서 삭제) |

## RouteTimeline — 순서 바꾸기 계산 (옮김)

2단계(2026-11-02)에서 앱 모듈로 옮겼고 실제 `testDebugUnitTest`에서 7개가 통과했다. 엔진 연결은 `ShuttleEngine.applyManualRoute`. 아래는 옮기기 전 기록이다.

- 기존 `CostMatrix`의 값을 분으로 해석해 정류장별 도착 시각, 복귀 시각, 총 소요를 만든다. 합성 행렬이면 결과도 합성 시각이다
- `move`는 위·아래 버튼 한 번에 해당하는 한 칸 이동, `warnings`는 약속 시간보다 이른 도착 · 허용되지 않은 직전 지점에서 진입 · 마감 초과를 낸다
- 정한 순서는 기존 `ShuttleEngine.previewRoute`/`applyRoute`와 `RouteObjective.FIXED`로 반영하는 것을 제안한다(엔진 연결은 아직 없음)

검증 (2026-11-01, Claude Cowork 클라우드 작업 공간):

- 컴파일: kotlinc-jvm 2.0.0(프로젝트 Kotlin 버전과 같음), `-jvm-target 17`, 종료 코드 0
- `CostMatrix`는 `feat/shuttle-poc` @ `aace9b1`의 `RoutePlanner.kt`에서 같은 내용을 복사해 함께 컴파일했다
- JUnit 4.13.2 jar를 받을 수 없어 `Test`·`Assert`·`assertThrows`만 흉내 낸 최소 대체본으로 실행했다 — 7개 통과, 0개 실패
- 실제 Gradle `testDebugUnitTest`와 실제 JUnit에서는 실행하지 않았다

## 테마 — `designsystem/theme` (옮김)

1단계(2026-11-02)에서 앱 모듈로 옮겼다. 앱 쪽 변경: 크기·글자를 `canvasDp`/`canvasSp`(캔버스 px × 1.3)로 환산, `compactWidthBreakpoint` 1200 dp, IBM Plex Sans KR 글꼴 포함, Material3 1.2에 맞춰 최소 터치 보정을 `LocalMinimumInteractiveComponentEnforcement`로 끔. 검증은 [VERIFICATION.md](../../VERIFICATION.md)에 있다.
