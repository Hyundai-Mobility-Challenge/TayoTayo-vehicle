package com.hanhyo.pleosshuttle.domain

import java.io.Serializable

enum class TripPhase(val label: String) { PREPARING("운행 준비"), DRIVING("운행"), ALIGHTING("하차"), END_REVIEW("종료 확인"), ENDED("운행 종료") }
/**
 * 운행의 차량 연동 방식. 출시 앱은 [SDK](Pleos 차량 연결)만 쓴다.
 * [SIMULATION]은 이전 빌드가 저장한 trip-v1.bin(Java 직렬화는 enum을 이름으로 저장)을 읽을 때 역직렬화가 실패하지 않도록
 * 상수만 남긴 것이다. 어떤 화면·흐름에서도 쓰지 않으며, 이 값으로 저장된 운행은 복원하지 않고 버린다([StoredTrips.restorable]).
 * 상수 이름을 바꾸거나 지우지 말 것.
 */
enum class IntegrationMode(val label: String) {
    /** 역직렬화 호환 전용. 새 운행에 쓰지 않는다. */
    SIMULATION("이전 빌드 저장 호환용"),
    SDK("Pleos 차량 연결"),
}
enum class Source(val label: String) { SDK("실제 SDK"), EMULATOR_INPUT("에뮬레이터 입력"), SIMULATED("모의 데이터"), MANUAL("수동 기록") }
enum class ResultStatus(val label: String) {
    UNVERIFIED("미검증"), PERMISSION_REQUIRED("권한 필요"), UNSUPPORTED_HERE("현재 환경 미지원"),
    ACCEPTED("요청 수락"), TARGET_OBSERVED("목표 상태 관측"), PARTIAL("부분 반영"), ERROR("오류"), TIMEOUT("시간 초과")
}
/**
 * 출결 상태. Java 직렬화는 enum을 이름으로 저장하므로 상수 추가는 기존 저장 파일과 호환된다(이름 변경·삭제 금지).
 * NOT_BOARDED는 "아직 탑승 기록 없음(미결정)", CONFIRMED_NOT_BOARDED는 선생님이 직접 확정한 미탑승(V-02, 7절 사람이 확인).
 */
enum class Attendance(val label: String) {
    /** NOT_BOARDED = 아직 결정 전(탑승 기록·미탑승 확인 모두 없음). [미탑승 확인] 버튼과 헷갈리지 않게 "탑승 전"으로 표시. 상수 이름은 직렬화되므로 바꾸지 않는다. */
    NOT_BOARDED("탑승 전"), ONBOARD("탑승 기록"), ALIGHTED("하차 기록"),
    CONFIRMED_NOT_BOARDED("미탑승 확인"),
}
enum class Gear(val label: String) { PARK("P"), REVERSE("R"), NEUTRAL("N"), DRIVE("D"), UNKNOWN("미확인") }
enum class SurroundingKind(val label: String) { WARNING("위험 정보 있음"), UNKNOWN("정보 미확인"), DIRECT_CHECK_REQUIRED("직접 확인 필요") }
enum class ResumePolicy { REQUIRE_REASON_AND_DRIVER_CONFIRM, REQUIRE_ALL_RESOLVED, WARN_AND_CONFIRM }
enum class RouteObjective(val label: String) { DISTANCE("거리 우선"), TIME("시간 우선"), FIXED("고정 순서") }

data class Observation<T>(
    val value: T? = null,
    val source: Source = Source.SDK,
    val observedAtMillis: Long? = null,
    val status: ResultStatus = ResultStatus.UNVERIFIED,
    val errorCode: String? = null,
    val generation: Long = 0,
) : Serializable {
    fun isFresh(now: Long, maxAgeMillis: Long = 5_000): Boolean =
        value != null && observedAtMillis != null && now >= observedAtMillis &&
            now - observedAtMillis <= maxAgeMillis && status == ResultStatus.TARGET_OBSERVED

    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = -3782103051953243101L
    }
}

