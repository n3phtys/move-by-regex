package movebyregex

import org.junit.Assert
import org.junit.Test
import java.io.File

internal class MainKtTest {

    @Test
    fun testAddUpdateExisting() {
        val input = "[MyTag] My Show Name S3 - 12 [1080p].mkv"
        val shouldOutput = "\\[MyTag] My Show Name S[0-9]+ - [0-9]+ \\[[0-9]+p]\\.mkv"
        val output = input.generalizeFileName()
        Assert.assertTrue(output.matches(input))
        Assert.assertEquals(shouldOutput, output.toString())
    }
}