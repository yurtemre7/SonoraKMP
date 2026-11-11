package de.yurtemre.sonora.ui.format

import org.junit.Test
import kotlin.test.assertEquals

class FolderDisplayTest {
    @Test fun primaryStorage() = assertEquals("Music › Test", FolderDisplay.format("/storage/emulated/0/Music/Test"))
    @Test fun sdCardUsesLabel() =
        assertEquals("SD card › Music", FolderDisplay.format("/storage/1A2B-3C4D/Music") { "SD card" })
    @Test fun sdCardWithoutLabel() = assertEquals("1A2B-3C4D › Music", FolderDisplay.format("/storage/1A2B-3C4D/Music/"))
    @Test fun otherPathUnchanged() = assertEquals("/data/x", FolderDisplay.format("/data/x"))
}
