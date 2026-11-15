package com.hanhyo.pleosshuttle

/** SDK callback receipt time, NOT a guaranteed GNSS measurement timestamp. */
data class TrackingLocation(val latitude: Double, val longitude: Double, val receivedAt: Long, val simulated: Boolean, val routeId: String? = null, val routeIndex: Int? = null) {
    fun valid(now: Long) = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0) && now - receivedAt in 0..30_000
}

/**
 * 위치 표본과 함께 보낼 차량 상태(서버 trip_location_samples.speed_kph numeric(5,1) · gear varchar(10) 'P / R / N / D / UNKNOWN').
 * 앱이 실제로 관측한 속도·기어만 담는다. 방향(headingDeg)·정확도(accuracyM)는 앱에 관측 출처가 없어 보내지 않는다.
 */
data class VehicleSample(val speedKph: Double, val gear: String)

/**
 * [observation]이 신선할 때(DriveObservation.fresh: 속도 유한 · 기어 알려짐 · 5초 이내)만 차량 상태를 만든다. 아니면 null(보내지 않음).
 * 속도는 m/s × 3.6을 소수 첫째 자리로 반올림하고 크기만 쓴다(후진 음수 포함). 서버 컬럼 범위(최대 9999.9)를 넘으면 보내지 않는다.
 */
fun vehicleSample(observation: DriveObservation?, now: Long): VehicleSample? {
    if (observation == null || !observation.fresh(now)) return null
    val gear = when (observation.gear) {
        com.hanhyo.pleosshuttle.domain.Gear.PARK -> "P"
        com.hanhyo.pleosshuttle.domain.Gear.REVERSE -> "R"
        com.hanhyo.pleosshuttle.domain.Gear.NEUTRAL -> "N"
        com.hanhyo.pleosshuttle.domain.Gear.DRIVE -> "D"
        com.hanhyo.pleosshuttle.domain.Gear.UNKNOWN -> return null
    }
    val kph = Math.round(kotlin.math.abs(observation.speedMps!!) * 3.6 * 10) / 10.0
    if (kph > 9999.9) return null
    return VehicleSample(kph, gear)
}
