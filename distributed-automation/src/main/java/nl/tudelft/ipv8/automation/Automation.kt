package nl.tudelft.ipv8.automation

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

@Serializable
data class Automation(val fixedValues: Map<String, String>, val figures: List<Figure>)

@Serializable
data class Figure(
    val name: String,
    val fixedValues: Map<String, String>,
    val tests: List<Test>,
    val iteratorDistributions: List<String>? = null
)

@Serializable
data class Test(val gar: String)

/**
 * @return 1. the configuration per node, per test, per figure ; 2. the names of the figures
 */
fun generateConfigs(
    automation: Automation
): Pair<List<List<List<Map<String, String>>>>, List<String>> {
    val configurations = arrayListOf<MutableList<MutableList<Map<String, String>>>>()
    val figureNames = arrayListOf<String>()

    // global fixed values
    val batchSize = automation.fixedValues["batchSize"]
    val iteratorDistribution = automation.fixedValues["iteratorDistribution"]
    val maxTestSample = automation.fixedValues["maxTestSample"]
    val optimizer = automation.fixedValues["optimizer"]
    val learningRate = automation.fixedValues["learningRate"]
    val momentum = automation.fixedValues["momentum"]
    val l2Regularization = automation.fixedValues["l2Regularization"]
    val communicationPattern = automation.fixedValues["communicationPattern"]
    val iterationsBeforeEvaluation = automation.fixedValues["iterationsBeforeEvaluation"]
    val iterationsBeforeSending = automation.fixedValues["iterationsBeforeSending"]
    val figures = automation.figures

    for (figure in figures) {
        configurations.add(arrayListOf())
        figureNames.add(figure.name)
        val dataset = figure.fixedValues["dataset"]
        val maxIterations = figure.fixedValues["maxIterations"]
        val behavior = figure.fixedValues["behavior"]
        val modelPoisoningAttack = figure.fixedValues["modelPoisoningAttack"]
        val numNodes = figure.fixedValues["numNodes"].toInt()
        val numAttackers = figure.fixedValues["numAttackers"]
        val firstNodeSpeed = figure.fixedValues["firstNodeSpeed"]?.toInt() ?: 0
        val firstNodeJoiningLate = figure.fixedValues["firstNodeJoiningLate"]?.equals("true") ?: false
        val overrideIteratorDistribution = figure.iteratorDistributions
        val overrideBatchSize = figure.fixedValues["batchSize"]

        for (test in figure.tests) {
            configurations.last().add(arrayListOf())
            val gar = test.gar

            for (node in 0 until numNodes) {
                val distribution = overrideIteratorDistribution?.get(node % overrideIteratorDistribution.size) ?: iteratorDistribution
                val slowdown = if ((node == 0 && firstNodeSpeed == -1) || (node != 0 && firstNodeSpeed == 1)) "d2" else "none"
                val joiningLate = if (node == 0 && firstNodeJoiningLate) "n2" else "n0"
                val configuration = mapOf(
                    Pair("dataset", dataset),

                    Pair("batchSize", overrideBatchSize ?: batchSize),
                    Pair("maxTestSamples", maxTestSample),
                    Pair("iteratorDistribution", distribution),

                    Pair("optimizer", optimizer),
                    Pair("learningRate", learningRate),
                    Pair("momentum", momentum),
                    Pair("l2", l2Regularization),

                    Pair("maxIterations", maxIterations),
                    Pair("gar", gar),
                    Pair("communicationPattern", communicationPattern),
                    Pair("behavior", behavior),
                    Pair("slowdown", slowdown),
                    Pair("joiningLate", joiningLate),
                    Pair("iterationsBeforeEvaluation", iterationsBeforeEvaluation),
                    Pair("iterationsBeforeSending", iterationsBeforeSending),

                    Pair("modelPoisoningAttack", modelPoisoningAttack),
                    Pair("numAttackers", numAttackers)
                )
                configurations.last().last().add(configuration)
            }
        }
    }
    return Pair(configurations, figureNames)
}
