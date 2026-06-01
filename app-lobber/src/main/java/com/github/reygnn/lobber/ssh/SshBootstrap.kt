package com.github.reygnn.lobber.ssh

interface SshBootstrap {
    suspend fun pushPublicKey(
        host: String, port: Int, username: String,
        password: String, publicKeyLine: String,
    ): String
    suspend fun verifyPubkeyAuth(config: SshConfig)
}
