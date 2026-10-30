package io.premiumspread.domain.alertmessage

import io.premiumspread.domain.owneralert.DiscordField

/** golden 기대값용 칸 표기. 구현의 SPACER 를 참조하지 않고 design §3.3 규칙 2 에서 따로 만든다(U+200B 한 글자). */
internal fun gf(name: String, value: String, inline: Boolean) = DiscordField(name, value, inline)

/** 2칸 inline 행 뒤 채움 칸. */
internal val GOLDEN_FILLER: DiscordField = DiscordField(Char(0x200B).toString(), Char(0x200B).toString(), true)
