ALTER TABLE shows
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_shows_public_id UNIQUE (public_id);

ALTER TABLE seats
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_seats_public_id UNIQUE (public_id);

ALTER TABLE reservations
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_reservations_public_id UNIQUE (public_id);

ALTER TABLE idempotency_keys
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uq_idempotency_public_id UNIQUE (public_id);
