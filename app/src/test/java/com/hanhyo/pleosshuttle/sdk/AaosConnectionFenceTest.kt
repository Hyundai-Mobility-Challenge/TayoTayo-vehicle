package com.hanhyo.pleosshuttle.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AaosConnectionFenceTest {
    @Test fun `연결 해제 전 시작한 조회는 늦게 완료되어도 연결 상태와 제어를 되살리지 않는다`() {
        val connection = AaosConnectionFence<Any>()
        connection.replace(Any())
        val pendingRead = connection.capture()!!
        connection.replace(null)
        var connected = false
        var controlRequests = 0

        // Same condition used before publishing real AAOS values and running the final-request continuation.
        if (connection.accepts(pendingRead)) {
            connected = true
            controlRequests++
        }

        assertFalse(connected)
        assertEquals(0, controlRequests)
        assertNull(connection.capture())
    }

    @Test fun `같은 manager로 재연결해도 이전 세대 조회를 거부하고 새 조회만 받아들인다`() {
        val connection = AaosConnectionFence<Any>()
        val manager = Any()
        connection.replace(manager)
        val oldRead = connection.capture()!!
        connection.replace(null)
        connection.replace(manager)
        val currentRead = connection.capture()!!

        assertTrue(currentRead.generation > oldRead.generation)
        assertFalse(connection.accepts(oldRead))
        assertTrue(connection.accepts(currentRead))
    }

    @Test fun `값이 같은 다른 manager 인스턴스의 조회는 현재 manager 조회로 처리하지 않는다`() {
        data class Manager(val name: String)
        val connection = AaosConnectionFence<Manager>()
        val oldManager = Manager("property")
        connection.replace(oldManager)
        val oldRead = connection.capture()!!
        val newManager = Manager("property")
        connection.replace(newManager)
        val currentRead = connection.capture()!!

        assertEquals(oldManager, newManager)
        assertFalse(oldManager === newManager)
        assertFalse(connection.accepts(oldRead))
        // A matching generation alone cannot substitute an old manager instance.
        assertFalse(connection.accepts(AaosConnectionFence.Capture(oldManager, currentRead.generation)))
        assertTrue(connection.accepts(currentRead))
        assertNotNull(connection.manager)
    }
}
