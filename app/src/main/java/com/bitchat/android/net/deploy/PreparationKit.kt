package com.bitchat.android.net.deploy

import com.bitchat.android.ui.network.DeployerPackage
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PreparationKit
private constructor(val files: Map<String, File>, val hashes: Map<String, String>) {
    companion object {
        val required =
            setOf(
                "server-binaries/linux-amd64/qwdtt-server",
                "server-binaries/linux-amd64/panchan-admission",
                "deploy/panchan-admission.service",
                "deploy/remote-admission.sh",
                "deploy/remote-qwdtt.sh",
            )

        suspend fun read(input: InputStream, expected: String, directory: File): PreparationKit {
            check(directory.isDirectory)
            val archive = File(directory, "kit.zip")
            archive.outputStream().use { DeployerPackage.copyVerified(input, it, expected) }
            val files = linkedMapOf<String, File>()
            val hashes = linkedMapOf<String, String>()
            ZipFile(archive).use { zip ->
                val manifestEntry =
                    checkNotNull(zip.getEntry("panchan-deployer/deployer-SHA256SUMS"))
                require(manifestEntry.size in 1..262144)
                val manifest =
                    zip.getInputStream(manifestEntry).use { source ->
                        val buffer = ByteArray(262145)
                        var count = 0
                        while (count < buffer.size) {
                            val n = source.read(buffer, count, buffer.size - count)
                            if (n < 0) break
                            require(n > 0)
                            count += n
                        }
                        buffer.copyOf(count)
                    }
                require(manifest.size <= 262144)
                manifest
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach {
                        val parts = it.split("  ", limit = 2)
                        require(
                            parts.size == 2 &&
                                parts[0].matches(Regex("[a-f0-9]{64}")) &&
                                parts[1] !in hashes
                        )
                        hashes[parts[1]] = parts[0]
                    }
                required.forEachIndexed { index, name ->
                    currentCoroutineContext().ensureActive()
                    val entry = checkNotNull(zip.getEntry("panchan-deployer/$name"))
                    require(entry.size in 1..67_108_864)
                    val file = File(directory, "artifact-$index")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    zip.getInputStream(entry).use { source ->
                        file.outputStream().use { target ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 67_108_864)
                                digest.update(buffer, 0, count)
                                target.write(buffer, 0, count)
                            }
                        }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == hashes[name])
                    files[name] = file
                }
            }
            archive.delete()
            return PreparationKit(files, hashes)
        }
    }

    fun file(name: String) = checkNotNull(files[name])

    fun hash(name: String) = checkNotNull(hashes[name])
}

object ServerPreparation {
    const val inventoryCommand =
        "set -eu; . /etc/os-release; printf 'OS=%s\\nARCH=%s\\nUID=%s\\nFREE_KB=%s\\n' \"\$ID\" \"\$(uname -m)\" \"\$(id -u)\" \"\$(df -Pk /opt | awk 'NR==2 {print \$4}')\""

    fun validateInventory(response: String) {
        val entries =
            response
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { it.split('=', limit = 2) }
                .toList()
        require(
            entries.all { it.size == 2 } && entries.map { it[0] }.distinct().size == entries.size
        )
        val values = entries.associate { it[0] to it[1] }
        check(values["OS"] == "debian") { "Нужен Debian." }
        check(values["ARCH"] == "x86_64") { "Нужен сервер x86_64." }
        check(values["UID"] == "0") { "Нужен вход root." }
        check((values["FREE_KB"]?.toLongOrNull() ?: 0) >= 262144) {
            "Нужно 256 МБ свободного места."
        }
    }

    fun run(connection: PreparationConnection, kit: PreparationKit, progress: (String) -> Unit) {
        progress("Проверяем Debian, права и свободное место…")
        validateInventory(connection.execute(inventoryCommand))
        connection.execute("install -d -m 700 /opt/panchan/staging")
        val admission = "server-binaries/linux-amd64/panchan-admission"
        val unit = "deploy/panchan-admission.service"
        val uploads =
            listOf(
                "server-binaries/linux-amd64/qwdtt-server" to "qwdtt-server",
                admission to "admission-${kit.hash(admission)}",
                unit to "unit-${kit.hash(unit)}",
            )
        uploads.forEachIndexed { index, (source, name) ->
            progress("Передаём проверенные файлы: ${index+1}/${uploads.size}…")
            connection.upload(kit.file(source), name)
        }
        progress("Устанавливаем службу с проверкой готовности и откатом…")
        val sums =
            uploads.take(1).joinToString("\n") { (source, name) ->
                kit.hash(source) + "  " + name
            } + "\n"
        val prelude =
            "set -euo pipefail\ncd /opt/panchan/staging\ncat > SHA256SUMS <<'PANCHAN_SUMS'\n" +
                sums +
                "PANCHAN_SUMS\nsha256sum -c SHA256SUMS >/dev/null\nchmod 700 qwdtt-server\nchmod 600 SHA256SUMS\n"
        val script = prelude.toByteArray() + kit.file("deploy/remote-admission.sh").readBytes()
        connection.execute("bash -s -- ${kit.hash(admission)} ${kit.hash(unit)}", script)
        progress("Проверяем установленную службу…")
        val response = connection.execute("/opt/panchan/admission/current/panchan-admission status")
        check(
            com.google.gson.JsonParser.parseString(response).asJsonObject.get("version")?.asInt == 1
        )
        progress("Запускаем qWDTT, WireGuard, Raw и закрытую панель управления…")
        val activation = connection.execute(
            "bash -s -- ${kit.hash("server-binaries/linux-amd64/qwdtt-server")}",
            kit.file("deploy/remote-qwdtt.sh").readBytes(),
        )
        check(activation.trim() == "QWDTT_READY")

    }
}
