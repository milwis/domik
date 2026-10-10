package app.domik.tools.probe

import app.domik.deimic.client.CertificatePinning
import app.domik.deimic.client.Connection
import app.domik.deimic.client.ConnectionConfig
import app.domik.deimic.client.ConnectionErrors
import app.domik.deimic.client.ConnectionException
import app.domik.deimic.client.ConnectionState
import app.domik.deimic.client.ControllerEndpoint
import app.domik.deimic.client.JvmTlsCompatibility
import app.domik.deimic.client.RequestResult
import app.domik.deimic.client.RequestTimeoutException
import app.domik.deimic.config.ConfigDownloadException
import app.domik.deimic.config.ConfigDownloader
import app.domik.deimic.config.ConfigParseException
import app.domik.deimic.config.HomeConfigParser
import app.domik.deimic.protocol.Ack
import app.domik.deimic.protocol.CommandCode
import app.domik.deimic.protocol.ConfigChecksum
import app.domik.deimic.protocol.ControllerMessage
import app.domik.deimic.protocol.Crc16Arc
import app.domik.deimic.protocol.FileDownloadResponse
import app.domik.deimic.protocol.FirmwareVersion
import app.domik.deimic.protocol.Frame
import app.domik.deimic.protocol.HeaterFlag
import app.domik.deimic.protocol.HeaterState
import app.domik.deimic.protocol.ProtocolConstants
import app.domik.deimic.protocol.Request
import app.domik.deimic.protocol.SerialNumber
import app.domik.deimic.protocol.StateRequestVariant
import app.domik.deimic.protocol.StateSnapshot
import app.domik.deimic.protocol.TemperatureReport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/*
 * Read-only probe of a 1st-generation controller.
 *
 * Sends ONLY: ping 0x1CE, firmware version 0x1D6, config checksum 0x1C8, collective state 0x202 [01],
 * heating zone state 0x6B (zones from the command line), temperature readout 0x72 and – with --download-db /
 * --db-only / --save – the download of the configuration database 0x212 (fixed path /logs/database-last.db3).
 * No command that changes the controller's state is built or sent anywhere in this tool (enforced by
 * [READ_ONLY_COMMANDS]). The connection itself sends keep-alive pings (0x1CE) every 5 s.
 *
 * The serial number is read from the config file and is never printed.
 */

private val READ_ONLY_COMMANDS = setOf(
    CommandCode.PING,
    CommandCode.FIRMWARE_VERSION,
    CommandCode.CONFIG_CHECKSUM,
    CommandCode.GET_STATE,
    CommandCode.HEATER_STATE,
    CommandCode.TEMPERATURE_REPORT,
    CommandCode.FILE_DOWNLOAD,
)

private const val USAGE = """Użycie: domik-probe [opcje]   (wyłącznie odczyty ze sterownika)
  --config <plik>        JSON z polami "host" i "serial" (domyślnie domik-probe.local.json w katalogu roboczym)
  --host <adres>         nadpisuje host z pliku konfiguracji
  --port <port>          domyślnie 60110
  --zones <moduł:nr,…>   strefy grzewcze do odczytu 0x6B, np. 1:0,1,2 (można powtórzyć); brak = pomiń 0x6B
  --listen <sekundy>     po odczytach nasłuchuj zdarzeń spontanicznych (domyślnie 5)
  --download-db          dodatkowo pobierz bazę konfiguracji (0x212, /logs/database-last.db3, odczyt) i wypisz
                         podsumowanie: poziomy, pomieszczenia, urządzenia (nazwy z Twojego domu!), problemy
  --db-only              tylko CRC 0x1C8 + pobranie bazy (bez ping/firmware/stanu/stref/temperatur/nasłuchu)
  --save <plik>          zapisz pobraną bazę do pliku (implikuje --download-db; nie nadpisuje istniejącego;
                         plik zawiera dane osobowe – trzymaj poza repozytorium albo na liście ignorowanych plików)
  --strict-tls           nie włączaj wymiany kluczy TLS_RSA na JVM (handshake prawdopodobnie się nie uda)
  --quiet                nie wypisuj każdej odebranej ramki
"""

private data class Options(
    val configPath: String = "domik-probe.local.json",
    val host: String? = null,
    val port: Int = ProtocolConstants.DEFAULT_PORT,
    val zones: List<Pair<UInt, List<UByte>>> = emptyList(),
    val listenSeconds: Int = 5,
    val strictTls: Boolean = false,
    val quiet: Boolean = false,
    val downloadDb: Boolean = false,
    val dbOnly: Boolean = false,
    val savePath: String? = null,
)

private class UsageException(message: String) : Exception(message)

