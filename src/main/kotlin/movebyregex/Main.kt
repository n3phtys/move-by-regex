package movebyregex

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.function.BiPredicate
import java.util.stream.Collectors


//default command just takes a list of files as argument, ready for usage
fun main(args: Array<String>) {
    val data = DataHolder()
    Welcome().subcommands(
        Dashboard(data),
        Delete(data),
        Add(data),
        Move(data),
        Blacklist(data),
        Extension(data),
        Check(data)
    ).main(args)
}


class Welcome : CliktCommand() {
    override fun run() {
    }
}

class DataHolder {
    //mocks for now, will be written to user profile file in the future
    var regexesFiles = listOf(Pair("\\[MyTag]thefile.mkv".toRegex(), File("tmp")))
    var fileExtensionRegex = "mkv".toRegex()
    var exclusionRegex = "fileIDoNotWant".toRegex()
}

class Delete(val dataholder: DataHolder) : CliktCommand() {
    val index: Int by option(help = "index to replace, null if just add new one").int().required()
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

class Add(val dataholder: DataHolder) : CliktCommand() {
    val index: Int? by option(help = "index to replace, null if just add new one").int()
    val regex: String by argument(help = "regex to add / update")
    val targetParent: File by option(help = "parent directory of target, defaults to pwd").file().default(
        File(
            System.getProperty(
                "user.dir"
            )
        )
    )

    override fun run() {
        val r = regex.toRegex()
        val idx = index
        if (idx == null) {
            dataholder.regexesFiles = dataholder.regexesFiles + Pair(r, targetParent)
        } else if (idx >= 0 && idx < dataholder.regexesFiles.size) {
            dataholder.regexesFiles =
                ((dataholder.regexesFiles.subList(0, idx) + Pair(r, targetParent)) + (dataholder.regexesFiles.subList(
                    idx + 1,
                    dataholder.regexesFiles.size
                )))
        } else {
            throw UsageError("idx is not allowed to be outside [0,${dataholder.regexesFiles.size})")
        }
    }
}

class Dashboard(val dataholder: DataHolder) : CliktCommand() {
    val header: Boolean by option(help = "include header with blacklist and extension filter").flag(
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

class Blacklist(val dataholder: DataHolder) : CliktCommand() {
    val regex: String by argument(help = "regex for files not to be considered to set")
    override fun run() {
        dataholder.exclusionRegex = regex.toRegex();
    }
}

class Extension(val dataholder: DataHolder) : CliktCommand() {
    val regex: String by argument(help = "regex for file extensions to set")
    override fun run() {
        dataholder.fileExtensionRegex = regex.toRegex();
    }
}

class Check(val dataholder: DataHolder) : CliktCommand(), Retrievable {
    val files: List<File> by argument(help = "files to be scanned").file(exists = true, readable = true).multiple(
        required = true
    )

    override fun run() {
        println("checking in ${File(System.getProperty("user.dir")).absolutePath}:")
        listFilesRecursively(files).filterMapByRegexList(
            dataholder.regexesFiles,
            dataholder.exclusionRegex,
            dataholder.fileExtensionRegex
        ).forEach { println("MATCH: ${it.key.path}  ==>  ${it.value.path}") }
    }

}

interface Retrievable {

    fun listFilesRecursively(filesAndDirectories: List<File>): List<File> =
        filesAndDirectories.flatMap { input ->
            Files.find(input.toPath(), Integer.MAX_VALUE, BiPredicate { _, fileAttr -> fileAttr.isRegularFile })
                .map { it.toFile() }.collect(
                    Collectors.toList()
                )
        }


    //returns source / target pairs
    fun List<File>.filterMapByRegexList(
        regexToTargetDirectory: List<Pair<Regex, File>>,
        blacklist: Regex,
        extensionRegex: Regex
    ): Map<File, File> = this.filter {
        checkBlacklist(blacklist, it)
    }.filter {
        checkExtension(extensionRegex, it)
    }.mapNotNull { mapWithRegex(regexToTargetDirectory, it) }.toMap()

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

val noTestModeFlag = "--no-test-mode"

class Move(val dataholder: DataHolder) : CliktCommand(), Retrievable {
    val files: List<File> by argument(help = "files to be scanned").file(exists = true, readable = true).multiple(
        required = true
    )
    val testMode: Boolean by option(help = "set to off/no if you actually want to change files").flag(
        noTestModeFlag,
        default = true
    )
    val copyFiles: Boolean by option(help = "set flag to use copy instead of move").flag()
    val overwriteExisting: Boolean by option().flag()

    override fun run() {
        listFilesRecursively(files).filterMapByRegexList(
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
            "Test mode is active, so no files will be moved / copied. Use ${noTestModeFlag} to move. Files would have been: ${this.keys.joinToString(
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