package com.example.domain

data class DomainUser(
    val id: String,
    val name: String,
    val email: String,
    val address: DomainAddress
)

data class DomainAddress(
    val street: String,
    val city: String,
    val zipCode: String
)

data class DomainTeam(
    val id: String,
    val name: String,
    val members: List<DomainUser>
)

data class DomainId(val value: String)

data class DomainCache(
    val entries: Map<DomainId, DomainUser>
)
