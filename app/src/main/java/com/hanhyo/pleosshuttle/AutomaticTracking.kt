package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*

enum class TrackingTransition { NONE, DEPART, NEXT_STOP, FINISH }

/** Called only after an accepted, durably saved teacher action. No timer-driven opt-in. */
fun automaticTrackingTransition(before: TripState, after: TripState): TrackingTransition {
    if (before.tripId != after.tripId) return TrackingTransition.NONE
    if (after.phase in listOf(TripPhase.END_REVIEW, TripPhase.ENDED) && before.phase != after.phase)
        return TrackingTransition.FINISH
    if (after.phase != TripPhase.DRIVING || after.currentStop == null || after.onboardChildren.isEmpty())
        return TrackingTransition.NONE
    if (before.phase == TripPhase.PREPARING) return TrackingTransition.DEPART
    if (before.currentStop?.id != after.currentStop?.id) return TrackingTransition.NEXT_STOP
    return TrackingTransition.NONE
}
