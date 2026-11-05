package com.pdfpaper.app.ui.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePlanTest {

    private fun plan(vararg docAndPage: Pair<Int, Int>): List<PlanPage> =
        docAndPage.map { PlanPage(it.first, it.second) }

    private fun sourceIndexes(plan: List<PlanPage>): List<Int> = plan.map { it.sourceIndex }

    @Test
    fun rotateOnlyTouchesSelectedPages() {
        val p = plan(0 to 0, 0 to 1, 0 to 2)
        val r = PagePlan.rotate(p, setOf(0, 2), 90)
        assertEquals(listOf(90, 0, 90), r.map { it.rotationDelta })
    }

    @Test
    fun rotationAccumulatesModuloNothing() {
        val p = PagePlan.rotate(plan(0 to 0), setOf(0), 270)
        val q = PagePlan.rotate(p, setOf(0), 90)
        assertEquals(360, q[0].rotationDelta)
    }

    @Test
    fun deleteRemovesSelectedPositions() {
        val p = plan(0 to 0, 0 to 1, 0 to 2, 0 to 3)
        assertEquals(listOf(0, 2), sourceIndexes(PagePlan.delete(p, setOf(1, 3))))
    }

    @Test
    fun moveUpKeepsSelectionContiguous() {
        val p = plan(0 to 0, 0 to 1, 0 to 2, 0 to 3)
        // Move pages 2 and 3 up one slot: [0,2,3,1].
        val moved = PagePlan.moveUp(p, setOf(2, 3))
        assertEquals(listOf(0, 2, 3, 1), sourceIndexes(moved))
    }

    @Test
    fun moveDownKeepsSelectionContiguous() {
        val p = plan(0 to 0, 0 to 1, 0 to 2, 0 to 3)
        // Move pages 0 and 1 down one slot: [2,0,1,3].
        val moved = PagePlan.moveDown(p, setOf(0, 1))
        assertEquals(listOf(2, 0, 1, 3), sourceIndexes(moved))
    }

    @Test
    fun moveUpAtTopIsNoOp() {
        val p = plan(0 to 0, 0 to 1)
        assertEquals(sourceIndexes(p), sourceIndexes(PagePlan.moveUp(p, setOf(0))))
    }

    @Test
    fun historyUndoRedoWalksSnapshots() {
        val history = PagePlanHistory(plan(0 to 0, 0 to 1, 0 to 2))
        assertFalse(history.canUndo)

        history.commit(PagePlan.delete(history.plan, setOf(0)))
        assertEquals(2, history.plan.size)
        assertTrue(history.canUndo)

        history.commit(PagePlan.rotate(history.plan, setOf(0), 90))
        assertEquals(90, history.plan[0].rotationDelta)

        assertTrue(history.undo())
        assertEquals(listOf(1, 2), sourceIndexes(history.plan))
        assertTrue(history.canRedo)

        assertTrue(history.undo())
        assertEquals(3, history.plan.size)
        assertFalse(history.canUndo)

        assertTrue(history.redo())
        assertEquals(2, history.plan.size)
    }

    @Test
    fun committingSamePlanDoesNotCreateHistory() {
        val history = PagePlanHistory(plan(0 to 0))
        history.commit(history.plan)
        assertFalse(history.canUndo)
    }

    @Test
    fun historyIsBounded() {
        val history = PagePlanHistory(plan(0 to 0), limit = 3)
        repeat(10) { i ->
            history.commit(listOf(PlanPage(0, i)))
        }
        var undos = 0
        while (history.undo()) undos++
        assertEquals(3, undos)
    }
}