/**
 * 좌석 Area별 관측. 지금은 좌석별 벨트 체결 진단에만 쓴다.
 * 이전 저장 운행(trip-v1.bin)에 이 클래스가 들어 있어 지우면 복원 시 ClassNotFoundException이 나므로 유지한다.
 */
data class SeatObservation(
    val areaId: Int,
    /** deprecated: V-07 삭제(D39), 저장 호환용으로만 유지. 새로 채우지 않으며 어떤 판단·문구에도 쓰지 않는다. */
    val occupancy: Observation<Boolean> = Observation(),
    val beltFastened: Observation<Boolean> = Observation(),
    val areaLabel: String = areaId.toString(),
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = 4640871374603120409L
    }
}

data class VehicleSnapshot(
    val connected: Boolean = false,
    val speedKph: Observation<Double> = Observation(),
    val gear: Observation<Gear> = Observation(),
    val parkingBrake: Observation<Boolean> = Observation(),
    val doorLocked: Observation<Boolean> = Observation(),
    val seats: List<SeatObservation> = emptyList(),
    val connectionGeneration: Long = 0,
    val parkingBrakeSupported: Boolean = false,
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = 7408924729198644115L
    }
}

data class Surroundings(
    val kind: SurroundingKind = SurroundingKind.UNKNOWN,
    val source: Source = Source.SDK,
    val observedAtMillis: Long? = null,
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = -3292896814217164568L
    }
}

data class ChildRecord(
    val id: String,
    val name: String,
    val stopId: String,
    /** 좌석 배정 삭제(D39)로 화면에 표시하지 않음. 저장된 기록 복원 호환을 위해 필드만 유지. */
    val virtualSeat: String,
    val status: Attendance = Attendance.NOT_BOARDED,
    /**
     * 서버 boardingExpected=false(결석·직접 픽업 등 오늘 미탑승 예정)일 때 true.
     * 반대 의미로 저장하는 이유: 이전 버전에서 저장된 기록을 Java 직렬화로 복원하면 새 필드는
     * JVM 기본값(false)이 되므로, 기본값이 곧 "탑승 예정"(D20: 미제출 기본값 탑승)이 되도록 함.
     */
    val notExpectedToBoard: Boolean = false,
    /** 서버가 내려주는 미탑승 예정 사유(ABSENT / SELF_PICKUP / NOT_RIDING / OTHER). 없으면 null. */
    val notBoardingReason: String? = null,
    /**
     * V-05 벨트 착용 수동 확인(선생님이 직접 확인). 탑승(ONBOARD) 중인 아이에게만 의미가 있다.
     * 이전 버전 저장 파일을 복원하면 JVM 기본값 false = "미확인"이 되므로 안전한 쪽으로 복원된다.
     * 운행 중 화면 상태용이며 서버로 보내지 않는다(D27 좌석·벨트 기록 보관 제외).
     */
    val beltConfirmed: Boolean = false,
    /**
     * V-21 보호자 미도착 · 동승 유지 사유 코드([StayOnboardReasons]). null = 동승 유지 아님.
     * 값이 있으면 이 운행 동안 내리지 않고 계속 타고 가는 아이다(정류장 하차 대상·하차 안내에서 빠지고, 복귀 후 기관 인계).
     * 이전 저장 파일을 복원하면 JVM 기본값 null = 동승 유지 아님(UID 고정, 필드 추가는 직렬화 호환).
     */
    val stayOnboardReason: String? = null,
    /**
     * V-27 등원 정류장 인계받음: 아이를 넘겨준 사람 유형 코드([PickupReceivers]). null = 아직 인계받지 않음.
     * 이름은 받지도 저장하지도 않는다(7절). 이전 저장 파일은 JVM 기본값 null(UID 고정, 필드 추가는 직렬화 호환).
     */
    val receivedFromType: String? = null,
    /** V-28 [보호자에게 도착 알림]을 보낸 시각. null = 호출 없음. 재호출·대기 한도는 없다(D30). 이전 저장 파일은 null. */
    val guardianCalledAtMillis: Long? = null,
) : Serializable {
    /** V-27: 등원 정류장에서 인계받음을 기록했는지. 등원 탑승은 이 값이 true일 때만 기록한다. */
    val receivedAtStop: Boolean get() = receivedFromType != null
    /** V-28: 보호자 호출(도착 알림)을 보냈는지. */
    val guardianCalled: Boolean get() = guardianCalledAtMillis != null
    /** V-21: 보호자 미도착으로 동승 유지 중(또는 동승 유지 뒤 기관 인계로 하차한) 아이. */
    val staysOnboard: Boolean get() = stayOnboardReason != null
    /** 정류장에서 인계·하차 기록을 기다리는 아이: 탑승 중이고 동승 유지가 아님. 정류장 완료·하차 안내 기준. */
    val awaitingAlighting: Boolean get() = status == Attendance.ONBOARD && !staysOnboard
    /** 오늘 탑승 예정 여부. 기본값 탑승(D20). */
    val expectedToBoard: Boolean get() = !notExpectedToBoard
    /** 탑승 기록도 미탑승 확인도 없는 상태. 탑승 단계에서 선생님이 결정해야 하는 아이. */
    val awaitingBoardingDecision: Boolean get() = status == Attendance.NOT_BOARDED
    /**
     * 오늘 이 차로 가는 아이인지(정류장 하차 인원 계산용). 탑승·하차 기록이 있으면 탄 것, 미탑승 확인이면 안 탄 것,
     * 아직 결정 전이면 탑승 예정(expectedToBoard)을 따른다. 계산 프로퍼티라 직렬화 형식은 바뀌지 않는다(UID 고정).
     */
    val ridesToday: Boolean get() = when (status) {
        Attendance.ONBOARD, Attendance.ALIGHTED -> true
        Attendance.CONFIRMED_NOT_BOARDED -> false
        Attendance.NOT_BOARDED -> expectedToBoard
    }

    companion object {
        /** 필드 추가 전 클래스의 기본 계산값을 고정해, 이미 저장된 운행 기록을 계속 복원할 수 있게 함. */
        private const val serialVersionUID: Long = 91676870627885723L
    }
}

