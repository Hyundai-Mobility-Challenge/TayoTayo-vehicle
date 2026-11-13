package com.hanhyo.pleosshuttle.domain

/**
 * 등원(V-25~V-29) 화면·음성이 읽을 순수 계산. 엔진 상태는 바꾸지 않는다. 하원 운행에서는 모두 빈 값/false를 돌려준다.
 * 상태 키는 features.md 3.5절(flow.md 4.1절 8개 키)을 그대로 쓴다.
 */
object PickupFlow {
    const val SCHEDULED = "scheduled"
    const val ABSENT_NOTICE = "absent_notice"
    const val AT_STOP = "at_stop"
    const val HANDED_OVER = "handed_over"
    const val BOARDED = "boarded"
    const val DROPPED = "dropped"
    const val NOT_BOARDED = "not_boarded"

    /**
     * 3.5절 등원 아이 상태 키. `stay_onboard`는 등원 정상 흐름에서 쓰지 않는다(OQ-29).
     * 기관 [인계 확인]과 [하차 기록]은 한 번에 기록하므로(handoverToInstitution) 기관 쪽 `handed_over`는 따로 나오지 않는다.
     */
    fun stateKey(trip: TripState, child: ChildRecord): String = when (child.status) {
        Attendance.CONFIRMED_NOT_BOARDED -> NOT_BOARDED
        Attendance.ALIGHTED -> DROPPED
        Attendance.ONBOARD -> BOARDED
        Attendance.NOT_BOARDED -> when {
            child.receivedAtStop -> HANDED_OVER
            trip.phase == TripPhase.ALIGHTING && child.stopId == trip.currentStop?.id -> AT_STOP
            !child.expectedToBoard -> ABSENT_NOTICE
            else -> SCHEDULED
        }
    }

    /** 지금 정류장에서 아직 탑승도 미탑승 확인도 없는 아이. 비어 있어야 [다음 정류장으로](V-27). */
    fun stopPendingChildren(trip: TripState): List<ChildRecord> =
        if (!trip.isPickup || trip.phase != TripPhase.ALIGHTING) emptyList()
        else trip.currentStopChildren.filter { it.status == Attendance.NOT_BOARDED }

    /** V-28: 호출하지 않고 미탑승 확인하려 할 때 "도착 알림을 먼저 보낼까요?" 경고를 띄울지(막지 않음). */
    fun shouldSuggestGuardianCall(child: ChildRecord): Boolean =
        child.status == Attendance.NOT_BOARDED && !child.receivedAtStop && !child.guardianCalled

    /** V-29: 기관 도착 확인 뒤 아직 기관 인계(하차 기록)가 남은 아이. */
    fun awaitingInstitutionHandover(trip: TripState): List<ChildRecord> =
        if (!trip.isPickup || trip.phase != TripPhase.END_REVIEW) emptyList() else trip.onboardChildren

    /** V-29: 기관 도착 뒤 탑승도 미탑승 확인도 없는 아이(이 화면에서 미탑승 확인해야 종료 가능). */
    fun unresolvedAtInstitution(trip: TripState): List<ChildRecord> =
        if (!trip.isPickup || trip.phase != TripPhase.END_REVIEW) emptyList() else trip.undecidedChildren

    // ---------------- VS-17·VS-18 화면 규칙(순수). 엔진이 같은 조건을 다시 검사하므로 화면은 버튼 표시·활성만 정한다. ----------------

    /** VS-17: 출발 전에 [미탑승 확인]을 보일 아이. 미탑승 예정(알림 있음)이고 아직 탑승 전인 아이만(결정 31, V-26). */
    fun canConfirmBeforeDeparture(trip: TripState, child: ChildRecord): Boolean =
        trip.isPickup && trip.phase == TripPhase.PREPARING && !trip.noTripToday &&
            child.status == Attendance.NOT_BOARDED && !child.expectedToBoard

