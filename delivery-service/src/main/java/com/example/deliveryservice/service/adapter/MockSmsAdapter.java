package com.example.deliveryservice.service.adapter;

import org.springframework.stereotype.Component;

/** Mock SMS Adapter. Simulates SMS delivery via SMS gateway. */
@Component
public class MockSmsAdapter extends BaseMockAdapter {
    @Override
    public String getChannel() {
        return "sms";
    }
}
