package dev.codex.hfutcampus

/** Current account's balance after pending settlements, held in memory only. */
data class BalanceSnapshot(val text: String, val expiresElapsed: Long)
