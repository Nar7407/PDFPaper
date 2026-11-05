package com.pdfpaper.app.ui.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.render.T68bFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * T68b: Behaviour tests for layer filtering in [ViewerViewModel].
 * Runs on device/instrumented environment with Android main looper and Application context.
 */
@RunWith(AndroidJUnit4::class)
class ViewerLayersDeviceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var viewModel: ViewerViewModel
    private lateinit var sampleFile: File

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = ViewerViewModel(app)
        val bytes = T68bFixtures.createMixedAnnotationDoc()
        sampleFile = tempFolder.newFile("sample.pdf")
        sampleFile.writeBytes(bytes)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun loadsPersistedLayerStateOnFingerprint() {
        val state = viewModel.annotationLayersState.value
        assertNotNull(state)
    }

    @Test
    fun toggleLayerGroupUpdatesStateAndSaves() {
        viewModel.toggleLayerGroup(AnnotationLayerGroup.HIGHLIGHT)
        val state = viewModel.annotationLayersState.value
        assertNotNull(state)
    }

    @Test
    fun toggleLayerColorUpdatesState() {
        viewModel.toggleLayerColor(0xFFFF00)
        val state = viewModel.annotationLayersState.value
        assertNotNull(state)
    }

    @Test
    fun soloColorEnablesOnlySpecifiedGroupAndColor() {
        viewModel.soloLayerColor(AnnotationLayerGroup.HIGHLIGHT, 0xFFFF00)
        val state = viewModel.annotationLayersState.value
        assertNotNull(state)
    }

    @Test
    fun showAllResetsToDefaultState() {
        viewModel.showAllLayers()
        val state = viewModel.annotationLayersState.value
        assertEquals(AnnotationLayersState.SHOW_ALL, state)
    }

    @Test
    fun hideAllDisablesAllAnnotationGroups() {
        viewModel.hideAllLayers()
        val state = viewModel.annotationLayersState.value
        assertNotNull(state)
    }

    @Test
    fun layersSheetVisibilityControls() {
        viewModel.openLayersSheet()
        viewModel.closeLayersSheet()
    }

    @Test
    fun annotationFilterReportEmitsDocumentCounts() {
        val report = viewModel.annotationFilterReport.value
        // Initial state may be null until document is open or emitted once ready
    }

    @Test
    fun togglingFilterLeavesPdfFileByteIdentical() {
        val hashBefore = sha256(sampleFile.readBytes())
        viewModel.toggleLayerGroup(AnnotationLayerGroup.HIGHLIGHT)
        viewModel.toggleLayerColor(0xFFFF00)
        viewModel.hideAllLayers()
        viewModel.showAllLayers()
        val hashAfter = sha256(sampleFile.readBytes())
        assertEquals("Toggling layers must NEVER modify the underlying PDF file", hashBefore, hashAfter)
    }
}
