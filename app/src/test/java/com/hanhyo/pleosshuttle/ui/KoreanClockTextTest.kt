package com.hanhyo.pleosshuttle.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 경로 화면의 "몇 시 몇 분 출발 / 도착 예정" 표기. */
class KoreanClockTextTest {
    @Test fun afternoonWithMinutes() {
        assertEquals("오후 4시 5분", koreanClockText(16 * 60 + 5))
        assertEquals("오후 4시 23분 도착 예정", arrivalClockText(16 * 60 + 23))
    }

    @Test fun onTheHourDropsMinutes() {
        assertEquals("오후 4시 출발", departureClockText(16 * 60))
    }

    @Test fun morningNoonAndMidnight() {
        assertEquals("오전 8시 30분", koreanClockText(8 * 60 + 30))
        assertEquals("오후 12시 10분", koreanClockText(12 * 60 + 10))
        assertEquals("오전 12시 29분", koreanClockText(29))
    }

    @Test fun wrapsPastMidnight() {
        // 경로 계산이 자정을 넘기면(1440분 이상) 다음 날 시각으로 읽는다.
        assertEquals("오전 12시 5분", koreanClockText(1_445))
        assertEquals("오후 11시 50분", koreanClockText(-10))
    }
}
