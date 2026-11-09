package dev.rubenmoya.traelo.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LauncherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, path: String) {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText("config_version=5")
    }

    @Test
    fun projectInRoot() {
        val root = tmp.newFolder("repo")
        touch(root, "project.godot")
        val found = ProjectFinder.find(root)
        assertNotNull(found)
        assertEquals(root, found!!.dir)
        assertEquals("", found.relativePath)
        assertEquals(1, found.found)
    }

    @Test
    fun projectInSubfolder() {
        val root = tmp.newFolder("repo")
        touch(root, "game/project.godot")
        val found = ProjectFinder.find(root)!!
        assertEquals("game", found.relativePath)
        assertEquals(1, found.found)
    }

    @Test
    fun manyProjectsPickShallowestAndCountAll() {
        val root = tmp.newFolder("repo")
        touch(root, "a/b/c/project.godot")
        touch(root, "demos/project.godot")
        touch(root, "z/project.godot")
        val found = ProjectFinder.find(root)!!
        assertEquals("demos", found.relativePath)
        assertEquals(3, found.found)
    }

    @Test
    fun deeperThanThreeLevelsIsIgnored() {
        val root = tmp.newFolder("repo")
        touch(root, "a/b/c/d/project.godot")
        assertNull(ProjectFinder.find(root))
    }

    @Test
    fun hiddenFoldersAreSkipped() {
        val root = tmp.newFolder("repo")
        touch(root, ".git/project.godot")
        assertNull(ProjectFinder.find(root))
    }

    @Test
    fun missingFolderGivesNull() {
        assertNull(ProjectFinder.find(File(tmp.root, "nope")))
    }

    @Test
    fun editorPackagePrefersNonDebug() {
        val pkg = GodotLauncher.pickEditorPackage(
            listOf("com.android.chrome", "org.godotengine.editor.v4.debug", "org.godotengine.editor.v4"),
        )
        assertEquals("org.godotengine.editor.v4", pkg)
    }

    @Test
    fun editorPackageFallsBackToDebugOrNull() {
        assertEquals(
            "org.godotengine.editor.v4.debug",
            GodotLauncher.pickEditorPackage(listOf("org.godotengine.editor.v4.debug")),
        )
        assertNull(GodotLauncher.pickEditorPackage(listOf("com.android.chrome", "org.godotengine.other")))
    }
}
