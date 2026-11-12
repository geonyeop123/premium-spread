ALTER TABLE account_pnl_monitor_state
    ADD COLUMN liquidation_side        VARCHAR(8)  NULL,
    ADD COLUMN liquidation_entry_price VARCHAR(64) NULL,
    ADD COLUMN liquidation_level       INT         NULL,
    ADD CONSTRAINT ck_account_pnl_monitor_state_liquidation CHECK (
        (liquidation_side IS NULL AND liquidation_entry_price IS NULL AND liquidation_level IS NULL)
        OR (liquidation_side IS NOT NULL AND liquidation_entry_price IS NOT NULL AND liquidation_level IS NOT NULL
            AND liquidation_side IN ('SHORT', 'LONG') AND liquidation_level IN (0, 50, 60, 70, 80, 90))
    );
