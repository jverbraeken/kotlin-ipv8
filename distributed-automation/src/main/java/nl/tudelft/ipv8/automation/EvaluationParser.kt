package nl.tudelft.ipv8.automation

import java.io.File
import java.nio.file.Paths

private const val STEP_SIZE = 5

fun main() {
    val evaluationsFolder = Paths.get(System.getProperty("user.home"), "Downloads", "evaluations").toFile()
    val files = evaluationsFolder.listFiles()!!
    val mostRecentEvaluations = files
        .filter { it.name.startsWith("emulator-") }
        .map { it.listFiles()!! }
//        .sortedByDescending { it.lastModified() }
//        .take(4)

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

fun scanEvaluation(evaluations: Array<File>): MutableMap<String, MutableMap<Int, Double>> {
    val meta = evaluations.first { it.name.contains("meta") }
    val mainFile = evaluations.first { !it.name.contains("meta") }
    val name = meta.readLines()[1].split(" - ")[0]
    val lines = mainFile.readLines()

    val data = mutableMapOf<String, MutableMap<Int, Double>>()
    if (lines.isEmpty()) {
        repeat(6) {
            data["$name - unknown fig: ${mainFile.name} $it"] = mutableMapOf()
        }
        return data
    }
    for (line in lines.subList(1, lines.size)) {
        val split = line.split(", ")
        val figure = split[0]
        val iteration = split[4].toInt()
        val accuracy = split[5].toDouble()
        data.putIfAbsent(figure, mutableMapOf())
        data[figure]!![iteration] = accuracy
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
