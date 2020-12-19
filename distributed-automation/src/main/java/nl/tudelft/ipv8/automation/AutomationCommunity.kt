package nl.tudelft.ipv8.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Packet
import sun.security.action.GetPropertyAction
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.security.AccessController.doPrivileged
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger("AutomationCommunity")

interface MessageListener {
    fun onMessageReceived(messageId: AutomationCommunity.MessageId, peer: Peer, payload: Any)
}

class AutomationCommunity : Community() {
    override val serviceId = "36b098237ff4debfd0278b8b87c583e1c2cce4b7" // MUST BE THE SAME AS FEDMLCOMMUNITY!!!!
    private lateinit var testFinishedLatch: CountDownLatch
    private lateinit var evaluationProcessor: EvaluationProcessor
    private lateinit var localPortToWanPort: Map<Int, Int>
    private val wanPortToPeer = mutableMapOf<Int, Peer>()
    private var wanPortToHeartbeat = mutableMapOf<Int, Long>()

    enum class MessageId(val id: Int, val deserializer: Deserializable<out Any>) {
        MSG_NOTIFY_HEARTBEAT(110, MsgNotifyHeartbeat.Deserializer),
        MSG_NEW_TEST_COMMAND(111, MsgNewTestCommand.Deserializer),
        MSG_NOTIFY_EVALUATION(112, MsgNotifyEvaluation.Deserializer),
        MSG_NOTIFY_FINISHED(113, MsgNotifyFinished.Deserializer)
    }

    init {
        messageHandlers[MessageId.MSG_NOTIFY_HEARTBEAT.id] = ::onMsgNotifyHeartbeat
        messageHandlers[MessageId.MSG_NOTIFY_EVALUATION.id] = ::onMsgNotifyEvaluation
        messageHandlers[MessageId.MSG_NOTIFY_FINISHED.id] = ::onMsgNotifyFinished

        messageListeners[MessageId.MSG_NOTIFY_HEARTBEAT]!!.add(object : MessageListener {
            override fun onMessageReceived(messageId: MessageId, peer: Peer, payload: Any) {
                logger.info { "Heartbeat: ${peer.address.port}" }
                val port = peer.address.port
                wanPortToHeartbeat[port] = System.currentTimeMillis()
                wanPortToPeer[port] = peer
                peer.supportsUTP = true
            }
        })
        messageListeners[MessageId.MSG_NOTIFY_EVALUATION]!!.add(object : MessageListener {
            override fun onMessageReceived(messageId: MessageId, peer: Peer, payload: Any) {
                val localPort = localPortToWanPort.filterValues { it == peer.address.port }.keys.first()
                logger.info { "Evaluation: $localPort" }
                evaluationProcessor.call(localPort, (payload as MsgNotifyEvaluation).evaluation)
            }
        })
        messageListeners[MessageId.MSG_NOTIFY_FINISHED]!!.add(object : MessageListener {
            override fun onMessageReceived(messageId: MessageId, peer: Peer, payload: Any) {
                val localPort = localPortToWanPort.filterValues { it == peer.address.port }.keys.first()
                logger.info { "Finished: $localPort" }
                testFinishedLatch.countDown()
                logger.info { "#finished peers: ${localPortToWanPort.size - testFinishedLatch.count} of ${localPortToWanPort.size} peers" }
            }
        })
        startAutomation()
    }

    companion object {
        val messageListeners = MessageId.values().associate { it to mutableListOf<MessageListener>() }.toMutableMap()
    }

