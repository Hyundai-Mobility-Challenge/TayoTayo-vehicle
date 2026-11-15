# 타요타요 — 어린이 통학 차량 Pleos 앱

**아이가 차에 타고 내리는 순간을 동승 선생님이 차량 화면에서 확인하고 기록하는 Pleos Connect 앱입니다.**
Pleos 차량의 기어·차속 신호로 주행 중에는 내비만 보여 주고, P로 정차하면 탑승·인계·하차 기록 화면으로 돌아옵니다. 기록은 기관 서버를 거쳐 보호자 앱에 바로 나타납니다.

<img src="docs/images/03-navi-guidance.jpg" alt="운행 시작 후 Pleos 내비가 첫 정류장 판교역으로 경유지 3곳을 안내하는 화면" width="100%">

> 운행을 시작하면 Pleos 내비가 정류장 3곳을 경유지로 안내합니다. 2026-11-15 Pleos Connect 에뮬레이터에서 로컬 서버와 가상 아동 4명으로 진행한 하원 운행 화면입니다.

**[⬇️ 차량 앱 v1.0.1 APK 내려받기](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-vehicle/releases/download/v1.0.1/TayoTayo-vehicle-1.0.1-release.apk)** (43.5MB · Pleos Connect, API 28 이상) · [Releases](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-vehicle/releases)

