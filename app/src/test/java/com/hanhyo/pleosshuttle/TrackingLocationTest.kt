package com.hanhyo.pleosshuttle

import org.junit.Assert.*
import org.junit.Test

class TrackingLocationTest {
    @Test fun staleFutureAndInvalidFixesAreNeverPublishedAsLive() {
        val now = 100_000L
        val fix = TrackingLocation(37.56, 126.97, now, false)
        assertTrue(fix.valid(now))
        assertFalse(fix.copy(receivedAt = now - 30_001).valid(now))
        assertFalse(fix.copy(receivedAt = now + 1).valid(now))
        assertFalse(fix.copy(latitude = Double.NaN).valid(now))
        assertFalse(fix.copy(longitude = 181.0).valid(now))
        assertFalse(fix.copy(latitude = 0.0, longitude = 0.0).valid(now))
    }
}
