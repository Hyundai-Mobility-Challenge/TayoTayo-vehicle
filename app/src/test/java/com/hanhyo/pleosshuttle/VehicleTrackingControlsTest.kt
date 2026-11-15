package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.TripPhase
import org.junit.Assert.*
import org.junit.Test

class VehicleTrackingControlsTest {
    private fun state(connected: Boolean = true, bound: Boolean = true, phase: TripPhase = TripPhase.DRIVING,
        onboard: Boolean = true, stop: Boolean = true, enabled: Boolean = true, storage: Boolean = true) =
        vehicleTrackingControls(connected, bound, phase, onboard, stop, enabled, storage)

    @Test fun disconnectedOrDifferentTripCannotEnableSharing() {
        assertFalse(state(connected = false).canEnable)
        assertFalse(state(bound = false).canEnable)
        assertFalse(state(storage = false).canEnable)
    }
    @Test fun preparingAndAlightingAllowOptIn() {
        assertTrue(state(phase = TripPhase.PREPARING).canEnable)
        assertTrue(state(phase = TripPhase.ALIGHTING).canEnable)
        assertEquals("위치 출처: Pleos 내비", state().source)
    }
    @Test fun unboundTripAsksToLoadTodaysTrip() {
        assertTrue(state(bound = false).guidance.contains("오늘 운행을 불러와"))
        assertTrue(state(connected = false).guidance.contains("차량 연결 코드"))
    }
    @Test fun completionCannotStartOrResumeSharing() {
        for (phase in listOf(TripPhase.END_REVIEW, TripPhase.ENDED)) assertFalse(state(phase = phase).canEnable)
    }
    @Test fun pickupInstitutionLegDoesNotShowDropOffPointWording() {
        val c = vehicleTrackingControls(true, true, TripPhase.DRIVING, true, false, true, true, pickup = true)
        assertTrue(c.guidance.startsWith("기관으로 가는 중"))
        assertFalse(c.guidance.contains("하차 지점"))
        assertTrue(state(stop = false).guidance.contains("하차 지점"))
    }
    @Test fun foregroundReportStatusFollowsTheActualSendResult() {
        assertTrue(foregroundTrackingStatus(active = true, fixSent = true, permission = true).startsWith("Pleos 내비 위치 전송"))
        assertTrue(foregroundTrackingStatus(active = true, fixSent = false, permission = false).startsWith("위치 권한 필요"))
        assertTrue(foregroundTrackingStatus(active = true, fixSent = false, permission = true).startsWith("위치 미수신"))
        assertTrue(foregroundTrackingStatus(active = false, fixSent = false, permission = true).contains("출발하면"))
    }
}
