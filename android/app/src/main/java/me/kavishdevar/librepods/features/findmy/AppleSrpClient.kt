package me.kavishdevar.librepods.features.findmy

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Apple's icloud.com login uses RFC 5054 SRP-6a with SHA-256 and a modified
 * password derivation (the username is omitted from x). This implementation
 * intentionally exposes only the two values needed by the web login flow.
 */
internal class AppleSrpClient(
    private val username: String,
    password: String,
    private val privateEphemeral: ByteArray = randomPrivateEphemeral(),
) {
    data class Start(val publicA: ByteArray)

    data class Proof(
        val m1: ByteArray,
        val m2: ByteArray,
    )

    private val passwordBytes = password.toByteArray(Charsets.UTF_8)
    private val privateA = BigInteger(1, privateEphemeral)
    private val publicA = GENERATOR.modPow(privateA, MODULUS)

    fun start(): Start = Start(publicA.toUnsignedByteArray())

    fun processChallenge(
        salt: ByteArray,
        iterations: Int,
        publicBBytes: ByteArray,
    ): Proof {
        require(iterations > 0) { "Invalid SRP iteration count" }
        val publicB = BigInteger(1, publicBBytes)
        require(publicB.mod(MODULUS) != BigInteger.ZERO) { "Invalid SRP server value" }

        val passwordHash = sha256(passwordBytes)
        val derivedPassword = pbkdf2HmacSha256(passwordHash, salt, iterations, 32)
        passwordHash.fill(0)

        // python-srp's no_username_in_x() still hashes ':' before the password.
        val xInner = sha256(byteArrayOf(':'.code.toByte()), derivedPassword)
        derivedPassword.fill(0)
        val x = BigInteger(1, sha256(salt, xInner))
        xInner.fill(0)

        val width = MODULUS.toUnsignedByteArray().size
        val k = BigInteger(1, sha256(MODULUS.padded(width), GENERATOR.padded(width)))
        val u = BigInteger(1, sha256(publicA.padded(width), publicB.padded(width)))
        require(u != BigInteger.ZERO) { "Invalid SRP scrambling parameter" }

        val verifier = GENERATOR.modPow(x, MODULUS)
        val base = publicB.subtract(k.multiply(verifier)).mod(MODULUS)
        val exponent = privateA.add(u.multiply(x))
        val sessionSecret = base.modPow(exponent, MODULUS)
        val sessionKey = sha256(sessionSecret.toUnsignedByteArray())

        val hN = sha256(MODULUS.toUnsignedByteArray())
        val hG = sha256(GENERATOR.padded(width))
        val hNxG = ByteArray(hN.size) { index ->
            (hN[index].toInt() xor hG[index].toInt()).toByte()
        }
        val m1 = sha256(
            hNxG,
            sha256(username.toByteArray(Charsets.UTF_8)),
            salt,
            publicA.toUnsignedByteArray(),
            publicB.toUnsignedByteArray(),
            sessionKey,
        )
        val m2 = sha256(publicA.toUnsignedByteArray(), m1, sessionKey)
        sessionKey.fill(0)
        return Proof(m1 = m1, m2 = m2)
    }

    fun clear() {
        passwordBytes.fill(0)
        privateEphemeral.fill(0)
    }

    private companion object {
        private val MODULUS = BigInteger(
            """
            AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4
            A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60
            95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF
            747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907
            8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861
            60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB
            FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73
            """.trimIndent().replace("\n", ""),
            16,
        )
        private val GENERATOR = BigInteger.valueOf(2)

        private fun randomPrivateEphemeral(): ByteArray = ByteArray(256).also {
            SecureRandom().nextBytes(it)
            it[0] = (it[0].toInt() or 0x80).toByte()
        }

        private fun sha256(vararg values: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run {
                values.forEach(::update)
                digest()
            }

        private fun pbkdf2HmacSha256(
            password: ByteArray,
            salt: ByteArray,
            iterations: Int,
            outputLength: Int,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val hashLength = mac.macLength
            val blocks = (outputLength + hashLength - 1) / hashLength
            val output = ByteArray(blocks * hashLength)
            var outputOffset = 0
            for (block in 1..blocks) {
                var u = mac.doFinal(salt + ByteBuffer.allocate(4).putInt(block).array())
                val accumulator = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (index in accumulator.indices) {
                        accumulator[index] =
                            (accumulator[index].toInt() xor u[index].toInt()).toByte()
                    }
                }
                accumulator.copyInto(output, outputOffset)
                outputOffset += hashLength
                u.fill(0)
                accumulator.fill(0)
            }
            return output.copyOf(outputLength).also { output.fill(0) }
        }

        private fun BigInteger.toUnsignedByteArray(): ByteArray {
            val encoded = toByteArray()
            return if (encoded.size > 1 && encoded[0] == 0.toByte()) {
                encoded.copyOfRange(1, encoded.size)
            } else {
                encoded
            }
        }

        private fun BigInteger.padded(width: Int): ByteArray {
            val source = toUnsignedByteArray()
            require(source.size <= width) { "SRP value exceeds group width" }
            return ByteArray(width).also {
                source.copyInto(it, width - source.size)
            }
        }
    }
}