    @ExperimentalUnsignedTypes
    override fun onPacket(packet: Packet) {
        val sourceAddress = packet.source
        val data = packet.data

        val probablePeer = network.getVerifiedByAddress(sourceAddress)
        if (probablePeer != null) {
            probablePeer.lastResponse = Date()
        }

        val msgId = data[prefix.size].toUByte().toInt()
        val handler = messageHandlers[msgId]

        if (handler != null) {
            try {
                handler(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            logger.info("Received unknown message $msgId from $sourceAddress")
        }
    }

    private fun onMsgNotifyHeartbeat(packet: Packet) {
        onMessage(packet, MessageId.MSG_NOTIFY_HEARTBEAT)
    }

    private fun onMsgNotifyEvaluation(packet: Packet) {
        onMessage(packet, MessageId.MSG_NOTIFY_EVALUATION)
    }

    private fun onMsgNotifyFinished(packet: Packet) {
        onMessage(packet, MessageId.MSG_NOTIFY_FINISHED)
    }

    private fun onMessage(packet: Packet, messageId: MessageId) {
        val (peer, payload) = packet.getAuthPayload(messageId.deserializer)
        messageListeners.getValue(messageId).forEach { it.onMessageReceived(messageId, peer, payload) }
    }


    ////////////// AUTOMATION METHODS


    private fun startAutomation() = thread(name = "automation main thread") {
        val folder = Paths.get(System.getProperty("user.home"), "Downloads").toFile()
        evaluationProcessor = EvaluationProcessor(folder, "simulated")
        val automation = loadAutomation()
        val (configs, figureNames) = generateConfigs(automation)

        for (figure in automation.figures.indices) {
            val figureName = figureNames[figure]
            val figureConfig = configs[figure]

            for (test in figureConfig.indices) {
                runTest(figureName, figureConfig[test])
            }
        }
    }

    private fun runTest(figureName: String, config: List<Map<String, String>>) {
        prepareEnvironment()
        while (localPortToWanPort.size < config.size) {
            logger.info { "Too few devices found to run the test: ${localPortToWanPort.size} devices found, ${config.size} devices needed" }
            Thread.sleep(2000)
        }
        logger.error { "Going to test: $figureName - ${config[0]["gar"]}" }
        evaluationProcessor.newSimulation("$figureName - ${config[0]["gar"]}", config)
        val activeLocalPorts = localPortToWanPort.keys.toList().subList(0, config.size)
        testFinishedLatch = CountDownLatch(activeLocalPorts.size)

        for ((i, localPort) in activeLocalPorts.withIndex()) {
            val msgNewTestCommand = MsgNewTestCommand(config[i])
            val packet = serializePacket(MessageId.MSG_NEW_TEST_COMMAND.id, msgNewTestCommand, true)
            send(wanPortToPeer[localPortToWanPort[localPort]]!!, packet, true)
        }
        testFinishedLatch.await()
        logger.warn { "Test finished" }
    }

    private fun getPortMapping(): Map<Int, Int> {
        val getWanPorts = AutomationCommunity::class.java.classLoader.getResource("GetWanPorts.cmd")!!.path
        Runtime.getRuntime().exec(getWanPorts)
        Thread.sleep(1000)

        val folder = Paths.get(System.getProperty("user.home"), "Downloads", "wanPorts").toFile()
        return folder.list()!!.associate {
            it.split('-')[1].toInt() to File(folder, it).readLines()[0].toInt()
        }
    }

    private fun prepareEnvironment() {
        localPortToWanPort = getPortMapping()
        setupPortRedirection(localPortToWanPort)
        runAppOnAllDevices()
    }

    private fun runAppOnAllDevices() = runBlocking {
        val maxHeartbeatDelay = 5000L
        val additionalWait = 3000L
        val restartTime = 10000L
        var maxTime = System.currentTimeMillis() - maxHeartbeatDelay
        if (localPortToWanPort.all { wanPortToHeartbeat.getOrDefault(it.value, -1) >= maxTime }) {
            logger.info { "All peers alive" }
            return@runBlocking
        }

        logger.info { "Possibly not all peers alive => waiting a bit longer" }
        delay(additionalWait)
        maxTime -= additionalWait
        if (localPortToWanPort.all { wanPortToHeartbeat.getOrDefault(it.value, -1) >= maxTime }) {
            logger.info { "All peers alive" }
            return@runBlocking
        }

        val deadPeers = localPortToWanPort.filterValues { wanPortToHeartbeat.getOrDefault(it, 0) < maxTime }.keys
        logger.info { "Peers that are probably dead: $deadPeers" }
        deadPeers.forEach {
            runAppOnDevice(it)
        }
        delay(restartTime)  // Give time to restart app
        maxTime -= restartTime
        if (localPortToWanPort.all { wanPortToHeartbeat.getOrDefault(it.value, -1) >= maxTime }) {
            logger.info { "Success restarting devices => all peers alive" }
            return@runBlocking
        }
        throw RuntimeException("Failed to restart devices...")
    }

    private fun runAppOnDevice(peer: Int) {
        val file = Files.createTempFile("runAppOnDevice", ".cmd").toFile()
        PrintWriter(file).use {
            it.println(
                "@echo off\n" +
                    "adb -s emulator-$peer root\n" +
                    "adb -s emulator-$peer shell am force-stop nl.tudelft.trustchain\n" +
                    "adb -s emulator-$peer shell am start -n nl.tudelft.trustchain/nl.tudelft.trustchain.app.ui.dashboard.DashboardActivity -e activity fedml -e automationPart 0 -e enableExternalAutomation true\n"
            )
            it.flush()
        }
        Runtime.getRuntime().exec(file.path)
    }

    private fun setupPortRedirection(portMapping: Map<Int, Int>) {
        val tmpDir = Paths.get(doPrivileged(GetPropertyAction("java.io.tmpdir"))).toFile()
        val tmpTime = System.currentTimeMillis()

        createSetupPortsHelperFile(tmpDir, tmpTime)
        val mainFile = createSetupPortsMainFile(tmpDir, tmpTime, portMapping)
        Runtime.getRuntime().exec(mainFile.path)
    }

    private fun createSetupPortsMainFile(tmpDir: File, tmpTime: Long, portMapping: Map<Int, Int>): File {
        val sb = StringBuilder("@echo off\n")
        portMapping.onEachIndexed { i, (emulatorPort, wanPort) ->
            sb.append("set ports[$i]=$emulatorPort\n")
            sb.append("set redirects[$i]=$wanPort\n")
        }
        sb.append(
            "set \"x=0\"\n" +
                ":SymLoop\n" +
                "if defined ports[%x%] (\n" +
                "\tcall echo %%ports[%x%]%%\n" +
                "\tset /a \"x+=1\"\n" +
                "\tGOTO :SymLoop \n" +
                ")\n" +
                "set /a \"x-=1\"\n" +
                "echo \"Redirecting %x% AVDs\"\n" +
                "\n" +
                "setlocal EnableDelayedExpansion\n" +
                "for /L %%i in (0, 1, %x%) do (\n" +
                "\techo Forwarding AVD !ports[%%i]! to port !redirects[%%i]!\n" +
                "\tstart telnet.exe localhost !ports[%%i]!\n" +
                "\tcscript SetupPortsHelper$tmpTime.vbs !redirects[%%i]!\n" +
                ")\n" +
                "endlocal\n" +
                "exit\n"
        )

        val mainFile = File(tmpDir, "SetupPorts$tmpTime.bat")
        PrintWriter(mainFile).use {
            it.println(sb.toString())
            it.flush()
        }
        return mainFile
    }

    private fun createSetupPortsHelperFile(tmpDir: File, tmpTime: Long): File {
        val helperFile = File(tmpDir, "SetupPortsHelper$tmpTime.vbs")
        PrintWriter(helperFile).use {
            it.println(
                "set OBJECT=WScript.CreateObject(\"WScript.Shell\")\n" +
                    "WScript.sleep 50\n" +
                    "OBJECT.SendKeys \"auth fqvo8zH1j32aFoVB{ENTER}\"\n" + // Hardcoded password is too insignificant to remove from the code
                    "WScript.sleep 50\n" +
                    "OBJECT.SendKeys \"redir add udp:\" & WScript.Arguments.Item(0) & \":8090{ENTER}\"\n" +
                    "WScript.sleep 50\n" +
                    "OBJECT.SendKeys \"exit{ENTER}\""
            )
            it.flush()
        }
        return helperFile
    }

    private fun loadAutomation(): Automation {
        val file = File(AutomationCommunity::class.java.classLoader.getResource("automation.config")!!.path)
        val string = file.readLines().joinToString("")
        return Json.decodeFromString(string)
    }
}
