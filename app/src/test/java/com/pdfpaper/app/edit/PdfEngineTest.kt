package com.pdfpaper.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfEngineTest {

    private val style = EditableStyle(
        fontFamily = EditFontFamily.SERIF,
        fontSize = 20f,
        color = 0xFF111111
    )

    @Test
    fun addUpdateUndoAndRedoText() {
        val engine = FakePdfEngine()
        val created = engine.addText(
            pageIndex = 2,
            bounds = EditableBounds(0.1f, 0.2f, 0.5f, 0.3f),
            content = "Hello",
            style = style
        )
        assertNotNull(created)
        assertEquals(1, engine.allObjects().size)
        assertEquals("Hello", engine.allObjects().single().content)
        assertTrue(engine.canUndo)
        assertFalse(engine.canRedo)

        engine.updateText(created!!.id, "Hello world", style.copy(fontSize = 26f))
        assertEquals("Hello world", engine.allObjects().single().content)
        assertEquals(26f, engine.allObjects().single().style.fontSize)

        assertTrue(engine.undo())
        assertEquals("Hello", engine.allObjects().single().content)
        assertTrue(engine.canRedo)

        assertTrue(engine.redo())
        assertEquals("Hello world", engine.allObjects().single().content)
    }

    @Test
    fun deleteCanBeUndone() {
        val engine = FakePdfEngine()
        val created = engine.addText(
            pageIndex = 0,
            bounds = EditableBounds(0.1f, 0.1f, 0.4f, 0.2f),
            content = "Remove me",
            style = style
        )!!

        assertTrue(engine.delete(created.id))
        assertTrue(engine.allObjects().isEmpty())

        assertTrue(engine.undo())
        assertEquals("Remove me", engine.allObjects().single().content)
    }

    @Test
    fun imageObjectsKeepTheirContentUri() {
        val engine = FakePdfEngine()
        val created = engine.addImage(
            pageIndex = 1,
            bounds = EditableBounds(0.2f, 0.2f, 0.7f, 0.6f),
            contentUri = "content://photos/42",
            style = EditableStyle()
        )!!

        assertEquals(EditableObjectType.IMAGE, created.type)
        assertEquals("content://photos/42", engine.allObjects().single().content)

        engine.updateImage(created.id, "content://photos/43")
        assertEquals("content://photos/43", engine.allObjects().single().content)
    }

    @Test
    fun boundsStayInsideThePageWhenMovedAndResized() {
        val bounds = EditableBounds(0.1f, 0.2f, 0.5f, 0.4f)
        val moved = bounds.movedBy(0.9f, 0.9f)
        assertTrue(moved.right <= 1f)
        assertTrue(moved.bottom <= 1f)
        assertEquals(bounds.width, moved.width, 0.0001f)

        val resized = bounds.resized(EditableHandle.BOTTOM_RIGHT, 0.2f, 0.2f)
        assertTrue(resized.right > bounds.right)
        assertTrue(resized.bottom > bounds.bottom)
        assertTrue(resized.width >= 0.04f)
        assertTrue(resized.height >= 0.04f)
    }

    @Test
    fun clearRemovesObjectsAndHistory() {
        val engine = FakePdfEngine()
        engine.addText(0, EditableBounds(0.1f, 0.1f, 0.4f, 0.2f), "Text", style)
        engine.clear()

        assertTrue(engine.allObjects().isEmpty())
        assertFalse(engine.canUndo)
        assertFalse(engine.canRedo)
    }
}
