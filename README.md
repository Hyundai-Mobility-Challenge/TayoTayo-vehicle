# 통학메이트 차량 앱

Pleos 차량에서 동승 선생님의 탑승 기록, 정류장 안내, 보호자 인계, 하차 기록, 운행 종료 점검을 연결하는 Android 앱입니다. 기관 연결과 배정 명단, Pleos 내비/차량 관측을 이용한 위치 공유, 음성 안내 및 시험 음악을 포함합니다.

## 빌드

Android Studio에서 이 저장소 루트를 엽니다. Android SDK 35, JDK 17 이상(이 분리 작업은 Android Studio의 JBR 21 사용), Pleos Maven 저장소 접근이 필요합니다. 실행 대상은 지원되는 Pleos Connect 에뮬레이터입니다. 앱 최소 API는 28입니다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

macOS/Linux: `./gradlew`를 사용합니다. SDK 경로는 Android Studio가 만드는 `local.properties` 또는 `ANDROID_HOME`으로 설정합니다. 다른 저장소 체크아웃 없이 단독으로 빌드됩니다.

- 앱 모듈: `app`
- 앱 ID: `com.hanhyo.pleosshuttle`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Vehicle 2.3.2 / NaviHelper 3.0.5 / TextToSpeech 3.3.1
- Firebase 서버 키나 보호자 앱 설정 파일이 필요하지 않습니다.

## 연결과 시연

1. [서버](https://github.com/Hyundai-Mobility-Challenge/tonghak-server)를 실행하고 기관·차량·어린이를 등록합니다.
2. 차량의 **기관 · 배정 명단**에서 에뮬레이터는 `http://10.0.2.2:8080`, 실제 차량 기기는 `http://<PC의 LAN IPv4>:8080`과 기관에서 발급한 차량 페어링 코드를 입력합니다. 앱은 `/api/v1/vehicle/pair`를 호출해 차량 자격 증명을 저장합니다.
3. 기관 웹/API에서 차량·경로·어린이 배정 후 오늘 운행을 미리 생성해야 합니다. 차량 앱에서 등원(`PICK_UP`) 또는 하원(`DROP_OFF`) 방향을 선택하고 해당 운행을 불러옵니다. 앱에서 새 운행이나 명단을 생성하지 않습니다.
4. **배정 명단으로 운행 준비 → 탑승 기록 → 이 순서로 · 운행 시작**을 진행합니다. 진행 기록과 정류장 상태는 서버 API로 전송됩니다.
5. 정차 후 **도착·정차 확인 → 인계 확인 → 하차 기록**을 진행합니다.
6. 복귀 후 실제 차내 확인 절차를 수행하고 종료합니다.

[전체 연결 순서](docs/INTEGRATION.md), [현재 시연 순서](docs/DEMO_STEP_BY_STEP.md)를 참고하세요. 과거 개발 문서의 개인 PC 주소와 증거 링크는 새 환경에서 사용할 설정이 아닙니다. 이 저장소에는 로컬 증거 폴더를 포함하지 않습니다.

## 구조

- `app/`: 차량 UI, 운행 상태, SDK, 오디오, 위치 공유와 테스트
- `common/kotlin/`: 서버 API 클라이언트 사본
- `docs/`: 원본 설계·시연 기록과 분리 안내
- `scripts/`: 차량 SDK/오디오 검증 도구

실제 센서 관측, 모의 입력, 선생님의 수동 기록을 구분합니다. 앱의 기록 완료가 실제 차량 출발 허가나 자동 인계 판정을 뜻하지 않습니다. 벨트 해제 알림은 좌석별 지원 확인과 별도 개발이 필요한 기획 항목입니다.

[분리 기준](docs/SOURCE_SNAPSHOT.md) / [이번 검증 결과](docs/SPLIT_VALIDATION.md) / [보호자 앱](https://github.com/Hyundai-Mobility-Challenge/tonghak-parent)
