package me.kavishdevar.librepods.features.findmy

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.Base64

class AppleSrpClientTest {
    @Test
    fun matchesPythonSrpRfc5054Vector() {
        val privateA = ByteArray(256).also { it[0] = 0x80.toByte() }
        val client = AppleSrpClient(
            username = "test@example.com",
            password = "test-password",
            privateEphemeral = privateA,
        )

        assertArrayEquals(decode(EXPECTED_A), client.start().publicA)
        val proof = client.processChallenge(
            salt = decode("0samK84bcBmkVsswOpZbZg=="),
            iterations = 20_433,
            publicBBytes = decode(SERVER_B),
        )
        assertArrayEquals(decode("ajx2dpby3GG3AXUEn7GRVyjLFQSKjzxyWIZ97NwqU1g="), proof.m1)
        assertArrayEquals(decode("UsN2aeSLZ8sR6+ELUh8cS8R2h3OJRDl9DGs7L/uvsLw="), proof.m2)
    }

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val EXPECTED_A =
            "CQK9CmdTUgJBr/YHyDEPvgktCndvj6Fan1lzTuqO6+jeyLB8qxoFQcgydmQk3ZxiDOx9ft1Nd3vqNV28z//+bA+xtFG+gs77Ucuowb6dQu1dRPNhNQplOzNT5LJxEKX0TEoeCRTc42k0cAbcY/Nfsn9ryWHlDRGP0rXtVEjx/603D/C7gctYnsDrB9/vwGwzET1KaZDw1OKLaOPy63B9ejZy6cheKJFC2BewSD+3era8X864emLARs9gcTrkBfD/WLwpP7wE2wLEU/DGGC8D94gZf098Sgb30hiies4tcSEGjB0nj7nOkEY7g4hKp1j1s84BfGC2/C3KoUUzF0Ieuw=="
        const val SERVER_B =
            "STVHcWTN9YOYn4IgtIJ6UPdPbvzvL+zza/l+6yUHUtdEyxwzpB78y8wqZ8QWSbVqjBcpl32iEA4T3nYp0LWZ5hD3r3yIJFloXvX0kpBJkr+Nh8EfHuW1V50A8riH6VWyuJ8m3JmOO7/xkNgP7je8GMpt/5f/7qE3AOj73e3JR0fzQ7IopdU0tlyVX0tD7T6wCyHS52GJWDdq1I2bgzurIK2/ZjR/Hwzd/67oFQPtKQgjrSRaKo5MJEfDP7C9wOlXsZqbb7igX6PeZRWrfl+iQFaA/FVeWSngB07ja3wOryY9GsYO06ELGOaQ+MpsT7mouqrGTfOJ0OMh9EgrkJEM6w=="
    }
}
