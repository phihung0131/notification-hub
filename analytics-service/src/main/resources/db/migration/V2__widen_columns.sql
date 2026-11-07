-- Align with notification-service (channel code up to 255) and delivery-service.
ALTER TABLE messages ALTER COLUMN tenant_id TYPE VARCHAR(128);
ALTER TABLE messages ALTER COLUMN channel   TYPE VARCHAR(255);
ALTER TABLE messages ALTER COLUMN recipient TYPE VARCHAR(512);
