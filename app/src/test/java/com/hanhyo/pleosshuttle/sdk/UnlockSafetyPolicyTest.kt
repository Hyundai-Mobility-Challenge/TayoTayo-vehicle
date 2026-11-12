package com.hanhyo.pleosshuttle.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class UnlockSafetyPolicyTest {
    private val knownFingerprint = "verified-emulator-fingerprint"
    private val knownProduct = "verified-emulator-product"
    private fun allowed(
        debug: Boolean = true,
        fingerprint: String = knownFingerprint,
        hardware: String = "ranchu",
        product: String = knownProduct,
        fingerprints: Set<String> = setOf(knownFingerprint),
        products: Set<String> = setOf(knownProduct),
    ) = UnlockSafetyPolicy.isVerifiedEmulator(debug, fingerprint, hardware, product, fingerprints, products)

    @Test fun `확인한 fingerprint와 product가 모두 일치하는 디버그 ranchu만 허용한다`() {
        assertTrue(allowed())
        assertFalse(allowed(debug = false))
        assertFalse(allowed(fingerprint = "other-fingerprint"))
        assertFalse(allowed(product = "other-product"))
        assertFalse(allowed(hardware = "real-car-hardware"))
    }
    @Test fun `에뮬레이터처럼 보이는 문자열만으로 문 시험을 허용하지 않는다`() {
        assertFalse(allowed(fingerprint = "generic/Pleos/ranchu/debug", product = "Pleos_Connect_v2.1.0"))
    }
    @Test fun `확인한 AVD 목록이 없으면 디버그 에뮬레이터도 허용하지 않는다`() {
        assertFalse(allowed(fingerprints = emptySet()))
        assertFalse(allowed(products = emptySet()))
        assertFalse(allowed(fingerprints = emptySet(), products = emptySet()))
    }
    @Test fun `동일한 확인 토큰은 발급 후 오초까지만 사용할 수 있다`() {
        val token = UnlockConfirmation(10, 20, 1_000)
        assertTrue(UnlockSafetyPolicy.confirmationIsCurrent(token, token, 20, 1_000))
        assertTrue(UnlockSafetyPolicy.confirmationIsCurrent(token, token, 20, 6_000))
        assertFalse(UnlockSafetyPolicy.confirmationIsCurrent(token, token, 20, 6_001))
    }
    @Test fun `이미 소비하거나 무효화한 확인 토큰은 다시 사용할 수 없다`() {
        val token = UnlockConfirmation(10, 20, 1_000)
        assertFalse(UnlockSafetyPolicy.confirmationIsCurrent(token, null, 20, 1_100))
        assertFalse(UnlockSafetyPolicy.confirmationIsCurrent(token, token, 21, 1_100))
        assertFalse(UnlockSafetyPolicy.confirmationIsCurrent(token.copy(nonce = 11), token, 20, 1_100))
        assertFalse(UnlockSafetyPolicy.confirmationIsCurrent(token.copy(areaKey = "different-group"), token, 20, 1_100))
    }
    @Test fun `단조 시각이 발급 시각보다 작으면 이전 실행의 토큰을 거부한다`() {
        val token = UnlockConfirmation(10, 20, 1_000)
        assertFalse(UnlockSafetyPolicy.confirmationIsCurrent(token, token, 20, 999))
    }
    private fun stoppedSnapshot(now: Long) = VehicleSnapshot(
        connected = true,
        speedMetersPerSecond = SdkObservation(0f, now, outcome = SdkOutcome.TARGET_OBSERVED),
        gear = SdkObservation(4, now, outcome = SdkOutcome.TARGET_OBSERVED),
        parkingBrakeSupported = true,
        parkingBrake = SdkObservation(true, now, outcome = SdkOutcome.TARGET_OBSERVED),
    )
    @Test fun `지원되는 주차 브레이크 조회가 실패해도 필수 확인을 우회하지 않는다`() {
        val now = 10_000L
        val stopped = stoppedSnapshot(now)
        assertNull(UnlockSafetyPolicy.motionBlock(stopped, true, now, 4))
        val failed = stopped.copy(parkingBrake = SdkObservation(outcome = SdkOutcome.ERROR, error = "connection lost"))
        assertNotNull(UnlockSafetyPolicy.motionBlock(failed, true, now, 4))
        val stale = stopped.copy(parkingBrake = stopped.parkingBrake.copy(observedAtMillis = 1_000))
        assertNotNull(UnlockSafetyPolicy.motionBlock(stale, true, now, 4))
        assertNotNull(UnlockSafetyPolicy.motionBlock(stopped.copy(parkingBrake = stopped.parkingBrake.copy(value = false)), true, now, 4))
    }
    @Test fun `속도와 기어가 정차여도 플랫폼 UX 제한이 있으면 문 시험을 막는다`() {
        val now = 10_000L
        val restricted = stoppedSnapshot(now).copy(uxRestricted = SdkObservation(true, now, outcome = SdkOutcome.TARGET_OBSERVED))
        assertNotNull(UnlockSafetyPolicy.motionBlock(restricted, true, now, 4))
    }
    @Test fun `후진 속도나 미확인 속도를 정차로 처리하지 않는다`() {
        val now = 10_000L
        val stopped = stoppedSnapshot(now)
        assertNotNull(UnlockSafetyPolicy.motionBlock(stopped.copy(speedMetersPerSecond = stopped.speedMetersPerSecond.copy(value = -1f)), true, now, 4))
        assertNotNull(UnlockSafetyPolicy.motionBlock(stopped.copy(speedMetersPerSecond = SdkObservation()), true, now, 4))
        assertNotNull(UnlockSafetyPolicy.motionBlock(stopped.copy(speedMetersPerSecond = stopped.speedMetersPerSecond.copy(value = Float.NaN)), true, now, 4))
    }

}
