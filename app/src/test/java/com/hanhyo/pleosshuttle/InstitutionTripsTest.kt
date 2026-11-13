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

    private fun entry(id: String, belt: Boolean? = null, driver: Boolean? = null) =
        LedgerEntry(id, if (driver != null) "" else "c1", Attendance.NOT_BOARDED, Attendance.ONBOARD, 0L, "", belt = belt, driverCheck = driver)

    @Test fun pendingCountsAllServerEntriesWhenNothingSent() {
        assertEquals(2, InstitutionTrips.pendingCount(listOf(entry("a"), entry("b")), emptySet()))
    }

    @Test fun pendingCountsOnlyUnsentEntries() {
        assertEquals(1, InstitutionTrips.pendingCount(listOf(entry("a"), entry("b")), setOf("a", "handover-c1")))
        assertEquals(0, InstitutionTrips.pendingCount(listOf(entry("a"), entry("b")), setOf("a", "b")))
    }

    @Test fun pendingNeverCountsBeltOrDriverCheckRecords() {
        val ledger = listOf(entry("a"), entry("belt", belt = true), entry("belt2", belt = false), entry("drv", driver = true), entry("drv2", driver = false))
        assertEquals(1, InstitutionTrips.pendingCount(ledger, emptySet()))
        assertEquals(0, InstitutionTrips.pendingCount(ledger, setOf("a")))
    }

    @Test fun sentEventsAreIsolatedPerTrip() {
        val stored = mapOf(InstitutionTrips.sentEventsKey("1") to setOf("a"), InstitutionTrips.sentEventsKey("2") to setOf("b"))
        assertEquals(setOf("a"), InstitutionTrips.sentEventIds("1", stored))
        assertEquals(setOf("b"), InstitutionTrips.sentEventIds("2", stored))
        assertEquals(emptySet<String>(), InstitutionTrips.sentEventIds("3", stored))
        val stale = InstitutionTrips.staleSentKeys("2", stored.keys + listOf("sent", "sentEvents", "trip", "2:start"))
        assertEquals(setOf("sentEvents:1", "sent", "sentEvents"), stale.toSet())
    }

    @Test fun legacyKeysAreToleratedAndWrongTypesIgnored() {
        val stored = mapOf("sentEvents" to setOf("a"), "sent" to emptySet<String>(), InstitutionTrips.sentEventsKey("1") to setOf("b"))
        assertEquals(setOf("a", "b"), InstitutionTrips.sentEventIds("1", stored))
        assertEquals(0, InstitutionTrips.pendingCount(listOf(entry("a"), entry("b")), InstitutionTrips.sentEventIds("1", stored)))
        val broken = mapOf("sentEvents" to "oops", "sent" to 3, InstitutionTrips.sentEventsKey("1") to true)
        assertEquals(emptySet<String>(), InstitutionTrips.sentEventIds("1", broken))
    }
    @Test fun tripOptionsPutInProgressFirstThenNewest() {
        val ordered = InstitutionTrips.orderOptions(listOf(
            TripOption("9", "PREPARING", listOf("a"), 1, 1), TripOption("12", "PREPARING", listOf("b"), 1, 1),
            TripOption("4", "IN_PROGRESS", listOf("c"), 1, 1), TripOption("x", "PREPARING", emptyList(), 0, 0)))
        assertEquals(listOf("4", "12", "9", "x"), ordered.map { it.id })
    }
    @Test fun tripOptionSummaryShowsEndsCountAndState() {
        val s = InstitutionTrips.optionSummary(TripOption("11", "PREPARING", listOf("중앙로역", "반월당역", "경대병원역", "동대구역"), 5, 4))
        assertEquals("중앙로역 → … → 동대구역 · 정류장 4곳 · 탈 어린이 4명 · 준비", s)
        assertEquals("A → B · 정류장 2곳 · 탈 어린이 0명 · 복귀 중", InstitutionTrips.optionSummary(TripOption("1", "RETURNING", listOf("A", "B"), 0, 0)))
        assertTrue(InstitutionTrips.optionSummary(TripOption("1", "IN_PROGRESS", emptyList(), 0, 0)).startsWith("정류장 정보 없음"))
    }

    @Test fun prepareTapDuringSyncWaitsInsteadOfRejecting() {
        assertEquals(InstitutionTrips.PrepareStart.NOW, InstitutionTrips.prepareStart(allowedNow = true, allowedAfterSync = true, syncing = false))
        // 5초 동기화와 겹친 탭: 거절하지 않고 동기화 뒤 이어서 준비
        assertEquals(InstitutionTrips.PrepareStart.AFTER_SYNC, InstitutionTrips.prepareStart(allowedNow = false, allowedAfterSync = true, syncing = true))
        // 진행 중인 운행처럼 동기화와 무관한 이유면 거절
        assertEquals(InstitutionTrips.PrepareStart.BLOCKED, InstitutionTrips.prepareStart(allowedNow = false, allowedAfterSync = false, syncing = true))
        // 동기화 중이 아닌데 막혀 있으면(미전송 기록 남음 등) 거절
        assertEquals(InstitutionTrips.PrepareStart.BLOCKED, InstitutionTrips.prepareStart(allowedNow = false, allowedAfterSync = true, syncing = false))
    }
}
