package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.IntegrationMode
import com.hanhyo.pleosshuttle.domain.TripPhase
import org.junit.Assert.*
import org.junit.Test

class VehicleTrackingControlsTest {
    private fun state(connected: Boolean = true, bound: Boolean = true, phase: TripPhase = TripPhase.DRIVING,
        onboard: Boolean = true, stop: Boolean = true, mode: IntegrationMode = IntegrationMode.SIMULATION,
        enabled: Boolean = true, storage: Boolean = true) =
        vehicleTrackingControls(connected, bound, phase, onboard, stop, mode, enabled, storage)

    @Test fun disconnectedOrDifferentTripCannotEnableSharing() {
        assertFalse(state(connected = false).canEnable)
        assertFalse(state(bound = false).canEnable)
        assertFalse(state(storage = false).canEnable)
    }
    @Test fun preparingAllowsOptInButNeverSendsSyntheticLocation() {
        val before = state(phase = TripPhase.PREPARING)
        assertTrue(before.canEnable); assertFalse(before.canSendDemo)
    }
    @Test fun demoRequiresCurrentOnboardStopAndExplicitOptIn() {
        assertTrue(state().canSendDemo)
        assertFalse(state(onboard = false).canSendDemo)
        assertFalse(state(stop = false).canSendDemo)
        assertFalse(state(enabled = false).canSendDemo)
        assertFalse(state(bound = false).canSendDemo)
    }
    @Test fun sdkCannotSendSyntheticFixAndAlightingStillAllowsSharing() {
        assertFalse(state(mode = IntegrationMode.SDK).canSendDemo)
        assertTrue(state(phase = TripPhase.ALIGHTING, mode = IntegrationMode.SDK).canEnable)
    }
    @Test fun onlyUnboundFictionalTripCanBeArchivedForConnectedDemo() {
        fun allowed(bound:Boolean=false, mode:IntegrationMode=IntegrationMode.SIMULATION,
            id:String="fictional-demo-1", connected:Boolean=true, busy:Boolean=false, storage:Boolean=true) =
            canArchiveLocalTrackingDemo(true,connected,bound,mode,id,storage,busy)
        assertTrue(allowed()); assertFalse(allowed(bound=true)); assertFalse(allowed(mode=IntegrationMode.SDK))
        assertFalse(allowed(id="remote-active")); assertFalse(allowed(connected=false))
        assertFalse(allowed(busy=true)); assertFalse(allowed(storage=false))
    }
    @Test fun completionCannotStartOrResumeSharing() {
        for (phase in listOf(TripPhase.END_REVIEW, TripPhase.ENDED)) {
            assertFalse(state(phase = phase).canEnable)
            assertFalse(state(phase = phase).canSendDemo)
        }
    }
    @Test fun pickupInstitutionLegDoesNotShowDropOffPointWording() {
        val c = vehicleTrackingControls(true, true, TripPhase.DRIVING, true, false, IntegrationMode.SDK, true, true, pickup = true)
        assertTrue(c.guidance.startsWith("기관으로 가는 중"))
        assertFalse(c.guidance.contains("하차 지점"))
        assertTrue(state(stop = false).guidance.contains("하차 지점"))
    }
}
