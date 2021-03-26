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
    val data = mutableMapOf<String, Map<Int, List<Double>>>()
    for (evaluation in mostRecentEvaluations) {
        val subData = scanEvaluation(evaluation)
        data.putAll(subData)
    }
    val newFile = File(evaluationsFolder, "parsed evaluations.csv")
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

fun scanEvaluation(evaluation: File): Map<String, Map<Int, List<Double>>> {
    logger.debug { evaluation.absolutePath }
    val lines = evaluation.readLines()

    val data = mutableMapOf<String, MutableMap<Int, MutableList<Double>>>()
    if (lines.size == 0) {
        logger.error { "Skipping!!!!!!!!!!!!!!" }
        val map = mutableMapOf<Int, MutableList<Double>>()
        repeat(30) {
            val list = arrayListOf<Double>()
            repeat(12) {
                list.add(0.0)
            }
            map.put(it * 10, list)
        }
        data.putIfAbsent("Figure 9.0 - average - transfer", map)
        data.putIfAbsent("Figure 9.0 - median - transfer", map)
        data.putIfAbsent("Figure 9.0 - krum - transfer", map)
        data.putIfAbsent("Figure 9.0 - bridge - transfer", map)
        data.putIfAbsent("Figure 9.0 - mozi - transfer", map)
        data.putIfAbsent("Figure 9.0 - bristle - transfer", map)
        data.putIfAbsent("Figure 9.0 - average - regular", map)
        data.putIfAbsent("Figure 9.0 - median - regular", map)
        data.putIfAbsent("Figure 9.0 - krum - regular", map)
        data.putIfAbsent("Figure 9.0 - bridge - regular", map)
        data.putIfAbsent("Figure 9.0 - mozi - regular", map)
        return data
    }
    for (line in lines.subList(1, lines.size)) {
        val split = line.split(", ")
        val node = split[1].toInt()
        if (node <= 5) {
            val figure = split[0]
            val iteration = split[4].toInt()
            val accuracy = split[5].toDouble()
            data.putIfAbsent(figure, mutableMapOf())
            data[figure]!!.putIfAbsent(iteration, arrayListOf())
            data[figure]!![iteration]!!.add(accuracy)
        }
    }
    return data
}

fun getAccuracies(iteration: Int, entryGroups: Map<String, List<Map.Entry<String, Map<Int, List<Double>>>>>): Array<Double?> {
    return entryGroups
        .map { group -> listOf(iteration.toDouble(), *(group.value.map { it.value[iteration]!!.average() }.toTypedArray()), null) }
        .flatten()
        .toTypedArray()
}