> [!NOTE]
> 차량과 보호자 앱의 설치 및 배포 서버 연결 순서는 [시작하기](#시작하기)를 따라 주세요.

이 저장소는 타요타요의 **대표 저장소**입니다. 차량 앱 소스를 관리하고, 서버와 보호자 앱은 서브모듈로 연결합니다.

| 구성 | 저장소 | 이 저장소 안의 위치 |
| --- | --- | --- |
| **Pleos 차량 앱** | [TayoTayo-vehicle](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-vehicle) | [`app/`](app) |
| 기관 서버 | [TayoTayo-server](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-server) | [`server/`](server) 서브모듈 |
| 보호자 앱 | [TayoTayo-parent](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-parent) | [`parent/`](parent) 서브모듈 |

## 왜 만들었나

어린이 통학 차량에서 가장 중요한 일은 **누가 탔고, 누구에게 넘겨졌는지**를 빠짐없이 확인하는 것입니다. 그런데 이 확인은 지금도 종이 명단과 전화·메신저에 기대는 경우가 많고, 달리는 차 안에서 휴대폰을 꺼내 확인하기도 어렵습니다.

타요타요는 센서가 대신 판단하는 서비스가 아닙니다. **사람이 확인해야 할 순간을 차량 화면이 놓치지 않게 짚어 주고, 그 확인을 기록으로 남깁니다.** 차량 신호는 화면 전환과 안내에만 쓰고, 아이의 상태는 선생님이 직접 누른 기록으로만 바뀝니다.

## 하원 운행 한 바퀴

| 1. 배정 명단 불러오기 | 2. 출발 전 확인 |
| :---: | :---: |
| <img src="docs/images/01-roster.jpg" alt="서버에서 불러온 탑승 명단 4명과 아이별 탑승 기록 버튼"> | <img src="docs/images/02-departure-check.jpg" alt="기관이 정한 방문 순서, 정류장별 도착 예정 시각, 탑승 인원과 기사 확인"> |
| 기관이 배정한 오늘의 명단을 불러와 아이마다 **탑승 기록**을 남깁니다. | 방문 순서와 탑승 인원을 기사와 함께 확인하고, 음성으로도 요약을 듣습니다. |
| **3. 주행 중 화면 잠금** | **4. 정차 후 대면 인계** |
| <img src="docs/images/04-driving-locked.jpg" alt="기어 D로 36km/h 주행 중 앱 화면이 잠기고 '주행 중에는 사용할 수 없습니다'가 표시됨"> | <img src="docs/images/05-handover.jpg" alt="판교역 정차 후 김민수를 보호자에게 직접 인계했는지 묻는 화면과 보호자 미도착·동승 유지 버튼"> |
| 기어 D에서는 Pleos가 앱 입력을 막고 길 안내만 남깁니다. | P로 정차하면 기록 화면으로 돌아옵니다. 보호자가 없으면 **동승 유지**를 선택합니다. |
| **5. 복귀 후 실내 확인** | **6. 운행 종료** |
| <img src="docs/images/06-cabin-check.jpg" alt="모든 아이 하차 후 차량 실내를 직접 확인하라는 화면"> | <img src="docs/images/07-trip-done.jpg" alt="정류장 3곳 처리와 기관 복귀를 마친 하원 완료 화면"> |
| 차 안에 남은 기록이 0명이어도 선생님이 실내를 직접 확인한 뒤 종료합니다. | 탑승·인계·하차·실내 확인 기록이 서버에 저장되고 위치 공유가 끝납니다. |

같은 운행을 보호자는 [보호자 앱](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-parent)에서 **운행 중 → 정류장 도착 → 인계·하차 완료** 순서로 확인합니다.

```mermaid
flowchart TD
    A["배정 명단 불러오기 · 아이별 탑승 기록"] --> B["출발 전 확인<br>인원 · 첫 정류장 · 기사 확인"]
    B --> C["기어 D: 내비 경유지 안내, 기록 화면 잠금"]
    C --> D["기어 P: 도착·정차 확인 → 보호자 도착 알림"]
    D --> E{"지정된 보호자에게<br>인계할 수 있나요?"}
    E -- 예 --> F["인계 확인 → 하차 기록<br>해당 아이 위치 공유 종료"]
    E -- 아니요 --> G["동승 유지<br>다음 구간 명단에 남김"]
    F --> H{"남은 정류장이 있나요?"}
    G --> H
    H -- 예 --> C
    H -- 아니요 --> I["기관 복귀 → 실내 직접 확인 → 운행 종료"]
```

## Pleos SDK를 어디에 쓰나

| SDK | 쓰는 정보 | 앱에서 하는 일 |
| --- | --- | --- |
| Vehicle 2.3.2 | 기어, 차속, 주행 중 화면 제한 | D에서는 기록 화면을 잠그고 내비를 앞에 띄웁니다. P 정차가 확인되면 도착·인계 기록 화면으로 돌아옵니다. |
| Vehicle 2.3.2 | 안전띠 경고, 도어 잠금 | 출발 전과 재출발 때 안전띠 재확인을 요청합니다. 아이별 착용 여부는 선생님이 확인합니다. |
| NaviHelper 3.0.5 | 경유지 경로 요청, 남은 거리·시간 | 정류장을 순서대로 경유지로 등록하고, 한 곳을 마치면 남은 정류장으로 경로를 다시 요청합니다. 남은 시간은 서버로 보내 보호자 앱의 예상 도착 시간이 됩니다. |
| TextToSpeech 3.3.1 | 한국어 음성 안내 | 출발 전 요약, 다음 정류장과 내릴 아이, 도착 후 인계 안내, 복귀 후 실내 확인을 읽어 줍니다. |

## 설계에서 지킨 원칙

**차량 신호는 화면만 바꾸고, 기록은 사람이 남깁니다.** 내비 도착 이벤트나 남은 거리 0m, 정류장 근처 위치만으로 도착·하차를 처리하지 않습니다. 운행 기록은 차량 연결, 최신 차속 0, 기어 P가 모두 확인될 때만 받습니다. 하나라도 빠지면 이유를 화면에 보여 주고 기록을 막습니다(`ShuttleEngine.stationaryBlockers`).

**통신이 끊겨도 기록을 잃지 않습니다.** 모든 기록은 기기에 먼저 저장한 뒤 서버로 보냅니다. 이벤트마다 운행 번호와 기록 키로 만든 고정 식별자(`clientEventId`)를 붙여서, 다시 연결돼 재전송해도 서버에는 한 건만 남습니다. 미전송 이벤트가 여러 건이면 묶음(`/events/batch`)으로 보냅니다(`InstitutionConnection`).

**보호자를 만나지 못한 아이는 하차 처리하지 않습니다.** 동승 유지를 선택하면 아이는 다음 구간 명단에 남고, 기관에 복귀해 담당자에게 인계할 때까지 추적됩니다.

## 시작하기

### 1. 앱 설치

| 준비물 | 설치·접속 방법 |
| --- | --- |
| 차량 앱 | Pleos Connect 에뮬레이터에 [차량 APK v1.0.1](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-vehicle/releases/download/v1.0.1/TayoTayo-vehicle-1.0.1-release.apk)을 설치합니다. Virtual Console에서 기어와 차속을 조작합니다. |
| 보호자 앱 | Android 9 이상 휴대폰 또는 에뮬레이터에 [보호자 APK 0.3-local-demo](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-parent/releases/download/v0.3-local-demo/TayoTayo-parent-0.3-local-demo.apk)를 설치합니다. 첫 설치 시 시연 서버 주소가 기본값입니다. |
| 기관 관리자·보호자 계정 | 두 계정의 비밀번호는 심사 안내와 함께 별도로 전달합니다. [서버 상태 확인](https://api-demo-e677ea0e.up.railway.app/health/database) |

### 2. 배포 서버에 연결하고 오늘 운행 만들기

차량 앱과 보호자 앱에 입력할 주소는 `https://api-demo-e677ea0e.up.railway.app`입니다. 차량 앱 v1.0.1과 보호자 앱 0.3-local-demo는 처음 설치하면 이 주소가 기본값입니다. 주소 끝에 `/api/v1`을 붙이지 마세요. 서버와 MySQL은 이미 실행 중입니다.

1. **PC에서 차량 연결 코드 발급.** [시연 서버 Swagger](https://api-demo-e677ea0e.up.railway.app/swagger-ui.html)에서 `POST /auth/login`을 열고 **Try it out**을 누릅니다. `email=orgs@example.com`, 전달받은 관리자 비밀번호, `clientType=ADMIN_WEB`을 입력하고 **Execute**를 누릅니다. 응답의 `accessToken` 값만 상단 **Authorize → BearerAuth**에 등록합니다. `POST /orgs/{orgId}/vehicles/{vehicleId}/pairing-codes`에서 두 ID에 각각 `1`, 본문에 `{"expiresIn":600}`을 넣으면 10분간 유효한 6자리 코드가 나옵니다.
2. **차량 연결.** 차량 앱 **기관 · 차량 연결** 화면에 서버 주소와 6자리 코드를 입력하고 **차량 연결**을 누릅니다. 연결 완료를 확인합니다.
3. **오늘 등원 운행 생성.** PC에서 [오늘 운행 만들기](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-server/blob/main/README.md#차량-연결-후-오늘-운행-만들기)의 PowerShell 명령을 실행합니다. 출력된 JSON의 날짜가 한국 기준 오늘이고 방향이 `PICK_UP`인지 확인합니다. 이 JSON으로 Swagger의 `POST /orgs/{orgId}/trips` 본문 전체를 교체하고 `orgId=1`로 한 번 실행합니다. **차량 페어링이 끝난 뒤에** 생성해야 그 기기에 운행이 배정됩니다. 응답에서 운행 번호(`id`), `PREPARING`, 정류장 3곳과 원아 3명을 확인합니다.

### 3. 등원 운행과 보호자 확인

1. **두 앱에서 명단 확인.** 차량 앱에서 **등원 → 배정 명단으로 운행 준비**를 누릅니다. 선택 목록이 뜨면 방금 만든 운행 번호의 **이 운행 준비**를 누릅니다. 보호자 앱에서는 기본 서버 주소로 `pickup-judge@example.com`과 보호자 비밀번호로 로그인합니다. 김하늘이 이미 연결돼 있어 별도의 어린이 초대 코드는 필요하지 않습니다. 이전 서버 주소가 저장된 경우 로그인 화면의 **서버 주소 설정 → 적용**에서 위 HTTPS 주소로 바꾸세요.
2. **승차·기관 인계.** 차량에서 명단과 순서를 확인하고 출발합니다. **판교역 김하늘 → 야탑역 김바다 → 성남시청 이마음** 순서로 정차합니다. 각 정류장에서 기어 P·차속 0을 확인하고, 아이를 넘겨준 사람 유형을 고른 뒤 **인계받음 → 탑승 → 벨트 확인**을 누릅니다. 마지막에는 기관에 도착해 각 아이의 **기관 인계 확인 · 하차 기록**을 누르고, 실내 확인 후 운행을 종료합니다.
3. **결과 확인.** 보호자 앱을 새로고침해 김하늘의 탑승·하차 기록을 확인합니다. 시연 서버는 휴대폰 푸시를 보내지 않습니다. 관리자 Swagger의 `GET /trips/{tripId}`에서 운행 번호로 조회하면 `COMPLETED`와 세 아이의 `DROPPED_OFF` 기록을 확인할 수 있습니다.

[서버 README의 Swagger 요청 예시](https://github.com/Hyundai-Mobility-Challenge/TayoTayo-server/blob/main/README.md#시작하기)

<details>
<summary>개발용 차량 앱을 소스에서 빌드하는 방법</summary>

```sh
git clone --recurse-submodules https://github.com/Hyundai-Mobility-Challenge/TayoTayo-vehicle.git
```

이미 받았다면 `git submodule update --init --recursive`를 실행합니다. GitHub의 **Download ZIP**에는 서브모듈 소스가 들어 있지 않습니다. 서버·보호자 앱의 소스 수정은 각 저장소에서 관리합니다.

Android Studio에서 저장소 루트를 엽니다. Android SDK 35, JDK 17 이상, Pleos Maven 저장소 접근이 필요하고 최소 API는 28입니다. 실행 대상은 Pleos Connect 에뮬레이터입니다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest      # Windows: .\gradlew.bat
```

| 항목 | 값 |
| --- | --- |
| 앱 모듈 / 앱 ID | `app` / `com.hanhyo.pleosshuttle` |
| 디버그 APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Firebase 설정 | 차량 앱에는 필요 없음 (알림은 서버가 보냄) |

</details>

## 구조

```text
app/src/main/java/com/hanhyo/pleosshuttle/
├─ domain/   운행 상태 엔진(ShuttleEngine), 승하차·인계 규칙, 출발 전 확인, 경로 순서
├─ sdk/      Pleos Vehicle·NaviHelper 어댑터
├─ audio/    TextToSpeech 안내와 발화 순서 제어
├─ ui/       Compose 화면 (명단, 운행, 인계, 기관 연결)
└─ *.kt      서버 연동(InstitutionConnection), 주행 중 화면 전환, 위치·ETA 전송
```

| 경로 | 내용 |
| --- | --- |
| `common/kotlin/` | 서버 API 클라이언트 사본 |
| `docs/` | 설계·연동·시연 문서 |
| `scripts/` | Pleos SDK·오디오 검증 도구 |

## 검증과 한계

- `app/src/test`에는 운행 엔진, 주행 중 화면 정책, ETA 전송, 서버 연동 이벤트 등 단위 테스트가, `app/src/androidTest`에는 Pleos 에뮬레이터에서 도는 운행 흐름 기기 테스트가 있습니다.
- 위 화면은 Pleos 에뮬레이터와 Virtual Console로 기어·속도를 바꿔 가며 찍었습니다. 에뮬레이터의 GPS 위치는 내비 지도 위에서 움직이지 않으므로, 실제 도로 주행에서 남은 거리가 줄어드는 동작은 실차 확인이 필요합니다.
- 안전띠는 차량 전체 경고를 재확인 요청에 쓰며, 좌석별 착용 판정은 하지 않습니다. 앱의 기록 완료가 차량 출발 허가나 자동 인계 판정을 뜻하지 않습니다.

[분리 기준](docs/SOURCE_SNAPSHOT.md) · [분리 검증 결과](docs/SPLIT_VALIDATION.md) · [실시간 공유 설계](docs/LIVE_TRACKING.md)
