package com.github.reygnn.core.data

/**
 * Drei-Zustands-Repräsentation für „ist die App konfiguriert?".
 *
 * Vorher trug das ein `Boolean?`, mit `null` als Loading-Sentinel — das
 * funktioniert, aber der `null`-Vertrag ist subtil und musste an jeder
 * Verzweigung kommentiert werden. Diese sealed interface macht den
 * Loading-Zustand explizit.
 */
sealed interface ConfigState {
    data object Loading : ConfigState
    data object Unconfigured : ConfigState
    data object Configured : ConfigState
}
