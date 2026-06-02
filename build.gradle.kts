plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library)     apply false
    alias(libs.plugins.kotlin.compose)      apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Git-diff-aware German-comment linter. Flags comment lines added on/after a
// fixed cutoff date (the "Stichtag", default 2026-06-01 — the project's initial
// commit, so effectively the whole tree) that contain German prose. sshTools
// keeps all source comments English (see CLAUDE.md); source before the cutoff is
// never swept.
//
// Run via `./gradlew checkGermanComments` or invoke the script directly.
// Override the cutoff with CHECK_CUTOFF, or pin an explicit base with CHECK_BASE.
tasks.register<Exec>("checkGermanComments") {
    group = "verification"
    description = "Fails if comments added since the cutoff date contain German."
    workingDir = rootDir
    commandLine = listOf("bash", "tools/check-german-comments.sh")
}