/** 서버 미탑승 예정 사유 코드(attendance_requests.request_type) → 화면 문구. 코드가 없으면 null(사유 문구 없이 배지만). */
fun notBoardingReasonLabel(code: String?): String? = when (code?.trim()?.uppercase()) {
    null, "" -> null
    "ABSENT" -> "결석"
    "SELF_PICKUP" -> "직접 픽업"
    "NOT_RIDING" -> "미탑승"
    else -> "기타"
}

/**
 * V-21 동승 유지 사유(고정 선택지). 자유 입력을 받지 않아 보호자·대리인 개인정보가 기록에 들어가지 않는다(7절).
 * 코드는 기기 원장에만 남기며, 서버 GUARDIAN_NO_SHOW 이벤트는 사유 필드를 저장하지 않는다(TripController.updateChildFromEvent).
 */
object StayOnboardReasons {
    const val GUARDIAN_ABSENT = "GUARDIAN_ABSENT"
    const val NO_CONTACT = "NO_CONTACT"
    const val OTHER = "OTHER"
    val ALL = listOf(GUARDIAN_ABSENT, NO_CONTACT, OTHER)
    fun label(code: String?): String = when (code) {
        GUARDIAN_ABSENT -> "보호자 미도착"
        NO_CONTACT -> "연락 안 됨"
        else -> "기타"
    }
}

/**
 * V-25 운행 방향. 서버 trips.direction 값(TripController.direction: PICK_UP / DROP_OFF)을 그대로 쓴다.
 * [TripState.direction]이 null(이전 저장 파일)이면 하원(DROP_OFF)으로 읽는다.
 */
