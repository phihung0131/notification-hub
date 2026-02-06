package org.example.deliveryservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.example.deliveryservice.service.adapter.DeliveryAdapter;
import org.example.deliveryservice.service.adapter.MockDefaultAdapter;
import org.example.deliveryservice.service.adapter.MockEmailAdapter;
import org.example.deliveryservice.service.adapter.MockSmsAdapter;
import org.example.deliveryservice.service.adapter.MockTelegramAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeliveryAdapterRegistry Unit Tests")
class DeliveryAdapterRegistryTest {

    private DeliveryAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        List<DeliveryAdapter> adapters =
                List.of(
                        new MockEmailAdapter(),
                        new MockSmsAdapter(),
                        new MockTelegramAdapter(),
                        new MockDefaultAdapter());
        registry = new DeliveryAdapterRegistry(adapters);
    }

    @Test
    @DisplayName("Should resolve email adapter correctly")
    void resolve_EmailChannel_ReturnsEmailAdapter() {
        // Act
        DeliveryAdapter adapter = registry.resolve("email");

        // Assert
        assertNotNull(adapter);
        assertEquals("email", adapter.getChannel());
        assertInstanceOf(MockEmailAdapter.class, adapter);
    }

    @Test
    @DisplayName("Should resolve SMS adapter correctly")
    void resolve_SmsChannel_ReturnsSmsAdapter() {
        // Act
        DeliveryAdapter adapter = registry.resolve("sms");

        // Assert
        assertNotNull(adapter);
        assertEquals("sms", adapter.getChannel());
        assertInstanceOf(MockSmsAdapter.class, adapter);
    }

    @Test
    @DisplayName("Should resolve Telegram adapter correctly")
    void resolve_TelegramChannel_ReturnsTelegramAdapter() {
        // Act
        DeliveryAdapter adapter = registry.resolve("telegram");

        // Assert
        assertNotNull(adapter);
        assertEquals("telegram", adapter.getChannel());
        assertInstanceOf(MockTelegramAdapter.class, adapter);
    }

    @Test
    @DisplayName("Should be case-insensitive")
    void resolve_UppercaseChannel_ReturnsAdapter() {
        // Act
        DeliveryAdapter adapter = registry.resolve("EMAIL");

        // Assert
        assertNotNull(adapter);
        assertEquals("email", adapter.getChannel());
    }

    @Test
    @DisplayName("Should return default adapter for unknown channel")
    void resolve_UnknownChannel_ReturnsDefaultAdapter() {
        // Act
        DeliveryAdapter adapter = registry.resolve("unknown-channel");

        // Assert
        assertNotNull(adapter);
        assertEquals("default", adapter.getChannel());
        assertInstanceOf(MockDefaultAdapter.class, adapter);
    }

    @Test
    @DisplayName("Should return default adapter for null channel")
    void resolve_NullChannel_ReturnsDefaultAdapter() {
        // Act
        DeliveryAdapter adapter = registry.resolve(null);

        // Assert
        assertNotNull(adapter);
        assertEquals("default", adapter.getChannel());
    }

    @Test
    @DisplayName("Should return all registered channels")
    void getRegisteredChannels_ReturnsAllChannels() {
        // Act
        var channels = registry.getRegisteredChannels();

        // Assert
        assertNotNull(channels);
        assertEquals(4, channels.size());
        assertTrue(channels.contains("email"));
        assertTrue(channels.contains("sms"));
        assertTrue(channels.contains("telegram"));
        assertTrue(channels.contains("default"));
    }
}
