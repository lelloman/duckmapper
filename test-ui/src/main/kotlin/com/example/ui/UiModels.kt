package com.example.ui

data class UiUser(
    val id: String,
    val name: String,
    val email: String,
    val address: UiAddress,
    val status: UiStatus
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

// Enum with same values as DomainStatus - bidirectional mapping works
enum class UiStatus {
    PENDING,
    ACTIVE
}

// Enum with more values - can receive from DomainStatus, but not map back
enum class UiStatusExtended {
    PENDING,
    ACTIVE,
    INACTIVE,
    ARCHIVED
}

// Target class for interface mapping
data class SimpleItem(
    val id: String,
    val name: String
)

// Interface for @DuckImplement testing - generates implementation class
interface UiDisplayable {
    val title: String
    val description: String
}

// Sealed interface for connection state mapping (superset - has extra Reconnecting state)
sealed interface UiConnectionState {
    data object Disconnected : UiConnectionState
    data object Connecting : UiConnectionState
    data object Reconnecting : UiConnectionState  // Extra state not in Domain
    data class Connected(val deviceId: Int, val serverVersion: String) : UiConnectionState
    data class Error(val message: String) : UiConnectionState
}
