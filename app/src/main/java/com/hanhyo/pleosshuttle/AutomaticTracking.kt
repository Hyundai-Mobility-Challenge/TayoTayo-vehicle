package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*

enum class TrackingTransition { NONE, DEPART, NEXT_STOP, FINISH }

/** Called only after an accepted, durably saved teacher action. No timer-driven opt-in. */
fun automaticTrackingTransition(before: TripState, after: TripState): TrackingTransition {
    if (before.tripId != after.tripId) return TrackingTransition.NONE
    if (after.phase in listOf(TripPhase.END_REVIEW, TripPhase.ENDED) && before.phase != after.phase)
        return TrackingTransition.FINISH
    // 등원(V-25)은 출발 때 탄 아이가 없어도 정류장으로 가므로 위치 공유를 켠다.
    if (after.phase != TripPhase.DRIVING || after.currentStop == null || (after.onboardChildren.isEmpty() && !after.isPickup))
        return TrackingTransition.NONE
    if (before.phase == TripPhase.PREPARING) return TrackingTransition.DEPART
    if (before.currentStop?.id != after.currentStop?.id) return TrackingTransition.NEXT_STOP
    return TrackingTransition.NONE
}
