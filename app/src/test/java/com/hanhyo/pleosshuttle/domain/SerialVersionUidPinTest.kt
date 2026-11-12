package com.hanhyo.pleosshuttle.domain

import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TripStore는 TripState를 Java 직렬화로 trip-v1.bin에 저장한다. serialVersionUID를 선언하지 않으면
 * JVM이 필드·비공개가 아닌 메서드 시그니처로 UID를 계산하므로, 계산 프로퍼티 하나만 추가해도
 * UID가 바뀌어 기존 저장 운행을 InvalidClassException으로 읽지 못한다(feature 2 회귀).
 * 아래 값은 커밋 3c27718 빌드가 실제로 쓴 파일(및 같은 소스의 계산값)에서 추출해 고정한 것이다.
 * 이후 필드/메서드를 바꿔도 이 값은 절대 바꾸지 말 것. 호환되지 않는 형식 변경이 필요하면
 * 파일 이름(trip-v2 등)과 마이그레이션을 따로 만든다.
 */
class SerialVersionUidPinTest {
    private val pinned: Map<Class<*>, Long> = mapOf(
        TripState::class.java to -1235094883528306877L,
        ChildRecord::class.java to 91676870627885723L,
        DiagnosticEntry::class.java to 8831578179708251824L,
        EndRecord::class.java to 8133472449072202633L,
        VehicleSnapshot::class.java to 7408924729198644115L,
        Observation::class.java to -3782103051953243101L,
        SeatObservation::class.java to 4640871374603120409L,
        LedgerEntry::class.java to 4960029191150688459L,
        Stop::class.java to -4591785948551678804L,
        // 아래는 저장 파일에서 null이라 파일에 기록되지 않았으나, 3c27718 이후 소스가 그대로라 같은 빌드 계산값으로 고정.
        RouteProposal::class.java to -6836717557408514147L,
        UnlockConfirmation::class.java to -5490408417010799804L,
        UnlockRequest::class.java to 7788491441232106111L,
        UnlockAttempt::class.java to 6284569530357834259L,
        Surroundings::class.java to -3292896814217164568L,
    )

    @Test fun `저장 클래스의 serialVersionUID는 3c27718 값으로 고정된다`() {
        pinned.forEach { (type, uid) ->
            assertEquals("${type.simpleName} serialVersionUID", uid, ObjectStreamClass.lookup(type).serialVersionUID)
        }
    }

    @Test fun `3c27718 빌드가 저장한 trip-v1 파일을 복원한다`() {
        // TripStore는 AtomicFile 위에 ObjectOutputStream을 그대로 쓰므로 파일은 순수 Java 직렬화 바이트다.
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/trip-v1-3c27718.bin"))
        val state = ObjectInputStream(stream).use { it.readObject() as TripState }
        assertNotNull(state)
        assertNotNull(state.tripId)
        assertEquals(4, state.children.size)
        assertTrue(state.children.all { it.expectedToBoard })
        assertEquals(4, state.expectedRiderCount)
    }
}
