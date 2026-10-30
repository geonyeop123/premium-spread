package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertRoute

object OwnerAlertRows {
    /**
     * [route] 에 따라 0~2행, 메일 행 먼저. [message] 의 Discord 문서는 Discord 행을 만들 때만 렌더한다.
     * DISCORD 행: subject=title, body=대체 평문, discordColor, discordLayout.
     */
    fun expand(message: OwnerAlertMessage, route: OwnerAlertRoute): List<AccountPnlMail> = buildList {
        if (route.email) add(AccountPnlMail(message.eventKey, message.mail.subject, message.mail.body()))
        route.discord?.let { target ->
            val rendered = message.discord.toDiscordMessage()
            add(
                AccountPnlMail(
                    eventKey = message.eventKey,
                    subject = rendered.title,
                    body = rendered.description,
                    channel = OwnerAlertChannel.DISCORD,
                    discordRoute = target,
                    discordColor = rendered.color,
                    discordLayout = rendered.layout,
                ),
            )
        }
    }
}
