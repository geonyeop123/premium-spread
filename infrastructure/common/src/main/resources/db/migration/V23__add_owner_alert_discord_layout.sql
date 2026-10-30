-- #102 owner 알림 Discord embed 레이아웃(author·fields·footer·timestamp)을 DISCORD 행에 보존한다.
-- JSON 텍스트이며 직렬화는 infrastructure:common 이 한다. NULL = 레이아웃 없는 DISCORD 행(배포 전 행 포함 — 제목·설명으로 발송) 또는 EMAIL 행.
-- 비밀값(webhook URL·수신 주소)은 담지 않는다.
ALTER TABLE account_pnl_mail
    ADD COLUMN discord_layout TEXT NULL,
    ADD CONSTRAINT ck_account_pnl_mail_discord_layout CHECK (discord_layout IS NULL OR channel = 'DISCORD');