object TripDirections {
    const val PICK_UP = "PICK_UP"
    const val DROP_OFF = "DROP_OFF"
    /** 알 수 없는 값·null은 하원(기존 동작 유지). */
    fun of(raw: String?): String = if (raw?.trim()?.uppercase() == PICK_UP) PICK_UP else DROP_OFF
    fun label(raw: String?): String = if (of(raw) == PICK_UP) "등원" else "하원"
    /** D25·OQ-26 제안안: 방향 기본값은 시간대. 정오 전이면 등원, 그 뒤면 하원. 선생님이 바꿀 수 있는 기본값일 뿐이다. */
    fun defaultFor(hourOfDay: Int): String = if (hourOfDay in 0 until 12) PICK_UP else DROP_OFF
}

/**
 * V-27 등원 정류장에서 아이를 넘겨준 사람 유형(고정 선택지). 이름은 받지 않는다(7절 개인정보 최소화).
 * 대리(관계)의 관계 선택지는 flow.md 확정 전이라 유형 코드만 둔다.
 */
object PickupReceivers {
    const val GUARDIAN = "GUARDIAN"
    const val PROXY = "PROXY"
    const val OTHER = "OTHER"
    val ALL = listOf(GUARDIAN, PROXY, OTHER)
    fun label(code: String?): String = when (code) {
        GUARDIAN -> "보호자"
        PROXY -> "대리"
        else -> "기타"
    }
}

/**
 * 등원 원장 기록 종류([LedgerEntry.pickupAction]). 하원 기록은 모두 null이다.
 * - RECEIVED: 정류장 인계받음(before·after 둘 다 NOT_BOARDED, 출결 상태는 그대로).
 * - BOARDED: 인계받음 뒤 탑승(NOT_BOARDED/CONFIRMED_NOT_BOARDED → ONBOARD).
 * - GUARDIAN_CALLED: 보호자에게 도착 알림(before·after 둘 다 NOT_BOARDED).
 * - NOT_BOARDED_NOTIFY / NOT_BOARDED_SILENT: 정류장 미탑승 확인(보호자 미탑승 안내 선택 / 선택 안 함).
 * - INSTITUTION_HANDOVER: 기관 담당자 인계 확인 후 하차 기록(ONBOARD → ALIGHTED).
 */
object PickupActions {
    const val RECEIVED = "RECEIVED"
    const val BOARDED = "BOARDED"
    const val GUARDIAN_CALLED = "GUARDIAN_CALLED"
    const val NOT_BOARDED_NOTIFY = "NOT_BOARDED_NOTIFY"
    const val NOT_BOARDED_SILENT = "NOT_BOARDED_SILENT"
    const val INSTITUTION_HANDOVER = "INSTITUTION_HANDOVER"
}

/** V-02 "오늘 미탑승 예정" 배지 문구. 탑승 예정인 아이는 null(배지 없음). 명단에서 빼지 않고 표시만 한다. */
fun notBoardingBadge(child: ChildRecord): String? =
    if (child.expectedToBoard) null
    else notBoardingReasonLabel(child.notBoardingReason)?.let { "오늘 미탑승 예정 · $it" } ?: "오늘 미탑승 예정"

data class Stop(val id: String, val name: String, val latitude: Double, val longitude: Double) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = -4591785948551678804L
    }
}

