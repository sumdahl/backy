package global.ptg

import global.ptg.ui.Query
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test
    fun wordsInAnyOrderIgnoringCaseAccentsAndSeparators() {
        assertTrue(Query("trip panch").matches("Panchpokhari Trip Views Only"))
        assertTrue(Query("img 2025").matches("IMG_20250101_120000.jpg"))
        assertTrue(Query("cafe").matches("Café photos"))
        assertTrue(Query("  ").matches("anything"))
        assertTrue(Query("whatsapp camera").matches("WhatsApp Images", "Camera"))
        assertFalse(Query("panch gym").matches("Panchpokhari"))
    }
}
