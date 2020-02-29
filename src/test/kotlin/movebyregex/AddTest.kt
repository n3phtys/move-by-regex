package movebyregex

import org.junit.Assert
import org.junit.Test


internal class AddTest {

    @Test
    fun testAddUpdateExisting() {
        val md = MockDataHolder()
        val add = Add(md)
        add.parse(arrayOf("--index", "0", "--target-parent", "mytmp", "myregex"))
        Assert.assertEquals(md.regexesFiles.first().first.toString(), "myregex")
        Assert.assertEquals(md.regexesFiles.first().second.path, "mytmp")
    }
}