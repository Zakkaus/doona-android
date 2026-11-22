package dev.doona.android.vpn

import java.io.File

/** The payloads in `contracts/doona-core/`, found from the test's working directory up. */
internal object ContractFixtures {
    val dir: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { it.resolve("contracts/doona-core") }
        .first { it.isDirectory }

    fun read(name: String): String = dir.resolve(name).readText()
}
