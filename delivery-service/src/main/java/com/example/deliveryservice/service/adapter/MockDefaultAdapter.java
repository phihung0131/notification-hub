package com.example.deliveryservice.service.adapter;

import org.springframework.stereotype.Component;

/** Mock Default Adapter. Fallback adapter for unknown channels. */
@Component
public class MockDefaultAdapter extends BaseMockAdapter {
    @Override
    public String getChannel() {
        return "default";
    }
}