    /** VS-17: 출발 전 [미탑승 확인 취소](탑승 전으로 되돌림)를 보일 아이. */
    fun canUndoBeforeDeparture(trip: TripState, child: ChildRecord): Boolean =
        trip.isPickup && trip.phase == TripPhase.PREPARING && child.status == Attendance.CONFIRMED_NOT_BOARDED

    /** VS-18: 지금 정차한 정류장이 방문 순서의 마지막인지. 마지막이면 [기관으로 출발]. */
    fun isLastStop(trip: TripState): Boolean =
        trip.currentStop != null && trip.currentStopIndex >= trip.routeOrder.size - 1

    /**
     * VS-18 맨 위 행: 앞 정류장(지나친 정류장, 방문 순서에 없는 정류장 포함)의 처리 안 된 아이. 행마다 [미탑승 확인].
     * 아직 도착하지 않은 정류장의 아이는 넣지 않는다.
     */
    fun earlierUnresolvedChildren(trip: TripState): List<ChildRecord> {
        if (!trip.isPickup || trip.phase != TripPhase.ALIGHTING) return emptyList()
        val here = trip.currentStop?.id
        return trip.children.filter { child ->
            child.status == Attendance.NOT_BOARDED && child.stopId != here && trip.routeOrder.indexOf(child.stopId) < trip.currentStopIndex
        }
    }

    /** VS-18 [다음 정류장으로]/[기관으로 출발] 버튼 문구. */
    fun leaveStopLabel(trip: TripState): String = if (isLastStop(trip)) "기관으로 출발" else "다음 정류장으로"

    /**
     * VS-18 정류장을 떠날 수 없는 이유(없으면 null = 버튼 활성). 이 정류장 아이가 모두 탑승 또는 미탑승 확인이어야 하고,
     * 마지막 정류장이면 운행의 모든 아이가 탑승 또는 미탑승 확인이어야 한다(V-26·V-27, VS-18).
     */
    fun leaveStopBlocker(trip: TripState): String? = when {
        !trip.isPickup || trip.phase != TripPhase.ALIGHTING || trip.currentStop == null -> "정류장 정차 중이 아니에요"
        stopPendingChildren(trip).isNotEmpty() -> "이 정류장 어린이 ${stopPendingChildren(trip).size}명이 아직 탑승·미탑승 확인 전이에요"
        isLastStop(trip) && trip.undecidedChildren.isNotEmpty() -> "아직 탑승·미탑승 확인 전인 어린이 ${trip.undecidedChildren.size}명 · 모두 확인해야 기관으로 출발해요"
        else -> null
    }

    fun canLeaveStop(trip: TripState): Boolean = leaveStopBlocker(trip) == null

    /** VS-18 아이 행에서 누를 수 있는 기록. 엔진 호출은 recordReceived / board / setBeltConfirmed / callGuardian / confirmNotBoardedAtStop / correctNotBoardedToBoarded. */
    enum class StopAction { RECEIVE, BOARD, BELT, CALL_GUARDIAN, CONFIRM_NOT_BOARDED, CORRECT_TO_BOARDED }

    /**
     * VS-18 이 정류장 아이 행의 동작. 탑승 전: [인계받음](유형 먼저 선택) · [보호자에게 도착 알림](호출 전이면, 재호출 없음 D30) ·
     * [미탑승 확인]. 인계받음 뒤: [탑승]. 탑승: 벨트 미확인이면 [벨트 확인]. 미탑승 확인: 정류장을 떠나기 전이면 [탑승으로 정정].
     * 이 정류장 아이가 아니거나 정차 중이 아니면 빈 목록(떠난 뒤 정정 없음, D33).
     */
    fun stopActions(trip: TripState, child: ChildRecord): List<StopAction> {
        if (!trip.isPickup || trip.phase != TripPhase.ALIGHTING || child.stopId != trip.currentStop?.id) return emptyList()
        return when (child.status) {
            Attendance.NOT_BOARDED -> if (child.receivedAtStop) listOf(StopAction.BOARD)
                else listOfNotNull(StopAction.RECEIVE, StopAction.CALL_GUARDIAN.takeIf { !child.guardianCalled }, StopAction.CONFIRM_NOT_BOARDED)
            Attendance.ONBOARD -> if (child.beltConfirmed) emptyList() else listOf(StopAction.BELT)
            Attendance.CONFIRMED_NOT_BOARDED -> listOf(StopAction.CORRECT_TO_BOARDED)
            Attendance.ALIGHTED -> emptyList()
        }
    }

