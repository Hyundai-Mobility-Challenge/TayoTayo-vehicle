package com.hanhyo.pleosshuttle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstitutionOriginTest {
    @Test fun serverOrgIsUsedWithNameFallback() {
        val o = InstitutionTrips.institutionOrigin("햇살어린이집", 35.87, 128.60)!!
        assertEquals("햇살어린이집", o.name); assertEquals(35.87, o.latitude, 1e-9); assertEquals(128.60, o.longitude, 1e-9)
        assertEquals("기관", InstitutionTrips.institutionOrigin("", 35.87, 128.60)!!.name)
    }

    @Test fun missingOrInvalidCoordinatesGiveNoOriginInsteadOfAPlaceholder() {
        assertNull(InstitutionTrips.institutionOrigin(null, null, null))
        assertNull(InstitutionTrips.institutionOrigin("x", Double.NaN, 128.0))
        assertNull(InstitutionTrips.institutionOrigin("x", 95.0, 128.0))
        assertNull(InstitutionTrips.institutionOrigin("x", 0.0, 0.0))
    }

    @Test fun readsBackendFlatOrgFields() {
        val top = mapOf("orgName" to "햇살어린이집", "orgLatitude" to 35.8714, "orgLongitude" to 128.6014)
        val o = InstitutionTrips.institutionOriginFrom({ top[it] }, null)!!
        assertEquals("햇살어린이집", o.name); assertEquals(35.8714, o.latitude, 1e-9); assertEquals(128.6014, o.longitude, 1e-9)
        // MySQL DECIMAL이 문자열로 와도 읽는다
        val asText = mapOf("orgLatitude" to "35.87", "orgLongitude" to "128.60")
        assertEquals(35.87, InstitutionTrips.institutionOriginFrom({ asText[it] }, null)!!.latitude, 1e-9)
    }

    @Test fun readsNestedOrgObjectAndPrefersFlatWhenBoth() {
        val nested = mapOf("name" to "묶음 기관", "latitude" to 36.0, "longitude" to 127.0)
        assertEquals("묶음 기관", InstitutionTrips.institutionOriginFrom({ null }, { nested[it] })!!.name)
        val top = mapOf("orgName" to "최상위", "orgLatitude" to 35.0, "orgLongitude" to 128.0)
        assertEquals("최상위", InstitutionTrips.institutionOriginFrom({ top[it] }, { nested[it] })!!.name)
    }

    @Test fun nullOrgCoordinatesGiveNoOrigin() {
        val onlyName = mapOf("orgName" to "기관", "orgLatitude" to null, "orgLongitude" to null)
        assertNull(InstitutionTrips.institutionOriginFrom({ onlyName[it] }, null))
    }
}
