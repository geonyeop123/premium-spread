-- owner 알림 채널(메일·Discord) 확장. 기존 행은 모두 메일이므로 channel 기본값 'EMAIL' 로 채워진다.
-- 한 알림은 켜진 채널 수만큼 행이 된다 — 같은 event_key 가 채널마다 한 행씩 있도록 UNIQUE 를 (event_key, channel) 로 넓힌다.
-- Discord webhook URL 은 저장하지 않는다(adapter 설정이 소유). discord_* 는 DISCORD 행에만 있다.
ALTER TABLE account_pnl_mail
    ADD COLUMN channel        VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN discord_target VARCHAR(16) NULL,
    ADD COLUMN discord_push   VARCHAR(16) NULL,
    ADD COLUMN discord_color  INT         NULL,
    DROP INDEX uk_account_pnl_mail_event_key,
    ADD CONSTRAINT uk_account_pnl_mail_event_key_channel UNIQUE (event_key, channel),
    ADD CONSTRAINT ck_account_pnl_mail_channel CHECK (
        (channel = 'EMAIL' AND discord_target IS NULL AND discord_push IS NULL AND discord_color IS NULL)
        OR (channel = 'DISCORD' AND discord_target IS NOT NULL AND discord_push IS NOT NULL AND discord_color IS NOT NULL)
    );
