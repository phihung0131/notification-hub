package org.example.deliveryservice.service.adapter;

import org.springframework.stereotype.Component;

/** Mock email delivery adapter for testing and development. */
@Component
public class MockEmailAdapter extends BaseMockAdapter {
    /**
     * Returns the channel code for email notifications.
     *
     * @return "email" - matches Channel.code in database
     */
    @Override
    public String getChannel() {
        return "email";
    }
}
