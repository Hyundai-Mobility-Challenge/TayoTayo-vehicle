package com.hanhyo.pleosshuttle.sdk

import kotlin.math.abs

/** Pure policy shared by the real adapter and regression tests; callers cannot configure its allowlist. */
internal object UnlockSafetyPolicy {
    const val CONFIRMATION_TTL_MILLIS = 5_000L

    fun isVerifiedEmulator(
        debuggable: Boolean,
        fingerprint: String,
        hardware: String,
        product: String,
        allowedFingerprints: Set<String>,
        allowedProducts: Set<String>,
    ): Boolean = debuggable && hardware == "ranchu" &&
        fingerprint in allowedFingerprints && product in allowedProducts

    fun motionBlock(snapshot: VehicleSnapshot, running: Boolean, nowMillis: Long, expectedParkGear: Int): String? = when {
        !running -> "앱 비활성 상태"
        !snapshot.connected -> "차량 서비스 미확인"
        !snapshot.speedMetersPerSecond.isFresh(nowMillis) -> "속도 미확인 또는 오래된 값"
        snapshot.speedMetersPerSecond.value?.let { !it.isFinite() || abs(it) > 0.01f } != false -> "차량 이동 중"
        !snapshot.gear.isFresh(nowMillis) -> "기어 미확인 또는 오래된 값"
        snapshot.gear.value != expectedParkGear -> "P 기어 미확인"
        snapshot.parkingBrakeSupported && !snapshot.parkingBrake.isFresh(nowMillis) -> "지원되는 주차 브레이크 값 미확인 또는 오래됨"
        snapshot.parkingBrakeSupported && snapshot.parkingBrake.value != true -> "주차 브레이크 미체결 관측"
        snapshot.uxRestricted.value == true -> "플랫폼 주행 UX 제한 활성"
        else -> null
    }

    fun confirmationIsCurrent(
        candidate: UnlockConfirmation,
        current: UnlockConfirmation?,
        revision: Long,
        nowElapsedMillis: Long,
    ): Boolean = candidate == current && candidate.revision == revision &&
        nowElapsedMillis - candidate.createdElapsed in 0..CONFIRMATION_TTL_MILLIS
}
