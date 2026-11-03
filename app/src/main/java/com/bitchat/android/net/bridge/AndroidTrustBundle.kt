package com.bitchat.android.net.bridge

import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Uses Android's trusted issuers instead of assuming a legacy filesystem CA directory. */
object AndroidTrustBundle {
    fun systemCertificates(): List<X509Certificate> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
    }
    fun configureProcess(builder: ProcessBuilder, directory: File, certificates: List<X509Certificate> = systemCertificates()) {
        val file = File(directory, "android-trust.pem")
        write(file, certificates)
        val extra = File(directory, "no-extra-roots")
        check((extra.isDirectory || extra.mkdirs()) && extra.listFiles()?.isEmpty() == true) { "Unexpected extra trust roots" }
        builder.environment()["SSL_CERT_FILE"] = file.absolutePath
        builder.environment()["SSL_CERT_DIR"] = extra.absolutePath
    }
    fun write(file: File, certificates: List<X509Certificate>) {
        require(certificates.isNotEmpty() && certificates.size <= 1024) { "Trusted certificate store is unavailable" }
        val pem = buildString {
            certificates.forEach { cert ->
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(cert.encoded))
                append("\n-----END CERTIFICATE-----\n")
            }
        }.toByteArray(Charsets.US_ASCII)
        require(pem.size <= 2 * 1024 * 1024)
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Trust directory unavailable" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(pem); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
}
