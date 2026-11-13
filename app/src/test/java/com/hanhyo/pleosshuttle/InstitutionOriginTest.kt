package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstitutionOriginTest {
    private val fallback = Stop("origin", "가상 출발지 · 서울시청", 37.5665, 126.9780)
    private val daegu = "대구시청|35.8714|128.6014"

    @Test fun serverOrgWinsOverDevOverrideAndFallback() {
        val o = InstitutionTrips.institutionOrigin("햇살어린이집", 35.87, 128.60, daegu, fallback)
        assertEquals("햇살어린이집", o.name); assertEquals(35.87, o.latitude, 1e-9); assertEquals(128.60, o.longitude, 1e-9)
        assertEquals("기관", InstitutionTrips.institutionOrigin("", 35.87, 128.60, null, fallback).name)
    }

    @Test fun devOverrideUsedWhenServerHasNoOrgCoordinates() {
        val o = InstitutionTrips.institutionOrigin(null, null, null, daegu, fallback)
        assertEquals("대구시청 · 임시 기관 위치", o.name); assertEquals(35.8714, o.latitude, 1e-9); assertEquals(128.6014, o.longitude, 1e-9)
        assertEquals(35.8714, InstitutionTrips.institutionOrigin("x", Double.NaN, 128.0, daegu, fallback).latitude, 1e-9)
    }

    @Test fun invalidValuesFallBackToSample() {
        for (bad in listOf(null, "", "대구", "대구|abc|128", "|35|128", "대구|95|128", "대구|0|0"))
            assertEquals(fallback, InstitutionTrips.institutionOrigin(null, null, null, bad, fallback))
        assertNull(InstitutionTrips.parseDevOrigin("a|1"))
    }
}
