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

fun loadAutomation(baseDirectory: File): Automation {
    val file = Paths.get(baseDirectory.path, "automation.config").toFile()
    val string = file.readLines().joinToString("")
    return Json.decodeFromString(string)
}

/**
 * @return 1. the configuration per node, per test, per figure ; 2. the names of the figures
 */
fun generateConfigs(
    automation: Automation
): Pair<List<List<List<Map<String, String>>>>, List<String>> {
    val configurations = arrayListOf<MutableList<MutableList<Map<String, String>>>>()
    val figureNames = arrayListOf<String>()

    // global fixed values
    val batchSize = automation.fixedValues.getValue("batchSize")
    val iteratorDistribution = automation.fixedValues.getValue("iteratorDistribution")
    val maxTestSample = automation.fixedValues.getValue("maxTestSample")
    val optimizer = automation.fixedValues.getValue("optimizer")
    val learningRate = automation.fixedValues.getValue("learningRate")
    val momentum = automation.fixedValues.getValue("momentum")
    val l2Regularization = automation.fixedValues.getValue("l2Regularization")
    val communicationPattern = automation.fixedValues.getValue("communicationPattern")
    val figures = automation.figures

    for (figure in figures) {
        configurations.add(arrayListOf())
        figureNames.add(figure.name)
        val dataset = figure.fixedValues.getValue("dataset")
        val maxIterations = figure.fixedValues.getValue("maxIterations")
        val behavior = figure.fixedValues.getValue("behavior")
        val modelPoisoningAttack = figure.fixedValues.getValue("modelPoisoningAttack")
        val numNodes = figure.fixedValues.getValue("numNodes").toInt()
        val numAttackers = figure.fixedValues.getValue("numAttackers")
        val firstNodeSpeed = figure.fixedValues["firstNodeSpeed"]?.toInt() ?: 0
        val firstNodeJoiningLate = figure.fixedValues["firstNodeJoiningLate"]?.equals("true") ?: false
        val overrideIteratorDistribution = figure.iteratorDistributions

        for (test in figure.tests) {
            configurations.last().add(arrayListOf())
            val gar = test.gar

            for (node in 0 until numNodes) {
                val distribution = overrideIteratorDistribution?.get(node % overrideIteratorDistribution.size) ?: iteratorDistribution
                val configuration = mapOf(
                    Pair("dataset", dataset),

                    Pair("batchSize", batchSize),
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
                    Pair(
                        "slowdown",
                        if ((node == 0 && firstNodeSpeed == -1) || (node != 0 && firstNodeSpeed == 1)) "d2" else "none"
                    ),
                    Pair("joiningLate", if (node == 0 && firstNodeJoiningLate) "n2" else "n0"),

                    Pair("modelPoisoningAttack", modelPoisoningAttack),
                    Pair("numAttackers", numAttackers)
                )
                configurations.last().last().add(configuration)
            }
        }
    }
    return Pair(configurations, figureNames)
}
