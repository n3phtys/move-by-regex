package movebyregex

import com.beust.klaxon.Klaxon
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.function.BiPredicate
import java.util.stream.Collectors


//default command just takes a list of files as argument, ready for usage
fun main(args: Array<String>) {
    val data = UserConfFileDataHolder()
    Welcome().subcommands(
        Dashboard(data),
        Delete(data),
        Add(data),
        Move(data),
        Blacklist(data),
        Extension(data),
        Generalize(data),
        Check(data)
    ).main(args)
}


class Welcome : CliktCommand() {
    override fun run() {
    }
}

abstract class DataHolder {
    abstract var regexesFiles: List<Pair<Regex, File>>
    abstract var fileExtensionRegex: Regex
    abstract var exclusionRegex: Regex
}

data class RegexFilePair(
    val regex: String,
    val filepath: String
)

data class UserConfFile(
    val regexMatches: List<RegexFilePair> = listOf(RegexFilePair("myfile[0-9]*.txt", "tmp")),
    val blacklistRegex: String = "notmovethisfile\\.txt|orthisfile\\.txt",
    val extensionRegex: String = "avi|mkv|mp4"
)

class UserConfFileDataHolder : DataHolder() {
    private val location = File(System.getProperty("user.home")).resolve(".move_by_regex").resolve("move.conf")
    private fun createIfNotExist() {
        if (location.exists() && location.canRead()) {
        } else {
            save(UserConfFile())
        }
    }

    private fun save(conf: UserConfFile) {
        location.parentFile.mkdirs()
        val unpretty = Klaxon().toJsonString(conf)
        val parsed = Klaxon().parseJsonObject(unpretty.reader())
        location.writeText(parsed.toJsonString(true))
    }

    private fun load(): UserConfFile = Klaxon().parse<UserConfFile>(location.readText()) ?: throw IllegalStateException(
        "Conf File at ${location.absolutePath} seems to be missing or corrupted, please repair or delete the file manually and rerun the command afterwards."
    )

    private var userConfFile: UserConfFile

    init {
        createIfNotExist()
        userConfFile = load()
    }

    override var regexesFiles: List<Pair<Regex, File>>
        get() = userConfFile.regexMatches.map { Pair(it.regex.toRegex(), File(it.filepath)) }
        set(value) {
            userConfFile =
                userConfFile.copy(regexMatches = value.map {
                    RegexFilePair(
                        it.first.toString(),
                        it.second.path
                    )
                }.sortedBy { it.regex })
            save(userConfFile)
        }
    override var fileExtensionRegex: Regex
        get() = userConfFile.extensionRegex.toRegex()
        set(value) {
            userConfFile = userConfFile.copy(extensionRegex = value.toString())
            save(userConfFile)
        }
    override var exclusionRegex: Regex
        get() = userConfFile.blacklistRegex.toRegex()
        set(value) {
            userConfFile = userConfFile.copy(blacklistRegex = value.toString())
            save(userConfFile)
        }

}

class MockDataHolder : DataHolder() {
    override var regexesFiles = listOf(Pair("\\[MyTag]thefile.mkv".toRegex(), File("tmp")))
    override var fileExtensionRegex = "mkv".toRegex()
    override var exclusionRegex = "fileIDoNotWant".toRegex()
}


class Generalize(private val dataholder: DataHolder) : CliktCommand() {
    private val file: File by argument(help = "file that is generalized by exchanging numbers for number wildcards, and combined with given parent directory").file(
        exists = true,
        readable = true
    )

    override fun run() {
        println("file = " + file.absolutePath)
        println("file.parentFile = " + file.absoluteFile.parent)
        val regex =
            file.name.generalizeFileName().verifyFileNameWithRegex(file.name).store(file.absoluteFile.parentFile)
        println("added regex: $regex")
    }

    private fun Regex.verifyFileNameWithRegex(fileName: String): Regex {
        if (!this.matches(fileName)) {
            throw IllegalStateException("generated regex '${this}' does not match input string '$fileName', this implies a bug in this software for the given input string. Please open a ticket on the github repository.")
        }
        return this
    }

    private fun Regex.store(parentDir: File): Regex {
        if (!(dataholder.regexesFiles.any { it.first.toString() == this.toString() })) {
            dataholder.regexesFiles = dataholder.regexesFiles.pushAtIndex(null, this, parentDir)
        }
        return this

    }


}


