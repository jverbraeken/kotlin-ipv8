package nl.tudelft.ipv8.automation

import mu.KotlinLogging
import java.io.File
import java.nio.file.Paths
import java.util.*

private const val STEP_SIZE = 10

private val logger = KotlinLogging.logger("EvaluationParser")

fun main() {
    val evaluationsFolder = Paths.get(System.getProperty("user.home"), "Downloads", "evaluations").toFile()
    val files = evaluationsFolder.listFiles()!!
    val mostRecentEvaluations = files.filter { it.isDirectory }.map {
        it.listFiles().first { it.isDirectory }.listFiles()
            .filter { file -> file.extension == "csv" && !file.name.contains("meta") }.maxByOrNull {
                val split = it.name.split('-')
                Date(
                    split[2].toInt(),
                    split[3].toInt(),
                    split[4].substring(0, 2).toInt(),
                    split[4].substring(3, 5).toInt(),
                    split[4].substring(6, 8).toInt()
                )
            }!!
    }

    // Mapping a figure to a mapping of an iteration to its accuracy
    val data = mutableMapOf<String, MutableMap<Int, Double>>()
    for (evaluation in mostRecentEvaluations) {
        val subData = scanEvaluation(evaluation)
        data.putAll(subData)
    }
    val newFile = File(evaluationsFolder, "parsed - ${mostRecentEvaluations[0].first { !it.name.contains("meta") }.name}")
    newFile.bufferedWriter().use { bw ->
        val entries = data
            .entries
            .toList()
            .sortedBy { it.key }
            .groupBy { it.key.split(" - ")[0] }
        val header = entries
            .values
            .map { group -> listOf(*(group.map { it.key }.toTypedArray()), null, null) }
            .flatten()
            .map { it ?: "" }
            .toTypedArray()
            .joinToString(", ")
        bw.write(", $header\n")
        val numIterations = data.values.map { it.size * STEP_SIZE }.maxOrNull()!!
        bw.write((0 until numIterations step (STEP_SIZE)).joinToString("\n") { iteration ->
            getAccuracies(iteration, entries).joinToString(", ") { it?.toString() ?: "" }
        })
    }
}

fun scanEvaluation(evaluation: File): MutableMap<String, MutableMap<Int, Double>> {
    logger.debug { evaluation.absolutePath }
    val lines = evaluation.readLines()

    val data = mutableMapOf<String, MutableMap<Int, Double>>()

    if (lines.size > 0) {
        for (line in lines.subList(1, lines.size)) {
            val split = line.split(", ")
            if (split[1] == "0") {
                val figure = split[0]
                val iteration = split[4].toInt()
                val accuracy = split[5].toDouble()
                data.putIfAbsent(figure, mutableMapOf())
                data[figure]!![iteration] = accuracy
            }
        }
    }
    repeat (6 - data.size) {
        data["$name - unknown fig: ${mainFile.name} $it"] = mutableMapOf()
    }
    return data
}

fun getAccuracies(iteration: Int, entryGroups: Map<String, List<Map.Entry<String, Map<Int, Double>>>>): Array<Double?> {
    return entryGroups
        .map { group -> listOf(iteration.toDouble(), *(group.value.map { it.value[iteration] }.toTypedArray()), null) }
        .flatten()
        .toTypedArray()
}
