package dev.rubenmoya.traelo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FolderPickerTest {
    private val primary = File("/storage/emulated/0")

    @Test
    fun primaryVolume() {
        assertEquals(
            "/storage/emulated/0/Documents/Traelo",
            FolderPicker.pathFromDocumentId("primary:Documents/Traelo", primary),
        )
    }

    @Test
    fun primaryRoot() {
        assertEquals("/storage/emulated/0", FolderPicker.pathFromDocumentId("primary:", primary))
    }

    @Test
    fun sdCard() {
        assertEquals("/storage/1A2B-3C4D/Juegos", FolderPicker.pathFromDocumentId("1A2B-3C4D:Juegos", primary))
    }

    @Test
    fun rawPath() {
        assertEquals("/storage/emulated/0/Download", FolderPicker.pathFromDocumentId("raw:/storage/emulated/0/Download", primary))
    }

    @Test
    fun unsupportedSources() {
        assertNull(FolderPicker.pathFromDocumentId("msf:1234", primary))
        assertNull(FolderPicker.pathFromDocumentId("primary:../etc", primary))
        assertNull(FolderPicker.pathFromDocumentId("raw:relativa", primary))
        assertNull(FolderPicker.pathFromDocumentId("../../x:y", primary))
    }
}