private const val digitPattern = "[0-9]+"
fun String.generalizeFileName(): Regex {
    val exact = this.replace(".", "\\.").replace("[", "\\[")
    //from including to excluding
    val digitAreas = mutableListOf<Pair<Int, Int>>()
    val charArr = exact.toCharArray()
    var lastStart: Int? = null
    charArr.forEachIndexed { idx, char ->
        if (!char.isADigit()) {
            //finish existing pair
            if (lastStart != null) {
                digitAreas.add(Pair(lastStart!!, idx))
                lastStart = null
            }
        } else {
            if (idx < 1) {
                //special case: first digit
                lastStart = 0
            } else {
                val prev = charArr[idx - 1]
                if (prev.isADigit()) {
                    //do nothing
                } else {
                    lastStart = idx
                }
            }
        }
    }
    if (lastStart != null) {
        digitAreas.add(Pair(lastStart!!, charArr.size))
    }
    var offset = 0
    var word = exact
    digitAreas.forEach {
        //replace the given substring in the word using offset, increase or decrease offset
        word = word.replaceRange(it.first + offset, it.second + offset, digitPattern)
        val dif = it.second - it.first
        offset += digitPattern.length - dif
    }
    return word.toRegex()
}

private fun Char.isADigit(): Boolean {
    return this.isDigit()
}

private fun <E, F> List<Pair<E, F>>.pushAtIndex(idx: Int?, key: E, value: F): List<Pair<E, F>> {
    return if (idx == null) {
        this + Pair(key, value)
    } else if (idx >= 0 && idx < this.size) {
        ((this.subList(0, idx) + Pair(key, value)) + (this.subList(idx + 1, this.size)))
    } else {
        throw UsageError("idx is not allowed to be outside [0,${this.size})")
    }
}


class Delete(private val dataholder: DataHolder) : CliktCommand() {
    private val index: Int by option(help = "index to replace, null if just add new one").int().required()
    override fun run() {
        val idx = index
        if (idx >= 0 && idx < dataholder.regexesFiles.size) {
            dataholder.regexesFiles = ((dataholder.regexesFiles.subList(0, idx) + (dataholder.regexesFiles.subList(
                idx + 1,
                dataholder.regexesFiles.size
            ))))
        } else {
            throw UsageError("idx is not allowed to be outside [0,${dataholder.regexesFiles.size})")
        }
    }
}

class Add(private val dataholder: DataHolder) : CliktCommand() {
    private val index: Int? by option(help = "index to replace, null if just add new one").int()
    private val regex: String by argument(help = "regex to add / update")
    private val targetParent: File by option(help = "parent directory of target, defaults to pwd").file().default(
        File(
            System.getProperty(
                "user.dir"
            )
        )
    )

    override fun run() {
        //check if not already contained:
        if (!(dataholder.regexesFiles.any { it.first.toString() == regex })) {
            val r = regex.toRegex()
            val idx = index
            dataholder.regexesFiles = dataholder.regexesFiles.pushAtIndex(idx, r, targetParent.absoluteFile)
        }
    }
}

class Dashboard(private val dataholder: DataHolder) : CliktCommand() {
    private val header: Boolean by option(help = "include header with blacklist and extension filter").flag(
        "no-header",
        default = true
    )

    override fun run() {
        if (header) {
            println("blacklist: ${dataholder.exclusionRegex}")
            println("extension filter: ${dataholder.fileExtensionRegex}")
        }
        dataholder.regexesFiles.forEach {
            println("${it.first}|${it.second.path}")
        }
    }
}

class Blacklist(private val dataholder: DataHolder) : CliktCommand() {
    private val regex: String by argument(help = "regex for files not to be considered to set")
    override fun run() {
        dataholder.exclusionRegex = regex.toRegex()
    }
}

class Extension(private val dataholder: DataHolder) : CliktCommand() {
    private val regex: String by argument(help = "regex for file extensions to set")
    override fun run() {
        dataholder.fileExtensionRegex = regex.toRegex()
    }
}

class Check(private val dataholder: DataHolder) : CliktCommand(), Retrievable {
    private val files: List<File> by argument(help = "files to be scanned").file(
        exists = true,
        readable = true
    ).multiple(
        required = true
    )

    override fun run() {
        println("checking in ${File(System.getProperty("user.dir")).absolutePath}:")
        listFilesRecursively(files, dataholder.fileExtensionRegex).filterMapByRegexList(
            dataholder.regexesFiles,
            dataholder.exclusionRegex,
            dataholder.fileExtensionRegex
        ).forEach { println("MATCH: ${it.key.path}  ==>  ${it.value.path}") }
    }

}

