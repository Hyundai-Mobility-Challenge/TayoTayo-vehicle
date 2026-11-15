package com.hanhyo.pleosshuttle

import org.junit.Assert.assertEquals
import org.junit.Test

/** 기관·차량 연결 화면에 차량 ID 대신 이름 또는 중립 문구를 보인다. */
class ConnectionLabelTest {
    @Test fun notConnected() = assertEquals("연결된 차량 없음", InstitutionTrips.connectionLabel(false, "타요 1호차"))

    @Test fun showsVehicleNameWhenServerGivesIt() =
        assertEquals("연결된 차량 · 타요 1호차", InstitutionTrips.connectionLabel(true, "타요 1호차"))

    @Test fun hidesBareIdFromOlderBuilds() {
        assertEquals("차량 연결됨", InstitutionTrips.connectionLabel(true, "연결된 차량 3"))
        assertEquals("차량 연결됨", InstitutionTrips.connectionLabel(true, ""))
        assertEquals("차량 연결됨", InstitutionTrips.connectionLabel(true, null))
    }
}
