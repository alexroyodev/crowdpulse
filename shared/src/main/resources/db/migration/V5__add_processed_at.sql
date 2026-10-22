-- Spec 003: when the processor applied the event. System latency = processed_at - received_at.
ALTER TABLE sensor_event ADD COLUMN processed_at TIMESTAMPTZ NULL;

-- Phase 1 processed every event in the same request that received it.
UPDATE sensor_event SET processed_at = received_at;

-- No CHECK (processed_at >= received_at): both instants come from the clocks of two different
-- processes, and a few milliseconds of skew must not reject a valid event.
ALTER TABLE sensor_event ALTER COLUMN processed_at SET NOT NULL;
