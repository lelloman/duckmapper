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

// Interface as source - common pattern for API responses
interface Identifiable {
    val id: String
    val name: String
}

// Interface for @DuckWrap testing - source must implement target interface
interface DomainDisplayable {
    val title: String
    val description: String
}

// Concrete class for @DuckWrap testing - implements the interface
// @DuckWrap creates a wrapper that hides implementation details
data class DomainItem(
    override val title: String,
    override val description: String,
    val extraData: String  // Additional property not in interface (hidden by wrapper)
) : DomainDisplayable

// Concrete class for @DuckImplement testing - does NOT implement interface
// @DuckImplement generates an implementation by copying values
data class DomainDetails(
    val title: String,
    val description: String,
    val metadata: String
)
