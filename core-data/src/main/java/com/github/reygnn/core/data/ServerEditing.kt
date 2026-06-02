package com.github.reygnn.core.data

/** Editor form for one server profile. [index] = null means "new server". */
data class ServerForm(
    val index: Int? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val workingDir: String = "",
)

/** Pre-fills a [ServerForm] from an existing profile at [index] for editing. */
fun ServerProfile.toForm(index: Int): ServerForm =
    ServerForm(index, name, host, port.toString(), username, workingDir)

/** Validation outcome of [ServerForm.validate]. */
sealed interface ServerFormResult {
    /**
     * The built profile, with the host-key pin carried over while host+port are
     * unchanged ([ServerProfile.pinToKeepFor]).
     */
    data class Valid(val profile: ServerProfile) : ServerFormResult

    /** A required field is blank (working dir only counts when required). */
    data object EmptyFields : ServerFormResult

    /** Port is not an integer in 1..65535. */
    data object InvalidPort : ServerFormResult
}

/**
 * Validates the form and, on success, builds the [ServerProfile]. [existing] is
 * the profile currently at the edited index (null for a new profile), used to
 * preserve the host-key pin. [requireWorkingDir] is false for Prodder, which has
 * no working dir.
 *
 * Shared by all three apps' SettingsViewModels so the validation rules and
 * pin-preservation can't drift; mapping the error cases to a user-facing string
 * stays in each app's ViewModel.
 */
fun ServerForm.validate(existing: ServerProfile?, requireWorkingDir: Boolean): ServerFormResult {
    if (name.isBlank() || host.isBlank() || username.isBlank() ||
        (requireWorkingDir && workingDir.isBlank())
    ) {
        return ServerFormResult.EmptyFields
    }
    val portNumber = port.toIntOrNull()
    if (portNumber == null || portNumber !in 1..65535) return ServerFormResult.InvalidPort
    val trimmedHost = host.trim()
    return ServerFormResult.Valid(
        ServerProfile(
            name = name.trim(),
            host = trimmedHost,
            port = portNumber,
            username = username.trim(),
            workingDir = workingDir.trim(),
            knownHostFingerprint = existing.pinToKeepFor(trimmedHost, portNumber),
        ),
    )
}

/** Inserts [profile] at the end (new, [index] == null) or replaces it at [index]. */
fun List<ServerProfile>.upsert(index: Int?, profile: ServerProfile): List<ServerProfile> =
    toMutableList().also { if (index == null) it.add(profile) else it[index] = profile }
