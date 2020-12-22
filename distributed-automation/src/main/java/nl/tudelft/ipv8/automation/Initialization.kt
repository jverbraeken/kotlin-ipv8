package nl.tudelft.ipv8.automation

import mu.KotlinLogging
import java.io.File
import java.io.PrintWriter
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger("Initialization")

fun main() {
    val avdDir = File("C:/Users/jverb/.android/avd")
    val threads = (0 until 2).map { i ->
        thread {
            val port = 5554 + 2 * i

            if (!emulatorFilesExist(i, avdDir)) {
                createFiles(i, avdDir)
            } else {
                logger.debug { "Skipping copying files for emulator $port" }
            }

            if (!isEmulatorRunning(port)) {
                startEmulator(i, port)
            } else {
                logger.debug { "Skipping starting emulator $port" }
            }

            getRootAccess(port)
            installApk(port, getApkFile())
            grantPermissions(port)
            runApp(port)
        }
    }
    threads.forEach { it.join() }
}

fun emulatorFilesExist(i: Int, avdDir: File): Boolean {
    val file_emul_i_ini = File(avdDir, "emul_$i.ini")
    return file_emul_i_ini.exists()
}

fun createFiles(i: Int, avdDir: File) {
    val file_emul_i_ini = File(avdDir, "emul_$i.ini")
    PrintWriter(file_emul_i_ini).use {
        it.println(
            "avd.ini.encoding=UTF-8\n" +
                "path=C:\\Users\\jverb\\.android\\avd\\emul_$i.avd\n" +
                "path.rel=avd\\emul_$i.avd\n" +
                "target=android-30\n"
        )
        it.flush()
    }

    val file_emul_i_avd = File(avdDir, "emul_$i.avd")
    file_emul_i_avd.mkdir()
    val file_config_ini = File(file_emul_i_avd, "config.ini")
    PrintWriter(file_config_ini).use {
        it.println(
            "AvdId=emul_$i\n" +
                "PlayStore.enabled=false\n" +
                "abi.type=x86_64\n" +
                "avd.ini.displayname=emul_$i\n" +
                "avd.ini.encoding=UTF-8\n" +
                "disk.dataPartition.size=2500M\n" +
                "fastboot.chosenSnapshotFile=\n" +
                "fastboot.forceChosenSnapshotBoot=no\n" +
                "fastboot.forceColdBoot=no\n" +
                "fastboot.forceFastBoot=yes\n" +
                "hw.accelerometer=yes\n" +
                "hw.arc=false\n" +
                "hw.audioInput=yes\n" +
                "hw.battery=yes\n" +
                "hw.camera.back=virtualscene\n" +
                "hw.camera.front=emulated\n" +
                "hw.cpu.arch=x86_64\n" +
                "hw.dPad=no\n" +
                "hw.device.hash2=MD5:136aea7d36133232419000067684a792\n" +
                "hw.device.manufacturer=User\n" +
                "hw.device.name=emul_1\n" +
                "hw.gps=yes\n" +
                "hw.gpu.enabled=yes\n" +
                "hw.gpu.mode=auto\n" +
                "hw.initialOrientation=Portrait\n" +
                "hw.keyboard=yes\n" +
                "hw.lcd.density=480\n" +
                "hw.lcd.height=3200\n" +
                "hw.lcd.width=1080\n" +
                "hw.mainKeys=no\n" +
                "hw.ramSize=1800\n" +
                "hw.sdCard=yes\n" +
                "hw.sensors.orientation=yes\n" +
                "hw.sensors.proximity=yes\n" +
                "hw.trackBall=no\n" +
                "image.sysdir.1=system-images\\android-30\\google_apis\\x86_64\\\n" +
                "runtime.network.latency=none\n" +
                "runtime.network.speed=full\n" +
                "showDeviceFrame=no\n" +
                "skin.dynamic=yes\n" +
                "skin.name=1080x3200\n" +
                "skin.path=_no_skin\n" +
                "skin.path.backup=_no_skin\n" +
                "tag.display=Google APIs\n" +
                "tag.id=google_apis\n" +
                "vm.heapSize=256\n"
        )
        it.flush()
    }
    val file_userdata_img = File(Automation::class.java.classLoader.getResource("userdata.img")!!.path)
    val file_userdata_img_target = File(file_emul_i_avd, "userdata.img")
    file_userdata_img.copyTo(file_userdata_img_target)
    logger.debug { "Successfully wrote files for emulator $i" }
}

fun isEmulatorRunning(port: Int): Boolean {
    val running = Runtime.getRuntime().exec("adb devices") // -s emulator-$i shell getprop")
    running.inputStream.bufferedReader(Charsets.UTF_8).use {
        val txt = it.readLines().joinToString()
        logger.debug { txt }
        if (txt.contains(port.toString())) {
            return true
        }
    }
    return false
}

fun startEmulator(i: Int, port: Int) {
    Runtime.getRuntime()
        .exec("C:\\Users\\jverb\\AppData\\Local\\Android\\Sdk\\emulator\\emulator -avd emul_$i -no-snapshot -port $port")
    var stop = false
    while (!stop) {
        val booting = Runtime.getRuntime().exec("adb -s emulator-$port shell getprop init.svc.bootanim")
        booting.inputStream.reader(Charsets.UTF_8).use {
            if (it.readText().startsWith("stopped")) {
                stop = true
            }
        }
        logger.debug { "Waiting until booted..." }
        Thread.sleep(1000)
    }
}

fun getRootAccess(port: Int) {
    val rooting = Runtime.getRuntime().exec("adb -s emulator-$port root")
    rooting.inputStream.reader(Charsets.UTF_8).use {
        val result = it.readText()
        logger.debug { result }
        if (result.startsWith("restarting adbd as root")) {
            val rooting2 = Runtime.getRuntime().exec("adb -s emulator-$port root")
            rooting2.inputStream.reader(Charsets.UTF_8).use {
                val other = it.readText()
                logger.debug { other }
            }
        }
    }
}

fun getApkFile(): String {
    val file_apk_untrimmed =
        Automation::class.java.classLoader.getResource("app-debug.apk")!!.path
    return file_apk_untrimmed.subSequence(
        1,
        file_apk_untrimmed.length
    ).toString()  // First character is a misplaced slash
}

fun installApk(port: Int, file_apk: String) {
    val installing = Runtime.getRuntime().exec("adb -s emulator-$port install -t $file_apk")
    installing.inputStream.bufferedReader(Charsets.UTF_8).use {
        var txt = it.readLine()
        logger.debug { txt }
        txt = it.readLine()
        logger.debug { txt }
    }
}

fun grantPermissions(port: Int) {
    Runtime.getRuntime()
        .exec("adb -s emulator-$port shell pm grant nl.tudelft.trustchain android.permission.WRITE_EXTERNAL_STORAGE")
    Runtime.getRuntime()
        .exec("adb -s emulator-$port shell pm grant nl.tudelft.trustchain android.permission.READ_EXTERNAL_STORAGE")
}

fun runApp(port: Int) {
    val start = Runtime.getRuntime()
        .exec("adb -s emulator-$port shell am start -n nl.tudelft.trustchain/nl.tudelft.trustchain.app.ui.dashboard.DashboardActivity -e activity fedml -e automationPart 0 -e enableExternalAutomation true")
    start.inputStream.reader(Charsets.UTF_8).use {
        logger.debug { it.readText() }
    }
}
