package org.example.deliveryservice.provider;

import org.example.events.NotificationEvent;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Email via SMTP (Mailpit in dev). */
public class EmailChannelProvider implements ChannelProvider {

    private final JavaMailSender mailSender;
    private final String from;
    private final String channel;

    public EmailChannelProvider(String channel, JavaMailSender mailSender, String from) {
        this.channel = channel;
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public String channel() {
        return channel;
    }

    @Override
    public ProviderResult send(NotificationEvent event, String idempotencyKey) {
        if (event.getRecipient() == null || !event.getRecipient().toString().contains("@")) {
            throw DeliveryException.permanent("invalid email recipient", null);
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(event.getRecipient().toString());
        msg.setSubject(event.getSubject() == null ? "" : event.getSubject().toString());
        msg.setText(event.getContent() == null ? "" : event.getContent().toString());
        try {
            // SMTP has no idempotency key: duplicate protection is the processed-id store.
            mailSender.send(msg);
            return new ProviderResult(idempotencyKey, "sent via smtp");
        } catch (MailParseException | MailPreparationException e) {
            throw DeliveryException.permanent("mail rejected: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw DeliveryException.retryable("smtp failure: " + e.getMessage(), e);
        }
    }
}
