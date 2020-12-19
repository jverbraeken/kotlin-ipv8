package nl.tudelft.ipv8.automation

import mu.KotlinLogging
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat

import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.fixedRateTimer

private const val DATE_PATTERN = "yyyy-MM-dd_HH.mm.ss"
private val DATE_FORMAT = SimpleDateFormat(DATE_PATTERN, Locale.US)
private val logger = KotlinLogging.logger("EvaluationProcessor")

class EvaluationProcessor(
    baseDirectory: File,
    runner: String
) {
    @Transient
    private val dataLines = ArrayList<String>()

    @Transient
    private val configurationLines = ArrayList<String>()
    private val fileDirectory = File(baseDirectory.path, "evaluations")
    private val fileResults = File(fileDirectory, "evaluation-$runner-${DATE_FORMAT.format(Date())}.csv")
    private var fileMeta = File(fileDirectory, "evaluation-$runner-${DATE_FORMAT.format(Date())}.meta.csv")
    private lateinit var currentName: String

    init {
        if (!fileDirectory.exists()) {
            fileDirectory.mkdirs()
        }
        fileResults.createNewFile()
        fileMeta.createNewFile()
        configurationLines.add(
            arrayOf(
                "name",
                "simulationIndex",
                "dataset",
                "optimizer",
                "learning rate",
                "momentum",
                "l2",

                "batchSize",
                "iteratorDistribution",
                "maxTestSamples",

                "gar",
                "communicationPattern",
                "behavior",
                "numEpochs",
                "slowdown",
                "joiningLate",

                "local model poisoning attack",
                "#attackers"
            ).joinToString(",")
        )

        dataLines.add("simulationIndex, elapsedTime, epoch, iteration, accuracy, f1, precision, recall, gmeasure, mcc, score, before or after averaging, #peers included in current batch")

        fixedRateTimer(period = 2500) {
            PrintWriter(fileResults).use { pw ->
                synchronized(dataLines) {
                    dataLines.forEach(pw::println)
                }
            }
        }
    }

    fun newSimulation(
        name: String,
        mlConfiguration: List<Map<String, String>>
    ) {
        this.currentName = name
        mlConfiguration.forEachIndexed { index, configuration ->
            configurationLines.add(
                arrayOf(
                    name,
                    index.toString(),
                    configuration["dataset"],
                    configuration["optimizer"],
                    configuration["learningRate"],
                    configuration["momentum?"] ?: "<null>",
                    configuration["l2"],

                    configuration["batchSize"],
                    configuration["distribution"],
                    configuration["maxTestSamples"],

                    configuration["gar"],
                    configuration["communicationPattern"],
                    configuration["behavior"],
                    configuration["maxIteration"],
                    configuration["slowdown"],
                    configuration["joiningLate"],

                    configuration["attack"],
                    configuration["numAttackers"]
                ).joinToString(",")
            )
        }
        PrintWriter(fileMeta).use { pw ->
            configurationLines.forEach(pw::println)
        }
    }

    fun call(peer: Int, evaluation: String) {
        dataLines.add("$peer, $evaluation")
    }
}
