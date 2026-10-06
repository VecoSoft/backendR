-- V66: owners can withdraw a verification request that is still waiting for review.
ALTER TABLE business_verification_request DROP CONSTRAINT IF EXISTS business_verification_request_status_check;
ALTER TABLE business_verification_request ADD CONSTRAINT business_verification_request_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED', 'CANCELLED'));
