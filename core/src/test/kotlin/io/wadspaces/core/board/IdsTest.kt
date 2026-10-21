package io.wadspaces.core.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdsTest {
    @Test
    fun idsLikeTheBoards() {
        assertTrue(Regex("^project-[0-9a-z]+-[0-9a-z]+$").matches(uid("project")))
        // The time in base 36, as wad-board's radix36 writes it.
        assertEquals("m2r1bz7k", uid("project", 1_730_000_000_000).split('-')[1])
    }
}
