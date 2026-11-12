package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Base64

class InstitutionTripsTest {
    @Test fun noTripMessageFollowsDirection() {
        assertTrue(InstitutionTrips.noTripMessage("PICK_UP").contains("등원 운행이 없습니다"))
        assertTrue(InstitutionTrips.noTripMessage("DROP_OFF").contains("하원 운행이 없습니다"))
        assertFalse(InstitutionTrips.noTripMessage("DROP_OFF").contains("등원"))
    }

    @Test fun selectionSkipsFinishedTrips() {
        // Server order is id desc: the newest trip may already be finished.
        assertEquals("7", InstitutionTrips.selectActiveTripId(listOf("9" to "COMPLETED", "8" to "CANCELLED", "7" to "PREPARING")))
        assertEquals("5", InstitutionTrips.selectActiveTripId(listOf("5" to "IN_PROGRESS", "4" to "PREPARING")))
        assertEquals("3", InstitutionTrips.selectActiveTripId(listOf("3" to "RETURNING")))
    }

    @Test fun selectionReturnsNullWhenOnlyFinishedOrEmpty() {
        assertNull(InstitutionTrips.selectActiveTripId(emptyList()))
        assertNull(InstitutionTrips.selectActiveTripId(listOf("2" to "COMPLETED", "1" to "CANCELLED")))
        assertNull(InstitutionTrips.selectActiveTripId(listOf("" to "PREPARING", "6" to "")))
    }

    @Test fun boardingExpectedIsPreservedInChildRecord() {
        val expected = InstitutionTrips.childRecord("c1", "가", "s1", "EXPECTED", true, null)
        assertTrue(expected.expectedToBoard)
        assertEquals(Attendance.NOT_BOARDED, expected.status)
        assertNull(expected.notBoardingReason)

        val absent = InstitutionTrips.childRecord("c2", "나", "s1", "NOT_BOARDED", false, "ABSENT")
        assertFalse(absent.expectedToBoard)
        assertEquals(Attendance.NOT_BOARDED, absent.status)
        assertEquals("ABSENT", absent.notBoardingReason)

        assertFalse(InstitutionTrips.childRecord("c3", "다", "s1", "NOT_BOARDED", 0, null).expectedToBoard)
        assertTrue(InstitutionTrips.childRecord("c4", "라", "s1", "EXPECTED", 1, null).expectedToBoard)
        assertEquals(Attendance.ONBOARD, InstitutionTrips.childRecord("c5", "마", "s1", "BOARDED", true, null).status)
        assertEquals(Attendance.ALIGHTED, InstitutionTrips.childRecord("c6", "바", "s1", "DROPPED_OFF", true, null).status)
    }

    @Test fun missingBoardingExpectedDefaultsToBoarding() {
        assertTrue(InstitutionTrips.childRecord("c1", "가", "s1", "EXPECTED", null, null).expectedToBoard)
        assertTrue(ChildRecord("c1", "가", "s1", "미배정").expectedToBoard)
        assertTrue(SampleTrip.create().children.all { it.expectedToBoard && it.notBoardingReason == null })
    }

    /** ChildRecord serialized by the build before notExpectedToBoard/notBoardingReason existed. */
    @Test fun childRecordSavedByOlderBuildStillRestoresAsExpectedToBoard() {
        val old = "rO0ABXNyACpjb20uaGFuaHlvLnBsZW9zc2h1dHRsZS5kb21haW4uQ2hpbGRSZWNvcmQBRbOgy+UCmwIABUwAAmlkdAASTGphdmEvbGFuZy9TdHJpbmc7TAAEbmFtZXEAfgABTAAGc3RhdHVzdAArTGNvbS9oYW5oeW8vcGxlb3NzaHV0dGxlL2RvbWFpbi9BdHRlbmRhbmNlO0wABnN0b3BJZHEAfgABTAALdmlydHVhbFNlYXRxAH4AAXhwdAAJY2hpbGQtb2xkdAADb2xkfnIAKWNvbS5oYW5oeW8ucGxlb3NzaHV0dGxlLmRvbWFpbi5BdHRlbmRhbmNlAAAAAAAAAAASAAB4cgAOamF2YS5sYW5nLkVudW0AAAAAAAAAABIAAHhwdAAHT05CT0FSRHQABnN0b3AtMXQAD+qwgOyDgSDsoozshJ0gMQ=="
        val restored = ObjectInputStream(ByteArrayInputStream(Base64.getDecoder().decode(old))).use { it.readObject() as ChildRecord }
        assertEquals("child-old", restored.id)
        assertEquals("stop-1", restored.stopId)
        assertEquals(Attendance.ONBOARD, restored.status)
        assertTrue(restored.expectedToBoard)
        assertNull(restored.notBoardingReason)
    }

    @Test fun newChildFieldsRoundTripThroughSerialization() {
        val child = InstitutionTrips.childRecord("c2", "나", "s1", "NOT_BOARDED", false, "SELF_PICKUP")
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(child) } }.toByteArray()
        assertEquals(child, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as ChildRecord })
    }
}
