package com.example.ui

data class UiUser(
    val id: String,
    val name: String,
    val email: String,
    val address: UiAddress
)

data class UiAddress(
    val street: String,
    val city: String,
    val zipCode: String
)

data class UiTeam(
    val id: String,
    val name: String,
    val members: List<UiUser>
)

data class UiId(val value: String)

data class UiCache(
    val entries: Map<UiId, UiUser>
)
