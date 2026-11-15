package com.hanhyo.pleosshuttle.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WithRoTest {
    @Test fun particleFollowsFinalConsonant() {
        assertEquals("떡잎유치원으로", withRo("떡잎유치원"))
        assertEquals("서현역으로", withRo("서현역"))
        assertEquals("판교로", withRo("판교"))
        assertEquals("서울로", withRo("서울")) // ㄹ 받침은 "로"
        assertEquals("ABC(으)로", withRo("ABC"))
    }
}
