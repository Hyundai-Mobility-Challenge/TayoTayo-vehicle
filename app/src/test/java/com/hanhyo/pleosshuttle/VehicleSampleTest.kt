package com.hanhyo.pleosshuttle

import com.hanhyo.pleosshuttle.domain.Gear
import org.junit.Assert.*
import org.junit.Test

/** 위치 표본에 붙이는 차량 상태(speedKph·gear)는 신선한 관측일 때만. 방향·정확도는 만들지 않는다. */
class VehicleSampleTest {
    private val now = 100_000L

    @Test fun freshObservationBecomesKphAndServerGearLetter() {
        assertEquals(VehicleSample(18.0, "D"), vehicleSample(DriveObservation(5.0, Gear.DRIVE, null, now - 1_000), now))
        assertEquals(VehicleSample(0.0, "P"), vehicleSample(DriveObservation(0.0, Gear.PARK, null, now), now))
        assertEquals("R", vehicleSample(DriveObservation(-1.0, Gear.REVERSE, null, now), now)!!.gear)
        assertEquals(3.6, vehicleSample(DriveObservation(-1.0, Gear.REVERSE, null, now), now)!!.speedKph, 1e-9)
        assertEquals("N", vehicleSample(DriveObservation(0.0, Gear.NEUTRAL, null, now), now)!!.gear)
        // 소수 첫째 자리(서버 numeric(5,1))로 반올림
        assertEquals(12.3, vehicleSample(DriveObservation(3.4166, Gear.DRIVE, null, now), now)!!.speedKph, 1e-9)
    }

    @Test fun staleMissingOrUnknownObservationSendsNothing() {
        assertNull(vehicleSample(null, now))
        assertNull(vehicleSample(DriveObservation(), now))
        assertNull(vehicleSample(DriveObservation(5.0, Gear.DRIVE, null, now - 5_001), now))
        assertNull(vehicleSample(DriveObservation(5.0, Gear.UNKNOWN, null, now), now))
        assertNull(vehicleSample(DriveObservation(null, Gear.DRIVE, null, now), now))
        assertNull(vehicleSample(DriveObservation(Double.NaN, Gear.DRIVE, null, now), now))
        assertNull(vehicleSample(DriveObservation(5.0, Gear.DRIVE, null, now + 10), now)) // 미래 시각
        assertNull(vehicleSample(DriveObservation(3_000.0, Gear.DRIVE, null, now), now)) // 서버 컬럼 범위 초과
    }
}
