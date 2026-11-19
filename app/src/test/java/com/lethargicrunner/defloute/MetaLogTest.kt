package com.lethargicrunner.defloute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaLogTest {

    @Test
    fun `date exif aller-retour`() {
        val ms = ExifUtil.parseExifDate("2024:05:17 14:03:09")
        assertTrue(ms != null)
        assertEquals("2024:05:17 14:03:09", ExifUtil.formatExifDate(ms!!))
    }

    @Test
    fun `date exif invalide ou absente`() {
        assertTrue(ExifUtil.parseExifDate(null) == null)
        assertTrue(ExifUtil.parseExifDate("") == null)
        assertTrue(ExifUtil.parseExifDate("pas une date") == null)
        assertTrue(ExifUtil.parseExifDate("0000:00:00 00:00:00") == null)
    }

    @Test
    fun `entree de journal contient titre et exception`() {
        val e = IllegalStateException("boum", RuntimeException("cause"))
        val t = LogFormat.entry("Erreur IA", e, 0L)
        assertTrue(t.contains("Erreur IA"))
        assertTrue(t.contains("IllegalStateException: boum"))
        assertTrue(t.contains("Caused by: java.lang.RuntimeException: cause"))
        assertTrue(t.contains("    at "))
    }

    @Test
    fun `journal tronque garde la fin sur ligne entiere`() {
        val text = (1..100).joinToString("\n") { "ligne numero $it" }
        val t = LogFormat.tail(text, 120)
        assertTrue(t.length <= 120)
        assertTrue(t.endsWith("ligne numero 100"))
        assertTrue(t.startsWith("ligne numero"))
        assertEquals(text, LogFormat.tail(text, 100000))
    }

    @Test
    fun `acceleration retenue seulement si fidele et plus rapide`() {
        assertTrue(AccelPolicy.accept(0.002, 0.05f, 2.0, 0.8))      // fidèle et 2,5x plus rapide
        assertTrue(!AccelPolicy.accept(0.002, 0.05f, 2.0, 1.9))     // trop peu plus rapide
        assertTrue(!AccelPolicy.accept(0.03, 0.05f, 2.0, 0.5))      // résultat différent
        assertTrue(!AccelPolicy.accept(0.002, 0.6f, 2.0, 0.5))      // écart local trop grand
    }
}
