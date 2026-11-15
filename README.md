# 타요타요 — 대표 저장소 · 차량 앱

이 저장소(`TayoTayo-vehicle`)는 타요타요 프로젝트의 대표 저장소입니다. 차량 앱 소스를 관리하며, 서버와 보호자 앱 소스는 각각 `server/`와 `parent/` Git submodule로 연결합니다.

| 역할 | 저장소 | 소스 위치 |
| --- | --- | --- |
| 대표 저장소 · 차량 앱 | [TayoTayo-vehicle](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-vehicle) | 이 저장소의 `app/` |
| 연동 서버 소스 | [TayoTayo-server](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-server) | [`server/`](server) |
| 보호자 앱 | [TayoTayo-parent](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-parent) | [`parent/`](parent) |

## 전체 소스 내려받기

```sh
git clone --recurse-submodules https://github.com/Hyundai-Mobility-Challenge/TayoTayo-vehicle.git
cd TayoTayo-vehicle
```

이미 차량용 저장소를 내려받았다면 저장소 루트에서 `git submodule update --init --recursive`를 실행합니다. 서버와 보호자 앱은 각각 대표 저장소에 기록된 커밋으로 내려받습니다. 소스 수정은 `TayoTayo-server`와 `TayoTayo-parent`에서 관리합니다. GitHub의 **Download ZIP**에는 두 submodule의 소스가 포함되지 않으므로 전체 소스는 위 명령으로 내려받으세요.

서버 실행은 [서버 README](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-server/blob/main/README.md), 앱과 서버 연결은 [전체 연결 순서](docs/INTEGRATION.md)를 참고하세요.

## 차량 앱 소개

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

1. [서버 README](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-server/blob/main/README.md)의 안내에 따라 서버를 실행하고 기관·차량·어린이를 등록합니다.
2. 차량의 **기관 · 배정 명단**에서 에뮬레이터는 `http://10.0.2.2:8080`, 실제 차량 기기는 `http://<PC의 LAN IPv4>:8080`과 기관에서 발급한 차량 페어링 코드를 입력합니다. 앱은 `/api/v1/vehicle/pair`를 호출해 차량 자격 증명을 저장합니다.
3. 기관 웹/API에서 차량·경로·어린이 배정 후 오늘 운행을 미리 생성해야 합니다. 차량 앱에서 등원(`PICK_UP`) 또는 하원(`DROP_OFF`) 방향을 선택하고 해당 운행을 불러옵니다. 앱에서 새 운행이나 명단을 생성하지 않습니다.
4. **배정 명단으로 운행 준비 → 탑승 기록 → 이 순서로 · 운행 시작**을 진행합니다. 진행 기록과 정류장 상태는 서버 API로 전송됩니다.
5. 정차 후 **도착·정차 확인 → 인계 확인 → 하차 기록**을 진행합니다.
6. 복귀 후 실제 차내 확인 절차를 수행하고 종료합니다.

### 코드 발급 후 학부모 앱 연결·시연

1. 서버에서 발급한 **차량 페어링 코드**는 위 2단계처럼 차량 앱에 입력합니다. 학부모 앱 연결에는 별도의 **어린이 보호자 초대 코드**가 필요합니다. 기관 관리자 계정으로 서버의 `POST /api/v1/orgs/{orgId}/children/{childId}/guardian-invitations`를 호출해 보호자 이메일로 초대 코드를 발급합니다. 두 코드는 용도가 다릅니다.
2. 학부모 휴대폰을 서버 PC와 같은 네트워크에 연결하고 학부모 앱의 로그인 화면에서 **서버 주소 설정**을 열어 `http://<서버 PC의 LAN IPv4>:8080`을 입력합니다. 에뮬레이터라면 `http://10.0.2.2:8080`을 사용합니다. 주소에 `/api/v1`은 붙이지 않습니다. 서버 PC가 바뀌면 같은 APK에서 주소만 다시 입력합니다. 로컬 시연용 학부모 앱 release APK도 사설망 HTTP 주소를 허용합니다.
3. 초대 코드 발급에 사용한 이메일로 학부모 앱에서 가입·로그인한 뒤 어린이 초대 코드를 수락합니다. 아이가 홈 화면에 나타나는지 확인합니다. 차량 페어링과 보호자 초대 수락을 마친 뒤 기관 웹/API에서 오늘 날짜의 운행을 생성하고 차량 앱에서 불러옵니다.
4. 차량 앱에서 **운행 준비 → 탑승 기록 → 운행 시작**을 진행하고 학부모 앱에서 오늘 운행 상태가 바뀌는지 확인합니다. 차량 앱이 서버로 위치·예상 시간을 보낸 뒤 학부모 앱을 전면에 두면 약 5초마다 최신 정보를 조회합니다. 이어 차량 앱에서 **도착·정차 확인 → 인계 확인 → 하차 기록**을 진행하며 학부모 앱의 도착·인계·하차 표시를 확인합니다. 실제 푸시 알림을 시연하려면 학부모 앱과 서버에 같은 Firebase 프로젝트를 설정하고 휴대폰 알림 권한을 허용해야 합니다.

[전체 연결 순서](docs/INTEGRATION.md), [현재 시연 순서](docs/DEMO_STEP_BY_STEP.md)를 참고하세요. 과거 개발 문서의 개인 PC 주소와 증거 링크는 새 환경에서 사용할 설정이 아닙니다. 이 저장소에는 로컬 증거 폴더를 포함하지 않습니다.

## 구조

- `app/`: 차량 UI, 운행 상태, SDK, 오디오, 위치 공유와 테스트
- `server/`: `TayoTayo-server` 저장소를 참조하는 서버 소스 submodule
- `parent/`: `TayoTayo-parent` 저장소를 참조하는 보호자 앱 소스 submodule
- `common/kotlin/`: 서버 API 클라이언트 사본
- `docs/`: 원본 설계·시연 기록과 분리 안내
- `scripts/`: 차량 SDK/오디오 검증 도구

실제 센서 관측, 모의 입력, 선생님의 수동 기록을 구분합니다. 앱의 기록 완료가 실제 차량 출발 허가나 자동 인계 판정을 뜻하지 않습니다. 벨트 해제 알림은 좌석별 지원 확인과 별도 개발이 필요한 기획 항목입니다.

[분리 기준](docs/SOURCE_SNAPSHOT.md) / [이번 검증 결과](docs/SPLIT_VALIDATION.md) / [보호자 앱](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-parent)
