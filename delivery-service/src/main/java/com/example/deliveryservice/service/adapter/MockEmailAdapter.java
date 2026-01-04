package com.example.deliveryservice.service.adapter;

import org.springframework.stereotype.Component;

/**
 * Mock email delivery adapter for testing and development.
 *
 * <p>Simulates email delivery via SMTP without actually sending emails. In production, this would
 * be replaced with a real implementation using JavaMail API, SendGrid, AWS SES, or similar
 * services.
 *
 * <h2>Simulated Behavior:</h2>
 *
 * <ul>
 *   <li>90% success rate (random simulation)
 *   <li>100-500ms simulated latency
 *   <li>Logs delivery attempt with recipient details
 * </ul>
 *
 * <h2>Production Implementation Example:</h2>
 *
 * <pre>{@code
 * @Component
 * public class SmtpEmailAdapter implements DeliveryAdapter {
 *     private final JavaMailSender mailSender;
 *
 *     public DeliveryResult deliver(NotificationEvent event) {
 *         MimeMessage message = mailSender.createMimeMessage();
 *         message.setTo(event.getRecipient());
 *         message.setSubject(event.getSubject());
 *         message.setText(event.getContent());
 *         mailSender.send(message);
 *         return DeliveryResult.success(event.getId().toString());
 *     }
 *
 *     public String getChannel() {
 *         return "email";
 *     }
 * }
 * }</pre>
 *
 * @author Notification Hub Team
 * @version 1.0
 * @since 1.0
 * @see BaseMockAdapter
 * @see DeliveryAdapter
 */
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