private fun parseArgs(args: Array<String>): Options {
    var options = Options()
    var index = 0
    fun value(name: String): String = args.getOrNull(++index) ?: throw UsageException("brak wartości dla $name")
    while (index < args.size) {
        when (val arg = args[index]) {
            "--help", "-h" -> { print(USAGE); exitProcess(0) }
            "--config" -> options = options.copy(configPath = value(arg))
            "--host" -> options = options.copy(host = value(arg))
            "--port" -> options = options.copy(port = value(arg).toIntOrNull() ?: throw UsageException("zły port"))
            "--zones" -> options = options.copy(zones = options.zones + parseZones(value(arg)))
            "--listen" -> options = options.copy(
                listenSeconds = value(arg).toIntOrNull()?.takeIf { it >= 0 } ?: throw UsageException("zła liczba sekund"),
            )
            "--strict-tls" -> options = options.copy(strictTls = true)
            "--quiet" -> options = options.copy(quiet = true)
            "--download-db" -> options = options.copy(downloadDb = true)
            "--db-only" -> options = options.copy(downloadDb = true, dbOnly = true)
            "--save" -> options = options.copy(downloadDb = true, savePath = value(arg))
            else -> throw UsageException("nieznana opcja: $arg")
        }
        index++
    }
    return options
}

/** "1:0,1,2" → module 1, zones 0, 1, 2. */
private fun parseZones(text: String): Pair<UInt, List<UByte>> {
    val parts = text.split(':')
    if (parts.size != 2) throw UsageException("--zones: oczekiwano moduł:nr,nr,… (np. 1:0,1,2)")
    val module = parts[0].trim().toUIntOrNull() ?: throw UsageException("--zones: zły numer modułu")
    val numbers = parts[1].split(',').map {
        it.trim().toIntOrNull()?.takeIf { nr -> nr in 0..255 }?.toUByte() ?: throw UsageException("--zones: zły numer strefy")
    }
    return module to numbers
}