data class LedgerEntry(
    val id: String,
    val childId: String,
    val before: Attendance,
    val after: Attendance,
    val recordedAtMillis: Long,
    val reason: String,
    val source: Source = Source.MANUAL,
    /**
     * null = 출결 기록(서버 전송 대상). 값이 있으면 V-05 벨트 수동 확인 기록(true 확인 / false 미확인)이고
     * before·after는 둘 다 ONBOARD다. 벨트 기록은 기기 안에만 남기고 서버 이벤트로 보내지 않는다(D27).
     * 이전 저장 파일의 항목은 JVM 기본값 null = 출결 기록으로 복원된다.
     */
    val belt: Boolean? = null,
    /**
     * V-08 기사 확인 기록(D15·D37). null = 아님. true = 선생님이 기사 구두 확인을 체크, false = 체크 해제·자동 해제·
     * "기사 확인 없음" 출발. childId는 빈 문자열, before·after는 둘 다 ONBOARD(의미 없음). 기기 안에만 남기고
     * 서버 출결 이벤트로 보내지 않는다(서버에는 체크리스트 driverConfirmed로만 전달). 이전 저장 파일은 JVM 기본값 null.
     */
    val driverCheck: Boolean? = null,
    /**
     * V-21 동승 유지 기록 표시. null = 아님(이전 저장 파일도 null).
     * - before·after 둘 다 ONBOARD: true = 보호자 미도착 · 동승 유지 기록, false = 같은 정류장에서 동승 유지 취소.
     * - after = ALIGHTED이고 true: 동승 유지 아이를 복귀 후 기관에 인계하고 하차 기록(V-22).
     * 출결 기록이므로 서버로 보낸다(InstitutionTrips.ledgerEventType).
     */
    val stayOnboard: Boolean? = null,
    /**
     * V-09·D17 정류장 제외 기록. null = 아님(이전 저장 파일도 null). true = 정류장 제외(선생님 확인), false = 다시 넣기
     * (선생님 [다시 넣기] 또는 다시 탈 아이가 생겨 자동 재포함). childId는 빈 문자열, before·after는 둘 다 ONBOARD(의미 없음).
     * 출결 이벤트가 아니므로 서버 이벤트로 보내지 않는다(서버에는 종료 때 stops/{id}/skip으로만, InstitutionConnection.sync).
     */
    val stopExcluded: Boolean? = null,
    /** [stopExcluded] 기록의 정류장 id. 그 밖의 기록은 null. */
    val stopId: String? = null,
    /** 등원 기록 종류([PickupActions]). null = 하원·공통 기록(이전 저장 파일도 null). */
    val pickupAction: String? = null,
    /** 등원 인계받음·탑승 기록의 넘겨준 사람 유형([PickupReceivers]). 이름은 넣지 않는다. 그 밖은 null. */
    val receivedFromType: String? = null,
) : Serializable {
    /** 등원(V-26~V-29) 기록이면 true. */
    val isPickupRecord: Boolean get() = pickupAction != null
    /** V-09 정류장 제외·다시 넣기 기록이면 true. 서버 출결 이벤트 전송에서 제외한다. */
    val isStopExclusionRecord: Boolean get() = stopExcluded != null
    /** V-05 벨트 기록이면 true. 서버 전송(InstitutionConnection)에서 제외한다. */
    val isBeltRecord: Boolean get() = belt != null
    /** V-08 기사 확인 기록이면 true. 서버 출결 이벤트 전송에서 제외한다. */
    val isDriverCheckRecord: Boolean get() = driverCheck != null
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = 4960029191150688459L
    }
}

data class DiagnosticEntry(
    val atMillis: Long,
    val feature: String,
    val status: ResultStatus,
    val message: String,
    val before: String? = null,
    val requested: String? = null,
    val observed: String? = null,
    val errorCode: String? = null,
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = 8831578179708251824L
    }
}

data class UnlockConfirmation(
    val id: String,
    val preparedAtMillis: Long,
    val expiresAtMillis: Long,
    val safetyRevision: Long,
    val connectionGeneration: Long,
    val mode: IntegrationMode,
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = -5490408417010799804L
    }
}

data class UnlockRequest(
    val id: String,
    val requestedAtMillis: Long,
    val mode: IntegrationMode,
    val stopId: String,
    val safetyRevision: Long,
    val connectionGeneration: Long,
    val beforeLocked: Boolean,
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = 7788491441232106111L
    }
}

