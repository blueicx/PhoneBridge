package com.phonebridge

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The pairing QR pins the full X.509 DER certificate, including self-signed LAN certificates. */
object PairingTls {
    fun derFingerprint(der: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(der).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun matchesCertificate(der: ByteArray, expected: String?): Boolean =
        PairingProtocol.isValidFingerprint(expected) && derFingerprint(der).equals(expected!!.trim(), ignoreCase = true)

    fun configure(builder: OkHttpClient.Builder, expected: String): OkHttpClient.Builder {
        require(PairingProtocol.isValidFingerprint(expected)) { "certificate fingerprint is invalid; pair again" }
        val trustManager = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("server certificate is missing")
                leaf.checkValidity()
                if (!matchesCertificate(leaf.encoded, expected)) throw CertificateException("server certificate changed; pair again")
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("client certificates are not accepted")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return builder.sslSocketFactory(context.socketFactory, trustManager)
    }
}
