package com.pdfpaper.engine.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T17: the pending-edit list supports undo/redo and clears redo on a new edit. */
class EditListTest {

    private fun text(value: String) = AddTextEdit(0, value, 1.0, 1.0)

    @Test
    fun undoAndRedoMoveEditsBetweenStacks() {
        val list = EditList()
        assertFalse(list.canUndo)
        assertFalse(list.canRedo)
        assertEquals(0, list.size)

        list.add(text("a"))
        list.add(text("b"))
        assertEquals(2, list.size)
        assertTrue(list.canUndo)
        assertFalse(list.canRedo)

        val undone = list.undo()
        assertEquals("b", (undone as AddTextEdit).text)
        assertEquals(1, list.size)
        assertTrue(list.canRedo)

        val redone = list.redo()
        assertEquals("b", (redone as AddTextEdit).text)
        assertEquals(2, list.size)
        assertFalse(list.canRedo)
    }

    @Test
    fun newEditClearsRedo() {
        val list = EditList()
        list.add(text("a"))
        list.undo()
        assertTrue(list.canRedo)

        list.add(text("c"))
        assertFalse(list.canRedo)
        assertEquals(1, list.size)
        assertEquals("c", (list.edits().single() as AddTextEdit).text)
    }

    @Test
    fun undoOnEmptyListReturnsNull() {
        assertFalse(EditList().canUndo)
        assertEquals(null, EditList().undo())
        assertEquals(null, EditList().redo())
    }
}