data class UnlockAttempt(
    val request: UnlockRequest,
    val status: ResultStatus = ResultStatus.UNVERIFIED,
    val observedLocked: Boolean? = null,
    val completedAtMillis: Long? = null,
    val errorCode: String? = null,
    val invalidated: Boolean = false,
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = 6284569530357834259L
    }
}

data class EndRecord(
    val recordedAtMillis: Long,
    val manualInspectionAtMillis: Long,
    val remainingChildIds: List<String>,
    /** deprecated: V-07 삭제(D39), 저장 호환용으로만 유지. 새 기록은 항상 빈 목록. */
    val occupiedAreas: List<Int>,
    /** deprecated: V-07 삭제(D39), 저장 호환용으로만 유지. 새 기록은 항상 빈 목록. */
    val unknownOrStaleAreas: List<Int>,
    val vehicleConnected: Boolean,
    val sensorCoverageKnown: Boolean,
    val observedVehicle: VehicleSnapshot = VehicleSnapshot(),
    /** deprecated: V-07 삭제(D39), 저장 호환용으로만 유지. */
    val occupiedAreaLabels: List<String> = occupiedAreas.map { it.toString() },
    /** deprecated: V-07 삭제(D39), 저장 호환용으로만 유지. */
    val unknownOrStaleAreaLabels: List<String> = unknownOrStaleAreas.map { it.toString() },
    val statement: String = "선생님 직접 실내 확인과 운행 기록을 저장함. 센서 관측은 안전 보증이 아님.",
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = 8133472449072202633L
    }
}

data class RouteProposal(
    val stopOrder: List<String>,
    val cost: Double,
    val costUnit: String,
    val description: String,
    val objective: RouteObjective,
    val returnToOrigin: Boolean,
    val routeVersion: Long,
    val evaluatedOrders: Int,
    val synthetic: Boolean = true,
) : Serializable {
    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = -6836717557408514147L
    }
}

