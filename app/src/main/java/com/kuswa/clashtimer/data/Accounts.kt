package com.kuswa.clashtimer.data

import com.kuswa.clashtimer.core.AppState
import kotlinx.serialization.Serializable

@Serializable
data class Account(val id: String, val name: String)

@Serializable
data class AccountList(val accounts: List<Account> = emptyList()) {
    fun encode(): String = AppState.JSON.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): AccountList =
            text?.let { runCatching { AppState.JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: AccountList()
    }
}
