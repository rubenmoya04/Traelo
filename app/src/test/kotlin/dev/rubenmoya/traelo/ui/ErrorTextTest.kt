package dev.rubenmoya.traelo.ui

import dev.rubenmoya.traelo.R
import dev.rubenmoya.traelo.core.GitHubException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorTextTest {
    private val fakeTime: (Long) -> String = { "10:30" }

    @Test
    fun unauthorizedSendsToSettings() {
        val info = describeError(GitHubException.Unauthorized(), fakeTime)
        assertEquals(UiText.of(R.string.err_unauthorized), info.text)
        assertEquals(ErrorAction.GO_TO_SETTINGS, info.action)
    }

    @Test
    fun notFoundHasNoExtraAction() {
        val info = describeError(GitHubException.NotFound("repo"), fakeTime)
        assertEquals(UiText.of(R.string.err_not_found), info.text)
        assertNull(info.action)
    }

    @Test
    fun rateLimitWithResetShowsTime() {
        val info = describeError(GitHubException.RateLimited(1_700_000_000L), fakeTime)
        assertEquals(UiText.of(R.string.err_rate_limited_at, "10:30"), info.text)
    }

    @Test
    fun rateLimitWithoutResetHasNoTime() {
        val info = describeError(GitHubException.RateLimited(null), fakeTime)
        assertEquals(UiText.of(R.string.err_rate_limited), info.text)
    }

    @Test
    fun networkIsMarkedOffline() {
        val info = describeError(GitHubException.Network(IOException("boom")), fakeTime)
        assertEquals(UiText.of(R.string.err_network), info.text)
        assertTrue(info.offline)
    }

    @Test
    fun httpKeepsCodeButNotDetail() {
        val info = describeError(GitHubException.Http(502, "detalle interno"), fakeTime)
        assertEquals(UiText.of(R.string.err_http, 502), info.text)
    }

    @Test
    fun integrityAndTooBig() {
        assertEquals(UiText.of(R.string.err_integrity), describeError(GitHubException.Integrity("a.txt"), fakeTime).text)
        assertEquals(UiText.of(R.string.err_too_big), describeError(GitHubException.TooBig(), fakeTime).text)
    }

    @Test
    fun diskErrorsAskForPermission() {
        val info = describeError(IOException("EACCES"), fakeTime)
        assertEquals(UiText.of(R.string.err_io), info.text)
        assertEquals(ErrorAction.GRANT_PERMISSION, info.action)
    }

    @Test
    fun unknownErrorsNeverLeakTheirMessage() {
        val info = describeError(IllegalStateException("github_pat_SECRETO"), fakeTime)
        assertEquals(UiText.of(R.string.err_unknown), info.text)
        assertFalse(info.toString().contains("SECRETO"))
    }
}
