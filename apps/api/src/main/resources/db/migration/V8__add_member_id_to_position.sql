ALTER TABLE position
    ADD COLUMN member_id BIGINT NOT NULL AFTER status,
    ADD INDEX idx_position_member_id (member_id),
    ADD CONSTRAINT fk_position_member FOREIGN KEY (member_id) REFERENCES member(id);