data class TripState(
    val tripId: String,
    val phase: TripPhase = TripPhase.PREPARING,
    val integrationMode: IntegrationMode = IntegrationMode.SDK,
    val children: List<ChildRecord>,
    val origin: Stop,
    val stops: List<Stop>,
    val routeOrder: List<String> = stops.map { it.id },
    val currentStopIndex: Int = 0,
    val resumePolicy: ResumePolicy? = ResumePolicy.REQUIRE_REASON_AND_DRIVER_CONFIRM,
    val routeObjective: RouteObjective? = RouteObjective.DISTANCE,
    val returnToOrigin: Boolean = true,
    val routeIsExample: Boolean = true,
    val routeProposal: RouteProposal? = null,
    val routeVersion: Long = 0,
    val ledger: List<LedgerEntry> = emptyList(),
    val handoverChildIds: Set<String> = emptySet(),
    val manualSurroundingsCheckAtMillis: Long? = null,
    val unlockConfirmation: UnlockConfirmation? = null,
    val unlockAttempt: UnlockAttempt? = null,
    val manualDoorReason: String? = null,
    val manualDoorAtMillis: Long? = null,
    val directCabinCheckAtMillis: Long? = null,
    val endRecord: EndRecord? = null,
    val diagnostics: List<DiagnosticEntry> = emptyList(),
    val revision: Long = 0,
    /**
     * V-08 기사 확인(D15): 선생님이 기사 구두 답을 듣고 체크한 시각. null = 체크 없음. 시각만 저장하고 기사 이름 등
     * 개인정보는 저장하지 않는다(Run.driver_checked_at). 이전 저장 파일은 JVM 기본값 null = 체크 없음으로 복원된다.
     */
    val driverCheckedAtMillis: Long? = null,
    /**
     * V-09·D17 선생님이 확인해 방문 순서에서 뺀 정류장 id(내릴 아이 없음). 뺀 정류장은 [routeOrder]에서도 빠진다.
     * 이전 저장 파일을 복원하면 JVM 기본값 null(= 뺀 정류장 없음)이 되므로 nullable로 두고 [excludedStops]로 읽는다.
     */
    val excludedStopIds: List<String>? = null,
    /**
     * V-25 운행 방향(Run.direction, [TripDirections]). null = 하원(이전 저장 파일은 JVM 기본값 null → DROP_OFF로 읽음).
     */
    val direction: String? = null,
) : Serializable {
    /** 기관에서 불러온 운행이 있는지. false면 "운행 없음" 자리 표시 상태([NoTrip])다. 계산 프로퍼티(UID 고정). */
    val hasTrip: Boolean get() = tripId != NoTrip.ID
    /** null 안전 방향. 이전 저장 파일·알 수 없는 값은 DROP_OFF. */
    val tripDirection: String get() = TripDirections.of(direction)
    /** 등원 운행인지. */
    val isPickup: Boolean get() = tripDirection == TripDirections.PICK_UP
    /** 뺀 정류장 id(null 안전). */
    val excludedStops: List<String> get() = excludedStopIds.orEmpty()
    val currentStop: Stop? get() = routeOrder.getOrNull(currentStopIndex)?.let { id -> stops.find { it.id == id } }
    val onboardChildren: List<ChildRecord> get() = children.filter { it.status == Attendance.ONBOARD }
    /** V-21: 지금 동승 유지로 타고 있는 아이(복귀 후 기관 인계 대상). */
    val stayOnboardChildren: List<ChildRecord> get() = children.filter { it.status == Attendance.ONBOARD && it.staysOnboard }
    val currentStopChildren: List<ChildRecord> get() = children.filter { it.stopId == currentStop?.id }
    val remainingStopCount: Int get() = (routeOrder.size - currentStopIndex).coerceAtLeast(0)
    /** 오늘 탈 아이(탑승 예정, D20 기본값 탑승) 수. 저장 필드가 아니라 명단에서 계산한다. */
    val expectedRiderCount: Int get() = children.count { it.expectedToBoard }
    /**
     * D34: 운행 준비 중 탈 아이가 0명(빈 명단 포함)이면 "오늘 운행 없음". 운행을 시작하지 않는다.
     * 이미 탑승 기록이 있는 운행(복원 등)은 기록을 잃지 않도록 기존 흐름을 유지한다.
     */
    val noTripToday: Boolean get() = phase == TripPhase.PREPARING && expectedRiderCount == 0 && onboardChildren.isEmpty()
    /** 탑승 단계에서 아직 탑승 기록도 미탑승 확인도 없는 아이. */
    val undecidedChildren: List<ChildRecord> get() = children.filter { it.awaitingBoardingDecision }
    val confirmedNotBoardedChildren: List<ChildRecord> get() = children.filter { it.status == Attendance.CONFIRMED_NOT_BOARDED }
    /**
     * V-05: 탑승 중인데 벨트를 아직 선생님이 확인하지 않은 아이 수. V-08 출발 전 확인·V-16 벨트 음성이 쓸 값.
     * 출발을 막는 데 쓰지 않는다(경고만, V-08). 계산 프로퍼티라 직렬화 형식은 그대로(UID 고정).
     */
    val beltUncheckedChildren: List<ChildRecord> get() = onboardChildren.filter { !it.beltConfirmed }
    val beltUncheckedCount: Int get() = beltUncheckedChildren.size
    /** V-08: 출발 뒤인데 기사 확인 체크가 없었던 운행("기사 확인 없음"). 계산 프로퍼티(UID 고정). */
    val departedWithoutDriverCheck: Boolean get() = phase != TripPhase.PREPARING && driverCheckedAtMillis == null

    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = -1235094883528306877L
    }
}

/**
 * 저장된 운행이 없을 때의 "운행 없음" 자리 표시 상태. 명단·정류장이 비어 있고 어떤 운행 조작·음성 자동 안내·위치 전송도 하지 않는다.
 * 화면은 기관 연결(페어링) → 오늘 운행 불러오기로 안내한다. 이 상태는 trip-v1.bin에 저장하지 않는다.
 */