/** Reads "host" and "serial" from a flat JSON object. Error messages never contain file content. */
private fun readConfig(path: String): Pair<String?, SerialNumber> {
    val file = File(path)
    if (!file.isFile) throw UsageException("brak pliku konfiguracji: ${file.absolutePath} (domyślna nazwa: domik-probe.local.json; inną podaj w --config)")
    val text = file.readText()
    fun field(name: String): String? =
        Regex("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)?.trim()
    val serialText = field("serial") ?: throw UsageException("w pliku konfiguracji brak pola \"serial\"")
    val serial = SerialNumber.parseOrNull(serialText)
        ?: throw UsageException("pole \"serial\" w pliku konfiguracji nie jest poprawnym numerem hex")
    return field("host") to serial
}

private fun summary(message: ControllerMessage): String = when (message) {
    is StateSnapshot -> "stan zbiorczy ${message.command}: format ${message.format?.label ?: "-"}, " +
        "modułów ${message.modules.size}" + (if (message.isEmpty) ", PUSTY" else "") +
        (if (message.issues.isNotEmpty()) ", problemy: ${message.issues}" else "")
    is FileDownloadResponse -> "plik ${message.name}, ${message.content.size} B"
    is Ack -> "ACK dla ${message.command} (addr 0x${message.address.toString(16)}, dane ${message.payloadLength} B)"
    is ControllerMessage.Unknown -> "(bez parsera)"
    is ControllerMessage.Malformed -> "BŁĘDNY ładunek: ${message.error.message}"
    else -> message.toString()
}

fun main(args: Array<String>) {
    val options = try {
        parseArgs(args)
    } catch (error: UsageException) {
        System.err.println("Błąd: ${error.message}\n")
        System.err.print(USAGE)
        exitProcess(2)
    }
    val (configHost, serial) = try {
        readConfig(options.configPath)
    } catch (error: UsageException) {
        System.err.println("Błąd: ${error.message}")
        exitProcess(2)
    }
    val host = options.host ?: configHost ?: run {
        System.err.println("Błąd: brak hosta (pole \"host\" w konfiguracji albo --host)")
        exitProcess(2)
    }
    val ok = runBlocking { probe(ControllerEndpoint(host, options.port), serial, options) }
    exitProcess(if (ok) 0 else 1)
}

private suspend fun probe(endpoint: ControllerEndpoint, serial: SerialNumber, options: Options): Boolean =
    coroutineScope {
        println("Domik probe – TYLKO ODCZYT – sterownik $endpoint (numer seryjny z pliku, nie jest wypisywany)")
        if (!options.strictTls && JvmTlsCompatibility.allowRsaKeyExchange()) {
            println("[JVM] włączono zestawy TLS_RSA_* (wyłączone domyślnie w tym JDK) – tylko w tym procesie")
        }
        val connection = Connection(
            endpoint, serial, this,
            config = ConnectionConfig(maxReconnectAttempts = 0),
        )
        if (!options.quiet) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                connection.frames.collect { frame ->
                    println("  << $frame\n     → ${summary(ControllerMessage.decode(frame))}")
                }
            }
        }
        var allOk = true
        try {
            connection.connect()
            val reached = withTimeoutOrNull(30.seconds) {
                connection.state.first { it == ConnectionState.Ready || it is ConnectionState.Failed }
            }
            if (reached != ConnectionState.Ready) {
                val failed = reached as? ConnectionState.Failed
                println("[TLS] BŁĄD: ${failed?.problem ?: "przekroczony czas"} ${failed?.detail.orEmpty()}")
                return@coroutineScope false
            }
            val info = connection.transportInfo.value
            val pinOk = info?.certificateSha1 == CertificatePinning.CONTROLLER_CERTIFICATE_SHA1
            println("[TLS] ${info?.protocol} ${info?.cipherSuite}; SHA-1 certyfikatu ${info?.certificateSha1} – zgodny z przypiętym: ${if (pinOk) "tak" else "NIE"}")

            suspend fun read(
                label: String,
                frame: Frame,
                expecting: Set<CommandCode>,
                collectSeconds: Int = 0,
                accept: (Frame) -> Boolean = { true },
            ): RequestResult? {
                check(frame.command in READ_ONLY_COMMANDS) { "tylko odczyty: ${frame.command} niedozwolona" }
                println("[$label] >> $frame")
                return try {
                    connection.request(frame, expecting, 5.seconds, collectSeconds.seconds, accept).also {
                        println("[$label] odpowiedzi: ${it.responses.size}, ACK: ${if (it.acknowledged) "tak" else "nie"}")
                    }
                } catch (error: RequestTimeoutException) {
                    allOk = false
                    println("[$label] BŁĄD: brak odpowiedzi (ACK: ${if (error.acknowledged) "tak" else "nie"})")
                    null
                } catch (error: ConnectionException) {
                    allOk = false
                    println("[$label] BŁĄD: ${error.problem} ${error.message}")
                    null
                }
            }

            val standardReads = !options.dbOnly
            if (standardReads) {
                read("ping", Request.ping(serial), setOf(CommandCode.PING))

                read("firmware", Request.firmwareVersion(serial), setOf(CommandCode.FIRMWARE_VERSION))?.let { result ->
                    val message = ControllerMessage.decode(result.responses.first())
                    println("[firmware] ${(message as? FirmwareVersion)?.text ?: summary(message)}")
                }
            }

            var configToken: ConfigChecksum? = null
            read("CRC", Request.configChecksum(serial), setOf(CommandCode.CONFIG_CHECKSUM))?.let { result ->
                val message = ControllerMessage.decode(result.responses.first())
                configToken = message as? ConfigChecksum
                println("[CRC] ${configToken?.hexString ?: summary(message)}")
            }

            if (standardReads) read(
                "stan", Request.getState(serial, StateRequestVariant.NEW),
                setOf(CommandCode.GET_STATE, CommandCode.STATE_NEW, CommandCode.STATE_V3),
            )?.let { result ->
                val message = ControllerMessage.decode(result.responses.first())
                println("[stan] ${summary(message)}")
                (message as? StateSnapshot)?.modules?.forEach { module ->
                    val heaters = module.heaters.orEmpty().withIndex()
                        .filter { it.value != HeaterFlag.NoZone }
                        .joinToString { "${it.index}=${it.value}" }
                    println("[stan]   moduł ${module.module}: format ${module.format.label}, włączone wyjścia ${module.onOutputs}, strefy: ${heaters.ifEmpty { "-" }}")
                }
            }

            for ((module, numbers) in if (standardReads) options.zones else emptyList()) {
                for (nr in numbers) {
                    read(
                        "0x6B $module:$nr", Request.heaterState(serial, module, nr), setOf(CommandCode.HEATER_STATE),
                    ) { it.address == module && it.payload.size > 0 && it.payload[0] == nr }?.let { result ->
                        val state = ControllerMessage.decode(result.responses.first())
                        if (state is HeaterState) {
                            println(
                                "[0x6B $module:$nr] aktualna ${state.currentTemperature} °C, zadana ${state.setpoint} °C, " +
                                    "profil ${state.profile}, grzeje: ${if (state.isHeating) "tak" else "nie"}",
                            )
                        } else {
                            println("[0x6B $module:$nr] ${summary(state)}")
                        }
                    }
                }
            }

            if (standardReads) read(
                "temperatury", Request.temperatureReadout(serial), setOf(CommandCode.TEMPERATURE_REPORT), collectSeconds = 2,
            )?.let { result ->
                result.responses.map(ControllerMessage::decode).forEach { message ->
                    if (message is TemperatureReport) {
                        println(
                            "[temperatury]   moduł ${message.module}, poziom ${message.levelId}, pokój ${message.roomId}, " +
                                "czujnik ${message.sensorNumber ?: "-"}: ${message.temperature} °C",
                        )
                    } else {
                        println("[temperatury]   ${summary(message)}")
                    }
                }
            }

            if (options.downloadDb && !downloadDatabase(connection, serial, configToken, options)) allOk = false

            if (standardReads && options.listenSeconds > 0) {
                println("[nasłuch] ${options.listenSeconds} s zdarzeń spontanicznych…")
                delay(options.listenSeconds.seconds)
            }
            val final = connection.state.value
            if (final != ConnectionState.Ready) {
                allOk = false
                println("[koniec] połączenie nie jest już aktywne: $final")
            }
            allOk
        } catch (error: ConnectionException) {
            println("BŁĄD połączenia: ${error.problem} ${error.message}")
            false
        } catch (error: Exception) {
            println("BŁĄD: ${ConnectionErrors.classify(error)} ${error.message}")
            false
        } finally {
            connection.disconnect()
            coroutineContext.cancelChildren()
            println("Rozłączono.")
        }
    }

