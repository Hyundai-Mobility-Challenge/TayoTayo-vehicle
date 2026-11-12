package com.hanhyo.pleosshuttle.domain

import java.io.Serializable

enum class TripPhase(val label: String) { PREPARING("운행 준비"), DRIVING("운행"), ALIGHTING("하차"), END_REVIEW("종료 확인"), ENDED("운행 종료") }
enum class IntegrationMode(val label: String) { SIMULATION("모의 체험"), SDK("Pleos SDK · 에뮬레이터") }
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

data class SeatObservation(
    val areaId: Int,
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
) : Serializable {
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
) : Serializable {
    /** V-05 벨트 기록이면 true. 서버 전송(InstitutionConnection)에서 제외한다. */
    val isBeltRecord: Boolean get() = belt != null
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
    val occupiedAreas: List<Int>,
    val unknownOrStaleAreas: List<Int>,
    val vehicleConnected: Boolean,
    val sensorCoverageKnown: Boolean,
    val observedVehicle: VehicleSnapshot = VehicleSnapshot(),
    val occupiedAreaLabels: List<String> = occupiedAreas.map { it.toString() },
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
    val integrationMode: IntegrationMode = IntegrationMode.SIMULATION,
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
) : Serializable {
    val currentStop: Stop? get() = routeOrder.getOrNull(currentStopIndex)?.let { id -> stops.find { it.id == id } }
    val onboardChildren: List<ChildRecord> get() = children.filter { it.status == Attendance.ONBOARD }
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

    companion object {
        /** 3c27718 빌드의 기본 계산값으로 고정. 저장된 trip-v1.bin 복원 호환(SerialVersionUidPinTest 참고). */
        private const val serialVersionUID: Long = -1235094883528306877L
    }
}

object SampleTrip {
    fun create(): TripState {
        val origin = Stop("origin", "가상 출발지 · 서울시청", 37.5665, 126.9780)
        val stops = listOf(
            Stop("forest", "가상 정류장 A · 서울숲", 37.5445, 127.0374),
            Stop("museum", "가상 정류장 B · 국립중앙박물관", 37.5239, 126.9805),
            Stop("park", "가상 정류장 C · 올림픽공원", 37.5207, 127.1214),
        )
        return TripState(
            tripId = "fictional-afternoon-001", origin = origin, stops = stops,
            children = listOf(
                ChildRecord("child-a", "가상 어린이 가", "forest", "가상 좌석 1"),
                ChildRecord("child-b", "가상 어린이 나", "forest", "가상 좌석 2"),
                ChildRecord("child-c", "가상 어린이 다", "museum", "가상 좌석 3"),
                ChildRecord("child-d", "가상 어린이 라", "park", "가상 좌석 4"),
            ),
        )
    }
}

/**
 * 경로 확인·순서 바꾸기 화면의 가상 규칙. 운행 파일(TripState)에 넣지 않아 저장된 기존 운행의 직렬화 형식을 바꾸지 않는다.
 * 출발 시각·약속·마감·메모는 모두 가상 예시이며, 이동 시간은 RoutePlanner의 합성 행렬을 분으로 읽는다.
 */
object SampleRoutePlan {
    val departureMinute: Int = RouteTimelines.parseClock("15:30")
    const val DWELL_MINUTES = 3
    val returnDeadlineMinute: Int = RouteTimelines.parseClock("17:30")
    val rules = listOf(
        StopRule("forest", allowedPreviousIds = setOf("origin", "museum"), approachMessage = "올림픽공원 쪽에서 오면 길 건너편에 서게 돼요",
            memo = "가상 메모 · 정문 앞 버스 정류장 뒤에 정차 · 시청 쪽에서 진입"),
        StopRule("museum", earliestArrivalMinute = RouteTimelines.parseClock("16:30"), earliestLabel = "다 친구 보호자와의 약속(16:30 이후)",
            memo = "가상 메모 · 주차장 입구 옆 하차 구역 · 약속 16:30 이후"),
        StopRule("park", memo = "가상 메모 · 평화의 문 앞 하차 구역"),
    )
}
