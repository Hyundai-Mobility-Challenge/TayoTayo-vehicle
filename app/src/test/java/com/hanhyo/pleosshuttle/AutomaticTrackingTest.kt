package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticTrackingTest {
    private val prepared = SampleTrip.create().let { t -> t.copy(children=t.children.map { it.copy(status=Attendance.ONBOARD) }) }
    private val driving = prepared.copy(phase=TripPhase.DRIVING)
    @Test fun departureEnablesOnceForEitherSource() {
        for(mode in IntegrationMode.values()) assertEquals(TrackingTransition.DEPART,
            automaticTrackingTransition(prepared.copy(integrationMode=mode),driving.copy(integrationMode=mode)))
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(driving,driving))
    }
    @Test fun handoverDoesNotRestartPausedOrManuallyStoppedMovement() {
        val arrived=driving.copy(phase=TripPhase.ALIGHTING)
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(driving,arrived))
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(arrived,arrived.copy(handoverChildIds=setOf("child-a"))))
    }
    @Test fun nextStopStartsNewLegButSameStopDoesNot() {
        val arrived=driving.copy(phase=TripPhase.ALIGHTING)
        assertEquals(TrackingTransition.NEXT_STOP,automaticTrackingTransition(arrived,driving.copy(currentStopIndex=1)))
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(arrived,driving))
    }
    @Test fun returningWithNoChildrenDoesNotCreateDemoLocation() {
        val returning=driving.copy(currentStopIndex=driving.routeOrder.size,children=driving.children.map { it.copy(status=Attendance.ALIGHTED) })
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(driving,returning))
        assertEquals(TrackingTransition.FINISH,automaticTrackingTransition(returning,returning.copy(phase=TripPhase.END_REVIEW)))
    }
    @Test fun noBoardingOrUnrelatedTripNeverOptsIn() {
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(prepared,SampleTrip.create().copy(phase=TripPhase.DRIVING)))
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(prepared,driving.copy(tripId="another")))
    }
    @Test fun repeatedFinishIsInert() {
        val ended=driving.copy(phase=TripPhase.ENDED)
        assertEquals(TrackingTransition.FINISH,automaticTrackingTransition(driving,ended))
        assertEquals(TrackingTransition.NONE,automaticTrackingTransition(ended,ended))
    }
}
