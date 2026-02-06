package org.example.deliveryservice.service.adapter;

import org.springframework.stereotype.Component;

/** Mock Telegram Adapter. Simulates Telegram message delivery via Telegram Bot API. */
@Component
public class MockTelegramAdapter extends BaseMockAdapter {
    @Override
    public String getChannel() {
        return "telegram";
    }
}