/**
 * Downloads the configuration database (0x212, the fixed path /logs/database-last.db3), optionally saves it, parses
 * it and prints a summary. Returns false when something failed.
 */
private suspend fun downloadDatabase(
    connection: Connection,
    serial: SerialNumber,
    configToken: ConfigChecksum?,
    options: Options,
): Boolean {
    val request = Request.fileDownload(serial, ConfigDownloader.DATABASE_PATH)
    check(request.command in READ_ONLY_COMMANDS) { "tylko odczyty: ${request.command} niedozwolona" }
    println("[baza] >> $request")
    val downloaded = try {
        ConfigDownloader(connection).download()
    } catch (error: ConfigDownloadException) {
        println("[baza] BŁĄD: odpowiedź nie jest oczekiwanym plikiem (${error.reason}): ${error.message}")
        return false
    } catch (error: RequestTimeoutException) {
        println("[baza] BŁĄD: brak odpowiedzi w 30 s (ACK: ${if (error.acknowledged) "tak" else "nie"})")
        return false
    } catch (error: ConnectionException) {
        println("[baza] BŁĄD: ${error.problem} ${error.message}")
        return false
    }
    println("[baza] pobrano ${downloaded.bytes.size} B (${downloaded.fileName})")
    val fileCrc = Crc16Arc.checksum(downloaded.bytes)
    val token = configToken?.value
    println(
        "[baza] CRC-16/ARC pliku: 0x${fileCrc.toString(16)}; token 0x1C8: " +
            (token?.let { "0x${it.toString(16)} – zgodny z CRC pliku: ${if (it == fileCrc) "tak" else "nie"}" } ?: "nie odczytano") +
            " (informacyjnie)",
    )

    var ok = true
    options.savePath?.let { path -> if (!saveDatabase(path, downloaded.bytes)) ok = false }

    try {
        val config = HomeConfigParser().parse(downloaded.bytes)
        println("[baza] UWAGA: poniższe podsumowanie zawiera nazwy pomieszczeń i urządzeń z Twojego domu – nie publikuj go.")
        printConfigSummary(config)
    } catch (error: ConfigParseException) {
        println("[baza] BŁĄD: baza nie jest czytelną konfiguracją sterownika: ${error.message}")
        ok = false
    }
    return ok
}

/** Writes [bytes] to a new file (never overwrites), readable only by the owner where the file system allows it. */
private fun saveDatabase(path: String, bytes: ByteArray): Boolean {
    val file = File(path).absoluteFile
    return try {
        file.parentFile?.mkdirs()
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (error: UnsupportedOperationException) {
            // not a POSIX file system
        }
        println("[baza] zapisano do ${file.path}")
        println("[baza] UWAGA: plik zawiera dane osobowe (m.in. nazwy pomieszczeń i urządzeń z Twojego domu) – nie dodawaj go do repozytorium ani nie publikuj.")
        true
    } catch (error: java.nio.file.FileAlreadyExistsException) {
        println("[baza] BŁĄD: plik ${file.path} już istnieje – nie nadpisuję go (podaj inną nazwę)")
        false
    } catch (error: IOException) {
        println("[baza] BŁĄD zapisu pliku ${file.path}: ${error.javaClass.simpleName}")
        false
    }
}
