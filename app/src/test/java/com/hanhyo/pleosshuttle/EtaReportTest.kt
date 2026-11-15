package com.hanhyo.pleosshuttle

import org.junit.Assert.*
import org.junit.Test

/** 예상 시간(eta-snapshots) 전송 실패는 위치 전송 상태와 분리한다. HTTP 없이 판단 로직만 확인한다. */
class EtaReportTest {
    @Test fun `같은 관측은 다시 보내지 않고 보낸 키는 성공했을 때만 바뀐다`() {
        assertTrue(InstitutionTrips.shouldSendEta("t/1/a", null))
        assertFalse(InstitutionTrips.shouldSendEta("t/1/a", "t/1/a"))
        assertEquals("t/2/b", InstitutionTrips.nextEtaKey("t/1/a", "t/2/b", EtaReport.SENT))
        assertEquals("t/1/a", InstitutionTrips.nextEtaKey("t/1/a", "t/2/b", EtaReport.FAILED))
        assertNull(InstitutionTrips.nextEtaKey(null, "t/2/b", EtaReport.FAILED))
        // 실패한 관측은 다음 주기에 다시 보낸다.
        assertTrue(InstitutionTrips.shouldSendEta("t/2/b", InstitutionTrips.nextEtaKey("t/1/a", "t/2/b", EtaReport.FAILED)))
    }

    @Test fun `위치를 보냈으면 예상 시간 실패는 연결 끊김이 아니라 예상 시간 실패로만 표시한다`() {
        val etaFailed = InstitutionTrips.trackingServiceStatus(true, TrackingReport(locationSent = true, eta = EtaReport.FAILED))
        assertTrue(etaFailed.startsWith("Pleos 내비 위치 전송 중"))
        assertTrue(etaFailed.contains("예상 시간 전송 실패"))
        assertFalse(etaFailed.contains("연결 끊김"))
        for (ok in listOf(EtaReport.SENT, EtaReport.UNCHANGED, EtaReport.NOT_AVAILABLE)) {
            val text = InstitutionTrips.trackingServiceStatus(true, TrackingReport(true, ok))
            assertEquals("Pleos 내비 위치 전송 중 · 내비 화면에서도 유지", text)
        }
        assertEquals("Pleos 위치 수신 대기", InstitutionTrips.trackingServiceStatus(false, TrackingReport(false)))
        assertEquals("Pleos 위치 수신 대기", InstitutionTrips.trackingServiceStatus(true, TrackingReport(false)))
    }

    @Test fun `화면 직접 보고 문구도 예상 시간 실패를 구분한다`() {
        val text = foregroundTrackingStatus(active = true, fixSent = true, permission = true, etaFailed = true)
        assertTrue(text.contains("예상 시간 전송 실패"))
        assertFalse(foregroundTrackingStatus(active = true, fixSent = true, permission = true).contains("실패"))
    }
}
