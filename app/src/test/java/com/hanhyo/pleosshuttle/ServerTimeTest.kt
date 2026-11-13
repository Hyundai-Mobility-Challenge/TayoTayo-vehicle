package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.ServerTime
import com.hanhyo.pleosshuttle.domain.SampleTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTimeTest {
    @Test fun zoneLessServerTimeIsKorea() {
        // 2026-11-13 14:10:30.281 KST = 05:10:30.281 UTC
        val utc = java.time.Instant.parse("2026-11-13T05:10:30.281Z").toEpochMilli()
        assertEquals(utc, ServerTime.parseMillis("2026-11-13T14:10:30.281"))
        assertEquals(utc, ServerTime.parseMillis("2026-11-13 14:10:30.281"))
        assertEquals(utc, ServerTime.parseMillis("2026-11-13T05:10:30.281Z"))
        assertEquals(utc, ServerTime.parseMillis("2026-11-13T14:10:30.281+09:00"))
    }
    @Test fun blankOrBadIsNull() {
        for (bad in listOf(null, "", "null", "어제", "2026-13-40")) assertNull(ServerTime.parseMillis(bad))
    }
    @Test fun checkedAtIsIsoUtcOnlyWhenChecked() {
        val trip = SampleTrip.create()
        assertNull(InstitutionTrips.checkedAt(trip))
        val at = java.time.Instant.parse("2026-11-13T05:01:12Z").toEpochMilli()
        assertEquals("2026-11-13T05:01:12Z", InstitutionTrips.checkedAt(trip.copy(driverCheckedAtMillis = at)))
    }
}
