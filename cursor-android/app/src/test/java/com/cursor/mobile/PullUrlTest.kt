package com.cursor.mobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PullUrlTest {
    @Test
    fun parsesGithubPullUrl() {
        val ref = parsePullRequestUrl("https://github.com/acme/payments/pull/42")
        assertEquals("acme", ref?.owner)
        assertEquals("payments", ref?.repo)
        assertEquals(42, ref?.number)
    }

    @Test
    fun rejectsOtherUrls() {
        assertNull(parsePullRequestUrl("https://gitlab.com/acme/payments/-/merge_requests/1"))
    }
}