    /** [인계받음]·[탑승으로 정정]은 넘겨준 사람 유형을 고른 뒤에만 누를 수 있다(이름은 받지 않음). */
    fun receiverChosen(type: String?): Boolean = type in PickupReceivers.ALL

    /**
     * VS-04 (b) 정류장 미탑승 확인 시트 내용.
     * [suggestGuardianCall]: 도착 알림을 보내지 않았고 지금 보낼 수 있는 아이(이 정류장에 정차 중)면 경고 줄을 보인다(막지 않음).
     */
    data class NotBoardedSheet(
        val childId: String,
        /** 출결 근거 한 줄("결석 알림" / "알림 없음"). */
        val evidence: String,
        /** 호출 근거 한 줄(호출했으면 "도착 알림 보냄"), 없으면 null. */
        val callNote: String?,
        val suggestGuardianCall: Boolean,
    )

    fun notBoardedSheet(trip: TripState, child: ChildRecord): NotBoardedSheet = NotBoardedSheet(
        childId = child.id,
        evidence = if (child.expectedToBoard) "미탑승 알림 없음"
            else "미탑승 예정 · ${pickupNoticeLabel(child.notBoardingReason)}",
        callNote = if (child.guardianCalled) "보호자에게 도착 알림 보냄" else null,
        suggestGuardianCall = shouldSuggestGuardianCall(child) && trip.phase == TripPhase.ALIGHTING && child.stopId == trip.currentStop?.id,
    )

    /** 시트의 "보호자에게 미탑승 안내 보내기" 선택 → 원장 기록 종류(D30). 엔진 confirmNotBoardedAtStop이 같은 매핑으로 기록한다. */
    fun notBoardedAction(notifyGuardian: Boolean): String =
        if (notifyGuardian) PickupActions.NOT_BOARDED_NOTIFY else PickupActions.NOT_BOARDED_SILENT

    /** 등원 미탑승 예정 사유 문구(3.5절): 결석 알림 / 직접 등원 알림. 서버 SELF_PICKUP은 등원에서 직접 등원. */
    fun pickupNoticeLabel(code: String?): String = when (code?.trim()?.uppercase()) {
        "ABSENT" -> "결석 알림"
        "SELF_PICKUP" -> "직접 등원 알림"
        null, "" -> "알림"
        else -> "${notBoardingReasonLabel(code) ?: "기타"} 알림"
    }

    /** VS-17 "미탑승 예정" 배지(자동 제외 없음). 탑승 예정 아이는 null. */
    fun notBoardingBadge(child: ChildRecord): String? =
        if (child.expectedToBoard) null else "미탑승 예정 · ${pickupNoticeLabel(child.notBoardingReason)}"

    // ---------------- VS-19 보호자 호출 상태(정류장 행 안). 대기 한도·재호출 없음(D30). ----------------

    /**
     * 호출 뒤 지난 시간 문구. 1분 미만 "방금 호출", 그 뒤 "호출 n분 전"(분 단위로 바뀜). 한도 없이 계속 센다.
     * 시계가 거꾸로 가면(now < 호출 시각) "방금 호출"로 둔다. 호출이 없으면 null.
     */
    fun callElapsedLabel(child: ChildRecord, now: Long): String? {
        val at = child.guardianCalledAtMillis ?: return null
        val minutes = ((now - at).coerceAtLeast(0L) / 60_000L)
        return if (minutes < 1) "방금 호출" else "호출 ${minutes}분 전"
    }

