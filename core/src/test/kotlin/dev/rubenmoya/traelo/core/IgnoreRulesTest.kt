package dev.rubenmoya.traelo.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreRulesTest {
    private val rules = IgnoreRules.parse(
        """
        # herramientas que no hacen falta para jugar
        tests/
        /docs/
        *.md
        art/raw/**
        tools/*.py
        hq/produccion.html

        """.trimIndent()
    )

    @Test fun folderRuleIgnoresEverythingInside() {
        assertTrue(rules.ignores("tests/test_walk.gd"))
        assertTrue(rules.ignores("tests/deep/x.gd"))
        assertTrue(rules.ignores("docs/DESIGN.md"))
        assertFalse(rules.ignores("scripts/tests/keep.gd"))
    }

    @Test fun nameRuleWorksInAnyFolder() {
        assertTrue(rules.ignores("README.md"))
        assertTrue(rules.ignores("scripts/notes.md"))
        assertFalse(rules.ignores("scripts/main.gd"))
    }

    @Test fun starsRespectFolders() {
        assertTrue(rules.ignores("tools/make_props.py"))
        assertFalse(rules.ignores("tools/sub/make.py"))
        assertTrue(rules.ignores("art/raw/a/b/c.png"))
    }

    @Test fun exactPathAndCommentsAndBlanks() {
        assertTrue(rules.ignores("hq/produccion.html"))
        assertFalse(rules.ignores("hq/otro.html"))
        assertFalse(rules.ignores("# herramientas que no hacen falta para jugar"))
    }

    @Test fun theIgnoreFileItselfIsNeverIgnored() {
        assertFalse(IgnoreRules.parse(".*\n*").ignores(IgnoreRules.FILE_NAME))
    }

    @Test fun emptyTextIgnoresNothing() {
        assertTrue(IgnoreRules.parse("\n# nada\n").isEmpty)
        assertFalse(IgnoreRules.NONE.ignores("cualquier/cosa.gd"))
    }
}
