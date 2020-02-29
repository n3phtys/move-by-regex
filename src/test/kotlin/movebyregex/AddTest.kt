package movebyregex

import org.junit.Assert
import org.junit.Test
import java.io.File


internal class AddTest {

    @Test
    fun testAddUpdateExisting() {
        val md = MockDataHolder()
        val add = Add(md)
        val target = "mytmp"
        add.parse(arrayOf("--index", "0", "--target-parent", target, "myregex"))
        Assert.assertEquals(md.regexesFiles.first().first.toString(), "myregex")
        Assert.assertEquals(md.regexesFiles.first().second.path, File(target).absolutePath)
    }
}