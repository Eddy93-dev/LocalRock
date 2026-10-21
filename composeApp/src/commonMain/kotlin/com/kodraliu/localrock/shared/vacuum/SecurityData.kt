package com.kodraliu.localrock.shared.vacuum

import com.kodraliu.localrock.shared.protocol.secureRandomBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.kotlincrypto.hash.md.MD5


data class SecurityData(
    val endpoint: String,
    val nonce: ByteArray,
) {
    val nonceHex: String by lazy { nonce.toHexString() }

    override fun equals(other: Any?): Boolean =
        other is SecurityData && other.endpoint == endpoint && other.nonce.contentEquals(nonce)

    override fun hashCode(): Int = 31 * endpoint.hashCode() + nonce.contentHashCode()
}

@OptIn(ExperimentalEncodingApi::class)
fun createSecurityData(rriotKey: String): SecurityData {
    val md = MD5().digest(rriotKey.encodeToByteArray())
    val endpointBytes = md.copyOfRange(8, 14)
    return SecurityData(
        endpoint = Base64.encode(endpointBytes),
        nonce = secureRandomBytes(16),
    )
}
