package com.pickaudio

import com.pickaudio.backup.validateBackupJson
import org.junit.Assert.*
import org.junit.Test

class BackupJsonTest {
    @Test fun strictJsonRejectsDuplicateFieldsTrailingObjectsAndDeepNesting() {
        listOf("""{"version":1,"version":2}""", "{} {}", "{unquoted:1}",
            "[".repeat(40) + "0" + "]".repeat(40)).forEach {
            assertTrue(runCatching { validateBackupJson(it.reader()) }.isFailure)
        }
    }
    @Test fun nestedUnicodeAndNullMetadataRemainValid() {
        validateBackupJson("""{"name":"拾音🎵","metadata":{"files":[null,{"size":12}]},"lyrics":"[00:01]歌词"}""".reader())
    }
}
