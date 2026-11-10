package global.ptg

import global.ptg.ui.CELL_SIZES
import global.ptg.ui.nextCell
import org.junit.Assert.assertEquals
import org.junit.Test

class GridTest {
    @Test
    fun pinchStepsThroughSizesAndStopsAtTheEnds() {
        assertEquals(136, nextCell(104, bigger = true))
        assertEquals(80, nextCell(104, bigger = false))
        assertEquals(CELL_SIZES.first(), nextCell(CELL_SIZES.first(), bigger = false))
        assertEquals(CELL_SIZES.last(), nextCell(CELL_SIZES.last(), bigger = true))
        // A size from an older version that isn't a step snaps onto the scale.
        assertEquals(104, nextCell(90, bigger = true))
    }
}