interface Retrievable {

    fun listFilesRecursively(filesAndDirectories: List<File>, extensionRegex: Regex): List<File> {
        return filesAndDirectories.flatMap { input ->
            Files.find(
                input.toPath(),
                Integer.MAX_VALUE,
                BiPredicate { filePath, fileAttr -> fileAttr.isRegularFile && extensionRegex.matches(((filePath.toFile().extension))) })
                .map { it.toFile() }.collect(
                    Collectors.toList()
                )
        }
    }


    //returns source / target pairs
    fun List<File>.filterMapByRegexList(
        regexToTargetDirectory: List<Pair<Regex, File>>,
        blacklist: Regex,
        extensionRegex: Regex
    ): Map<File, File> {
        return this.filter {
            checkBlacklist(blacklist, it)
        }.filter {
            checkExtension(extensionRegex, it)
        }.mapNotNull { mapWithRegex(regexToTargetDirectory, it) }.toMap()
    }


    fun checkBlacklist(blacklist: Regex, file: File): Boolean = !blacklist.containsMatchIn(file.path)

    fun checkExtension(extensionRegex: Regex, file: File): Boolean = extensionRegex.matches(file.extension)


    fun mapWithRegex(regexToTargetDirectory: List<Pair<Regex, File>>, file: File): Pair<File, File>? {
        val m = regexToTargetDirectory.find { p ->
            p.first.matches(file.name)
        } ?: return null
        val target = m.second.resolve(file.name)
        return Pair(file, target)
    }

}

const val noTestModeFlag = "--no-test-mode"

class Move(private val dataholder: DataHolder) : CliktCommand(), Retrievable {
    private val files: List<File> by argument(help = "files to be scanned").file(
        exists = true,
        readable = true
    ).multiple(
        required = true
    )
    private val testMode: Boolean by option(help = "set to off/no if you actually want to change files").flag(
        noTestModeFlag,
        default = true
    )
    private val copyFiles: Boolean by option(help = "set flag to use copy instead of move").flag()
    private val overwriteExisting: Boolean by option().flag()

    override fun run() {
        listFilesRecursively(files, dataholder.fileExtensionRegex).filterMapByRegexList(
            dataholder.regexesFiles,
            dataholder.exclusionRegex,
            dataholder.fileExtensionRegex
        ).createTargetParents().checkIfTargetIsFree(overwriteExisting).testMode(testMode)
            .forEach { it.moveCopy(!copyFiles) }
    }


    private fun Map<File, File>.checkIfTargetIsFree(overwriteExisting: Boolean): Map<File, File> {
        this.entries.forEach {
            if (!overwriteExisting && it.value.exists()) {
                throw UsageError("File at \"${it.value.absolutePath}\" already exists, use flag --overwrite-existing to overwrite regardless")
            }
            if (!it.key.canRead()) {
                throw UsageError("Cannot read source file at ${it.key.absolutePath}")
            }
            if (it.value.exists() && !it.value.canWrite()) {
                throw UsageError("Cannot write target file at ${it.value.absolutePath}")
            }
        }
        return this
    }

    private fun Map<File, File>.testMode(doNotModify: Boolean): Map<File, File> {
        if (doNotModify) println(
            "Test mode is active, so no files will be moved / copied. Use $noTestModeFlag to move. Files would have been: ${this.keys.joinToString(
                ";"
            ) { it.absolutePath }}"
        )
        return if (doNotModify) mapOf() else this
    }

    private fun Map<File, File>.createTargetParents(): Map<File, File> {
        this.values.forEach {
            if (!it.parentFile.exists()) {
                println("creating file at ${it.absolutePath}")
                it.parentFile.mkdirs()
            }
        }
        return this
    }


    private fun Map.Entry<File, File>.moveCopy(moveInsteadOfCopy: Boolean) {
        if (moveInsteadOfCopy) {
            Files.move(
                this.key.toPath(),
                this.value.toPath(),
                //StandardCopyOption.COPY_ATTRIBUTES,
                StandardCopyOption.REPLACE_EXISTING
            )
        } else {
            Files.copy(
                this.key.toPath(),
                this.value.toPath(),
                //StandardCopyOption.COPY_ATTRIBUTES,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

}