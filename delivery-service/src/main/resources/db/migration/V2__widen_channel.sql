-- notification-service allows channel codes up to 255 chars; delivery must accept the same.
ALTER TABLE notification_delivery ALTER COLUMN channel TYPE VARCHAR(255);
