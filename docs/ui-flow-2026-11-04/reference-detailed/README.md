# 통학메이트 화면 이동도

작성일: 2026-11-04. 현재 로컬 구현을 코드에서 확인한 설명 자료이며 UI 변경안의 구현이나 실제 스크린샷이 아니다.

## 열기

index.html을 더블클릭한다. 학부모/차량/연동 탭을 선택하고 화면에 맞춤 또는 원본 크기로 본다. 인쇄 버튼은 세 장 모두를 인쇄한다. 01-parent, 02-vehicle, 03-sync의 SVG는 확대용, PNG는 문서/발표 삽입용이다. 인터넷 연결이 필요 없다.

## 중요한 정정

- 학부모 앱은 아이 목록→상세로 이동하지 않는다. 홈에 여러 아이의 카드가 나열되고 각 카드 상태가 갱신된다.
- 가입은 로그인 폼 전환, 어린이 추가 연결과 최근 기록은 홈 안 펼치기, 설정은 팝업이다.
- 푸시 알림 탭은 앱 홈을 열 뿐 특정 아이 카드로 바로 이동하지 않는다.
- 차량 메인 하원은 단계별 본문 교체다. 상세 패널에는 명단/운행 기록/기관 연결/테스트 도구 탭이 있다.
- 메인 하원의 마지막 아이 하차 기록은 다음 목적지 진행을 함께 수행한다. 매번 별도 다음 지점 버튼이 필수인 것은 아니다.
- 마지막 하차 지점 뒤에는 출발지 복귀, 복귀·정차 확인, 미하차 확인, 실내 확인·종료가 있다.
- 보호자 미도착 등 사유 기록은 현재 메인 인계 화면이 아닌 상세 조작 경로에 있다.
- 내비 전면 요청과 차량 근접 알림은 다른 기능이다. 후자의 실제 팝업·탭 복귀는 현장 미검증이다.

## 코드 근거

저장소 루트: C:\SSAFY\PleosShuttlePoC

| 항목 | 파일 | 확인 위치 |
|---|---|---|
| 학부모 홈·폼·펼치기·팝업 | parent/src/main/java/com/hanhyo/shuttle/parent/MainActivity.kt | ParentScreen |
| 하원 카드 상태·공유 | parent/src/main/java/com/hanhyo/shuttle/parent/TrackingCard.kt | TrackingContent |
| 정류장 진행 | parent/src/main/java/com/hanhyo/shuttle/parent/GuardianJourney.kt | GuardianStopLine |
| 알림 탭의 진입점 | parent/src/main/java/com/hanhyo/shuttle/parent/PushService.kt | Intent(MainActivity) |
| 차량 하원/상세 패널 구조 | app/src/main/java/com/hanhyo/pleosshuttle/ui/ShuttleApp.kt | ShuttleContent, DetailTabs, MainPanel |
| 탑승→인계→하차→종료 본문 | app/src/main/java/com/hanhyo/pleosshuttle/ui/TeacherFlowScreen.kt | TeacherFlow |
| 단계 선택 기준 | app/src/main/java/com/hanhyo/pleosshuttle/FlowGuide.kt | nextFlowGuide |
| 기관 연결·명단 준비 | app/src/main/java/com/hanhyo/pleosshuttle/ui/InstitutionPanel.kt | InstitutionPanel |
| 명단 준비 후 하원 복귀 | app/src/main/java/com/hanhyo/pleosshuttle/ShuttleViewModel.kt | loadInstitutionTrip |
| 순서 확인·수정 | app/src/main/java/com/hanhyo/pleosshuttle/ui/RouteScreens.kt | RouteCheckBody, ReorderBody |
| 상세 진입·기록 보기 | app/src/main/java/com/hanhyo/pleosshuttle/ui/RosterScreen.kt | RosterPanel, TripHistory |
| 미도착 사유·상세 조작 | app/src/main/java/com/hanhyo/pleosshuttle/ui/TripControlsScreen.kt | Alighting, EndReview |
| 자동 다음 지점 진행 | app/src/main/java/com/hanhyo/pleosshuttle/domain/ShuttleEngine.kt | recordAlightingAndContinue |
| 하단 기관·내비 버튼 | app/src/main/java/com/hanhyo/pleosshuttle/ui/VehicleTrackingPanel.kt | VehicleTrackingSummary |
| 공유 웹 | server/track.html | 링크 수신·진행·지도 옵션·만료 |
| 실제 기기 검증 이력 | evidence/tracking/trip-flow/README.md, evidence/tracking/default-pleos/README.md | 11/3, 11/4 기록 |

## 미적용 개선 제안

1. 여러 아이일 때 홈 목록/상세 분리 여부 검토.
2. 알림 탭에서 해당 아이 카드로 직접 이동·강조.
3. 보호자 미도착 예외 처리를 메인 인계 화면에서 열기.
4. 일상 운행과 개발/문 시험/중복 상세 조작 경로 분리.
5. 순서 검토의 예시 계산 시간과 실제 Pleos 예상 시간의 시각적 구분.

위 제안은 이동도와 분리해 HTML 하단의 상세 설명에 기록했다. 앱 코드는 수정하지 않았다. 이번 작업에서는 과거 기기 시험을 재실행하지 않았고 문서 구조·산출물·렌더링을 확인한다.
