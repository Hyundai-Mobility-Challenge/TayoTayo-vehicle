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

    @Test fun readsBackendFlatOrgFields() {
        val top = mapOf("orgName" to "햇살어린이집", "orgLatitude" to 35.8714, "orgLongitude" to 128.6014)
        val o = InstitutionTrips.institutionOriginFrom({ top[it] }, null, null, fallback)
        assertEquals("햇살어린이집", o.name); assertEquals(35.8714, o.latitude, 1e-9); assertEquals(128.6014, o.longitude, 1e-9)
        // MySQL DECIMAL이 문자열로 와도 읽는다
        val asText = mapOf("orgLatitude" to "35.87", "orgLongitude" to "128.60")
        assertEquals(35.87, InstitutionTrips.institutionOriginFrom({ asText[it] }, null, null, fallback).latitude, 1e-9)
    }

    @Test fun readsNestedOrgObjectAndPrefersFlatWhenBoth() {
        val nested = mapOf("name" to "묶음 기관", "latitude" to 36.0, "longitude" to 127.0)
        assertEquals("묶음 기관", InstitutionTrips.institutionOriginFrom({ null }, { nested[it] }, null, fallback).name)
        val top = mapOf("orgName" to "최상위", "orgLatitude" to 35.0, "orgLongitude" to 128.0)
        assertEquals("최상위", InstitutionTrips.institutionOriginFrom({ top[it] }, { nested[it] }, null, fallback).name)
    }

    @Test fun missingOrNullOrgCoordinatesFallBackToDevThenSample() {
        val onlyName = mapOf("orgName" to "기관", "orgLatitude" to null, "orgLongitude" to null)
        assertEquals("대구시청 · 임시 기관 위치", InstitutionTrips.institutionOriginFrom({ onlyName[it] }, null, daegu, fallback).name)
        assertEquals(fallback, InstitutionTrips.institutionOriginFrom({ onlyName[it] }, null, null, fallback))
    }
}
