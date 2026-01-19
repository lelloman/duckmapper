package com.example.domain

data class DomainUser(
    val id: String,
    val name: String,
    val email: String,
    val address: DomainAddress,
    val status: DomainStatus
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

// Enum with subset of values (PENDING, ACTIVE) - can map TO larger enum
enum class DomainStatus {
    PENDING,
    ACTIVE
}

// Enum with more values - can only map FROM, not TO DomainStatus
enum class DomainStatusExtended {
    PENDING,
    ACTIVE,
    INACTIVE,
    ARCHIVED
}
