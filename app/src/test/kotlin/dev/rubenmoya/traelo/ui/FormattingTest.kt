package dev.rubenmoya.traelo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class FormattingTest {
    private val utc = ZoneId.of("UTC")
    private val es = Locale.forLanguageTag("es")

    @Test
    fun shortDateFromGitHubIso() {
        assertEquals("12 nov 2026", formatShortDate("2026-11-12T08:30:00Z", utc, es).replace(".", ""))
    }

    @Test
    fun shortDateEmptyWhenMissingOrBroken() {
        assertEquals("", formatShortDate(null, utc, es))
        assertEquals("", formatShortDate("ayer", utc, es))
    }

    @Test
    fun shortShaTakesSeven() {
        assertEquals("abcdef1", shortSha("abcdef1234567890"))
        assertEquals("abc", shortSha("abc"))
    }

    @Test
    fun baseDirValidation() {
        assertEquals(BaseDirProblem.EMPTY, validateBaseDir("   "))
        assertEquals(BaseDirProblem.NOT_ABSOLUTE, validateBaseDir("Documents/Traelo"))
        assertEquals(BaseDirProblem.HAS_DOTDOT, validateBaseDir("/storage/../etc"))
        assertEquals(null, validateBaseDir("/storage/emulated/0/Documents/Traelo"))
    }

    @Test
    fun baseDirNormalization() {
        assertEquals("/storage/emulated/0/Documents/Traelo", normalizeBaseDir(" /storage/emulated/0/Documents/Traelo/ "))
    }

    @Test
    fun megabytesUseTheLocaleDecimal() {
        assertEquals("12,5", formatMegabytes(13_107_200, es))
        assertEquals("0.0", formatMegabytes(-5, Locale.US))
    }

    @Test
    fun etaIsRoundedSoItDoesNotJump() {
        assertEquals(Eta.Seconds(5), etaOf(0))
        assertEquals(Eta.Seconds(5), etaOf(3))
        assertEquals(Eta.Seconds(25), etaOf(21))
        assertEquals(Eta.Minutes(1), etaOf(60))
        assertEquals(Eta.Minutes(3), etaOf(121))
    }
}
