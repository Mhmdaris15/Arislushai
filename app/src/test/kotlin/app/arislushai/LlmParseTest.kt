package app.arislushai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmParseTest {
    @Test
    fun parsesPlainFormat() {
        val s = LlmClient.parse("TITLE: Performative reading and speech acts\nSUMMARY:\n- Austin's theory\n- Social media origin")
        assertEquals("Performative reading and speech acts", s.title)
        assertEquals("- Austin's theory\n- Social media origin", s.text)
    }

    @Test
    fun parsesMarkdownDecoratedFormat() {
        val s = LlmClient.parse("**TITLE:** \"Литература и перформанс\"\n\n**SUMMARY:**\n- Первый пункт")
        assertEquals("Литература и перформанс", s.title)
        assertEquals("- Первый пункт", s.text)
    }

    @Test
    fun fallsBackWhenModelIgnoresFormat() {
        val s = LlmClient.parse("- Only bullets here\n- Second")
        assertEquals("Only bullets here", s.title)
        assertTrue(s.text.contains("Second"))
    }

    @Test
    fun titleIsCappedAtEightyChars() {
        val s = LlmClient.parse("TITLE: " + "x".repeat(200) + "\nSUMMARY:\n- a")
        assertEquals(80, s.title.length)
    }

    @Test
    fun onlyOverloadAndRateLimitErrorsAreRetried() {
        assertTrue(LlmClient.isTransient(503))
        assertTrue(LlmClient.isTransient(429))
        assertTrue(LlmClient.isTransient(500))
        assertEquals(false, LlmClient.isTransient(404)) // retired model: retrying cannot help
        assertEquals(false, LlmClient.isTransient(401)) // bad key
        assertEquals(false, LlmClient.isTransient(0))   // not an HTTP error
    }

    @Test
    fun transcriptLinesCarryTimestamps() {
        val t = Summarizer.transcriptOf(listOf(CaptionItem(0, 65.0, "привет", emptyMap()), CaptionItem(1, 3.0, "мир", emptyMap())))
        assertEquals("[01:05] привет\n[00:03] мир", t)
    }
}
