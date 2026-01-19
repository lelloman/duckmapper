package com.example.app

import com.example.domain.*
import com.example.ui.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MappersTest {

    @Test
    fun `basic mapping - DomainAddress to UiAddress`() {
        val domain = DomainAddress(
            street = "123 Main St",
            city = "Springfield",
            zipCode = "12345"
        )

        val ui = domain.toUiAddress()

        assertEquals("123 Main St", ui.street)
        assertEquals("Springfield", ui.city)
        assertEquals("12345", ui.zipCode)
    }

    @Test
    fun `basic mapping - UiAddress to DomainAddress`() {
        val ui = UiAddress(
            street = "456 Oak Ave",
            city = "Shelbyville",
            zipCode = "67890"
        )

        val domain = ui.toDomainAddress()

        assertEquals("456 Oak Ave", domain.street)
        assertEquals("Shelbyville", domain.city)
        assertEquals("67890", domain.zipCode)
    }

    @Test
    fun `enum mapping - DomainStatus to UiStatus`() {
        assertEquals(UiStatus.PENDING, DomainStatus.PENDING.toUiStatus())
        assertEquals(UiStatus.ACTIVE, DomainStatus.ACTIVE.toUiStatus())
    }

    @Test
    fun `enum mapping - UiStatus to DomainStatus`() {
        assertEquals(DomainStatus.PENDING, UiStatus.PENDING.toDomainStatus())
        assertEquals(DomainStatus.ACTIVE, UiStatus.ACTIVE.toDomainStatus())
    }

    @Test
    fun `nested type mapping - DomainUser to UiUser`() {
        val domain = DomainUser(
            id = "user-1",
            name = "John Doe",
            email = "john@example.com",
            address = DomainAddress("123 Main St", "Springfield", "12345"),
            status = DomainStatus.ACTIVE
        )

        val ui = domain.toUiUser()

        assertEquals("user-1", ui.id)
        assertEquals("John Doe", ui.name)
        assertEquals("john@example.com", ui.email)
        assertEquals("123 Main St", ui.address.street)
        assertEquals("Springfield", ui.address.city)
        assertEquals("12345", ui.address.zipCode)
        assertEquals(UiStatus.ACTIVE, ui.status)
    }

    @Test
    fun `nested type mapping - UiUser to DomainUser`() {
        val ui = UiUser(
            id = "user-2",
            name = "Jane Doe",
            email = "jane@example.com",
            address = UiAddress("456 Oak Ave", "Shelbyville", "67890"),
            status = UiStatus.PENDING
        )

        val domain = ui.toDomainUser()

        assertEquals("user-2", domain.id)
        assertEquals("Jane Doe", domain.name)
        assertEquals("jane@example.com", domain.email)
        assertEquals("456 Oak Ave", domain.address.street)
        assertEquals("Shelbyville", domain.address.city)
        assertEquals("67890", domain.address.zipCode)
        assertEquals(DomainStatus.PENDING, domain.status)
    }

    @Test
    fun `list mapping - DomainTeam to UiTeam`() {
        val domain = DomainTeam(
            id = "team-1",
            name = "Engineering",
            members = listOf(
                DomainUser("u1", "Alice", "alice@example.com", DomainAddress("1 St", "City1", "11111"), DomainStatus.ACTIVE),
                DomainUser("u2", "Bob", "bob@example.com", DomainAddress("2 St", "City2", "22222"), DomainStatus.PENDING)
            )
        )

        val ui = domain.toUiTeam()

        assertEquals("team-1", ui.id)
        assertEquals("Engineering", ui.name)
        assertEquals(2, ui.members.size)
        assertEquals("Alice", ui.members[0].name)
        assertEquals("Bob", ui.members[1].name)
        assertEquals("1 St", ui.members[0].address.street)
        assertEquals(UiStatus.ACTIVE, ui.members[0].status)
        assertEquals(UiStatus.PENDING, ui.members[1].status)
    }

    @Test
    fun `list mapping - UiTeam to DomainTeam`() {
        val ui = UiTeam(
            id = "team-2",
            name = "Design",
            members = listOf(
                UiUser("u3", "Carol", "carol@example.com", UiAddress("3 St", "City3", "33333"), UiStatus.ACTIVE)
            )
        )

        val domain = ui.toDomainTeam()

        assertEquals("team-2", domain.id)
        assertEquals("Design", domain.name)
        assertEquals(1, domain.members.size)
        assertEquals("Carol", domain.members[0].name)
        assertEquals(DomainStatus.ACTIVE, domain.members[0].status)
    }

    @Test
    fun `map mapping with key and value transformation - DomainCache to UiCache`() {
        val domain = DomainCache(
            entries = mapOf(
                DomainId("id-1") to DomainUser("u1", "Alice", "alice@example.com", DomainAddress("1 St", "City1", "11111"), DomainStatus.ACTIVE),
                DomainId("id-2") to DomainUser("u2", "Bob", "bob@example.com", DomainAddress("2 St", "City2", "22222"), DomainStatus.PENDING)
            )
        )

        val ui = domain.toUiCache()

        assertEquals(2, ui.entries.size)
        val key1 = UiId("id-1")
        val key2 = UiId("id-2")
        assertEquals("Alice", ui.entries[key1]?.name)
        assertEquals("Bob", ui.entries[key2]?.name)
    }

    @Test
    fun `map mapping with key and value transformation - UiCache to DomainCache`() {
        val ui = UiCache(
            entries = mapOf(
                UiId("id-3") to UiUser("u3", "Carol", "carol@example.com", UiAddress("3 St", "City3", "33333"), UiStatus.ACTIVE)
            )
        )

        val domain = ui.toDomainCache()

        assertEquals(1, domain.entries.size)
        val key = DomainId("id-3")
        assertEquals("Carol", domain.entries[key]?.name)
    }

    @Test
    fun `roundtrip - DomainUser to UiUser and back`() {
        val original = DomainUser(
            id = "roundtrip-1",
            name = "Test User",
            email = "test@example.com",
            address = DomainAddress("Test St", "Test City", "00000"),
            status = DomainStatus.ACTIVE
        )

        val converted = original.toUiUser().toDomainUser()

        assertEquals(original, converted)
    }

    @Test
    fun `roundtrip - DomainTeam to UiTeam and back`() {
        val original = DomainTeam(
            id = "team-rt",
            name = "Roundtrip Team",
            members = listOf(
                DomainUser("u1", "Member1", "m1@example.com", DomainAddress("1 St", "C1", "11111"), DomainStatus.PENDING),
                DomainUser("u2", "Member2", "m2@example.com", DomainAddress("2 St", "C2", "22222"), DomainStatus.ACTIVE)
            )
        )

        val converted = original.toUiTeam().toDomainTeam()

        assertEquals(original, converted)
    }

    @Test
    fun `interface mapping - Identifiable to SimpleItem`() {
        // Any class implementing Identifiable can be mapped
        val identifiable = object : Identifiable {
            override val id = "item-1"
            override val name = "Test Item"
        }

        val item = identifiable.toSimpleItem()

        assertEquals("item-1", item.id)
        assertEquals("Test Item", item.name)
    }
}
