/*
 * Copyright (c) 2022~2023 chr_56
 */

package util.phonograph

import util.phonograph.output.GitHubReleaseMarkdown
import util.phonograph.releasenote.parseReleaseNoteYaml
import java.io.File

fun main(args: Array<String>) {

    val rootPath = args[0]
    val sourcePath = args[1]
    val targetPath = args[2]

    val model = parseReleaseNoteYaml(File("$rootPath/$sourcePath"))

    val markdown = GitHubReleaseMarkdown(model).write()
    writeToFile(markdown, "$rootPath/$targetPath")

    println(markdown)
}