    /**
     * VS-19 / P-21 보호자 응답 줄. 서버 운행 응답의 children[].guardianCall(가장 최근 호출)을 그대로 보여 준다.
     * - 응답 있음: "보호자 응답: 곧 나가요 · 2분 전" / "보호자 응답: 오늘 안 타요 · 미탑승은 직접 확인해 주세요"
     * - 응답 없음: 열림 "보호자 응답 기다리는 중", 만료 "보호자 응답 없음(10분 지남)", 닫힘 null
     * 응답은 참고용이며 "오늘 안 타요"도 미탑승을 확정하지 않는다(7절). 호출하지 않은 아이·정보가 없으면 null.
     */
    fun guardianResponseLabel(child: ChildRecord, call: GuardianCallInfo?, now: Long): String? {
        if (!child.guardianCalled || call == null) return null
        val ago = call.respondedAtMillis?.let { at -> ((now - at).coerceAtLeast(0L) / 60_000L).let { if (it < 1) "방금" else "${it}분 전" } }
        return when (call.response) {
            "ON_THE_WAY" -> "보호자 응답: 곧 나가요" + (ago?.let { " · $it" } ?: "")
            "NOT_RIDING" -> "보호자 응답: 오늘 안 타요" + (ago?.let { " · $it" } ?: "") + " · 미탑승은 직접 확인해 주세요"
            else -> when (call.status) {
                "OPEN" -> "보호자 응답 기다리는 중"
                "EXPIRED" -> "보호자 응답 없음(10분 지남)"
                else -> null
            }
        }
    }

    // ---------------- VS-20 기관 도착·기관 인계·종료(S-19). ----------------

    /**
     * [운행 종료]를 막는 이유(없으면 null). 기관 도착 확인 뒤(종료 확인 단계)에, 처리 안 된 아이(탑승 전·미탑승 예정, 지나친 정류장 포함)가
     * 없고, 탄 아이가 모두 기관 인계(하차 기록)되고, 선생님이 실내를 직접 확인했을 때만 종료한다.
     */
    fun institutionFinishBlocker(trip: TripState, cabinChecked: Boolean): String? = when {
        !trip.isPickup || trip.phase != TripPhase.END_REVIEW -> "기관 도착 확인 뒤 종료해요"
        unresolvedAtInstitution(trip).isNotEmpty() -> "처리 안 된 어린이 ${unresolvedAtInstitution(trip).size}명 · 미탑승 확인해 주세요"
        awaitingInstitutionHandover(trip).isNotEmpty() -> "기관 인계 전 어린이 ${awaitingInstitutionHandover(trip).size}명 · 아이마다 인계해 주세요"
        !cabinChecked -> "차 안에 남은 아이가 없는지 직접 확인해 주세요"
        else -> null
    }

    /** 3.5절 등원 차량 표기. 행 상태 칩 문구. */
    fun statusLabel(trip: TripState, child: ChildRecord): String = when (stateKey(trip, child)) {
        AT_STOP -> "이번 탑승"
        HANDED_OVER -> "인계받음 · 탑승 기록 남음"
        BOARDED -> "탑승"
        DROPPED -> "기관 인계 완료"
        NOT_BOARDED -> "미탑승 확인"
        else -> "탑승 전"
    }
}

/** 서버 children[].guardianCall 한 건(V-28). status: OPEN / RESPONDED / CLOSED / EXPIRED, response: ON_THE_WAY / NOT_RIDING / null. */
data class GuardianCallInfo(val callId: String, val status: String, val response: String?, val openedAtMillis: Long?, val respondedAtMillis: Long?)

/** 서버 시각 문자열 → epoch ms. 시간대가 없으면(예: "2026-11-13T14:10:30.281") 서버 기준 한국 시간으로 본다. 잘못된 값은 null. */
object ServerTime {
    private val SERVER_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")
    fun parseMillis(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text.replace(' ', 'T')).atZone(SERVER_ZONE).toInstant().toEpochMilli() }.getOrNull()
    }
}