object NoTrip {
    const val ID = "no-trip"
    fun state(): TripState = TripState(
        tripId = ID, integrationMode = IntegrationMode.SDK, children = emptyList(),
        origin = Stop("origin", "기관", 0.0, 0.0), stops = emptyList(), routeIsExample = false,
    )
}

/** 저장 파일(trip-v1.bin)에서 읽은 운행을 이어서 쓸지 정한다. */
object StoredTrips {
    /** 이전 빌드의 모의 운행(연동 방식 SIMULATION 또는 가상 명단 id "fictional-…")인지. */
    fun isLegacySimulation(trip: TripState): Boolean =
        trip.integrationMode == IntegrationMode.SIMULATION || trip.tripId.startsWith("fictional-")
    /**
     * 이어서 쓸 운행. 저장된 운행이 없거나, 자리 표시 상태이거나, 이전 빌드의 모의 운행이면 null(= 운행 없음으로 시작).
     * 예외: 이전 빌드에서 기관 운행을 연동 방식 전환(changeMode)으로 SIMULATION으로 바꿔 저장한 경우, 그 운행이 지금 기관 서버에
     * 묶인 운행([boundTripId])이고 서버로 보내지 않은 원장([unsentRecords] > 0)이 남아 있으면 버리지 않고 SDK로 바꿔 복원한다
     * (미전송 기록이 서버로 가도록). 원장·명단 등 다른 값은 그대로 둔다.
     */
    fun restorable(saved: TripState?, boundTripId: String? = null, unsentRecords: Int = 0): TripState? {
        if (saved == null || !saved.hasTrip) return null
        if (!isLegacySimulation(saved)) return serverOrder(saved)
        val boundWithUnsent = saved.integrationMode == IntegrationMode.SIMULATION && !boundTripId.isNullOrEmpty() &&
            saved.tripId == boundTripId && unsentRecords > 0
        return if (boundWithUnsent) serverOrder(saved.copy(integrationMode = IntegrationMode.SDK)) else null
    }

    /**
     * 기관 운행은 서버 정류장 순서(seq)를 그대로 쓴다. 저장본이 "예시 순서"(routeIsExample)로 표시돼 있으면 [운행 시작] 때
     * 앱이 순서를 자동 계산해 바꾸므로(ShuttleEngine.startOperationalTrip), 복원할 때 그 표시와 미리보기를 지운다.
     * 차량에서 바꾼 순서는 서버로 전달되지 않기 때문이다(서버 route-revisions 차량 허용 후 재연결 예정).
     */
    fun serverOrder(trip: TripState): TripState {
        val cleared = if (trip.routeIsExample || trip.routeProposal != null) trip.copy(routeIsExample = false, routeProposal = null) else trip
        // 이전 빌드에서 순서를 바꿔 둔 운행 준비 저장본은 서버 정류장 순서(stops = seq 순)로 되돌린다. 뺀 정류장은 빼고 정규화한다.
        // 출발 뒤에는 진행 위치(currentStopIndex)가 순서에 묶여 있어 바꾸지 않는다.
        if (cleared.phase != TripPhase.PREPARING) return cleared
        val server = cleared.stops.map { it.id }.filter { it !in cleared.excludedStops }
        val reordered = if (server == cleared.routeOrder) cleared else cleared.copy(routeOrder = server, routeVersion = cleared.routeVersion + 1)
        // 출발 전 순서(또는 예시 표시)가 바뀌었으면 기사 확인을 다시 받는다(ShuttleEngine.clearDriverCheckIfChanged와 같은 규칙).
        val orderChanged = reordered.routeOrder != trip.routeOrder || trip.routeIsExample
        return if (orderChanged && reordered.driverCheckedAtMillis != null) reordered.copy(driverCheckedAtMillis = null) else reordered
    }
}
