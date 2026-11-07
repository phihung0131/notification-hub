-- Durable quota truth lives in tenants.quota_used (committed units only). Reservations are held
-- in the Redis ledger with a TTL. These constraints make the database the final arbiter:
-- a committed count can never go negative.
ALTER TABLE tenants ADD CONSTRAINT chk_tenants_quota_used_nonneg CHECK (quota_used >= 0);
ALTER TABLE tenants ADD CONSTRAINT chk_tenants_quota_limit CHECK (quota_limit >= -1);
