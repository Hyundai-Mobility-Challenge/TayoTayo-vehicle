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
}
