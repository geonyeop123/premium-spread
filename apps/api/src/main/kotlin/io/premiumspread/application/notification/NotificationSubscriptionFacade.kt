package io.premiumspread.application.notification

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.notification.NotificationSubscriptionCommand
import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.notification.NotificationSubscriptionService
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.domain.ticker.Exchange
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class NotificationSubscriptionFacade(private val service: NotificationSubscriptionService, private val clock: Clock) {
    fun create(criteria: NotificationSubscriptionCriteria.Create): NotificationSubscriptionResult.Detail =
        translate {
            toDetail(
                service.create(
                    NotificationSubscriptionCommand.Create(
                        memberId = criteria.memberId,
                        symbol = criteria.symbol,
                        direction = parseEnum(criteria.direction),
                        threshold = criteria.threshold,
                        koreaExchange = parseEnum(criteria.koreaExchange),
                        foreignExchange = parseEnum(criteria.foreignExchange),
                    ),
                ),
            )
        }

    fun find(criteria: NotificationSubscriptionCriteria.Find): NotificationSubscriptionResult.Detail {
        val subscription = service.findByIdAndMemberId(criteria.id, criteria.memberId)
            ?: throw ApplicationException(ApplicationError.NOTIFICATION_SUBSCRIPTION_NOT_FOUND)
        return toDetail(subscription)
    }

    fun findAll(criteria: NotificationSubscriptionCriteria.FindAll): NotificationSubscriptionResult.Details =
        NotificationSubscriptionResult.Details(
            service.findAllByMemberId(criteria.memberId).map(::toDetail),
        )

    fun update(criteria: NotificationSubscriptionCriteria.Update): NotificationSubscriptionResult.Detail =
        translate {
            if ((criteria.koreaExchange == null) != (criteria.foreignExchange == null)) {
                throw ApplicationException(ApplicationError.DOMAIN_ERROR)
            }
            toDetail(
                service.update(
                    NotificationSubscriptionCommand.Update(
                        id = criteria.id,
                        memberId = criteria.memberId,
                        status = criteria.status?.let { parseEnum<SubscriptionStatus>(it) },
                        direction = criteria.direction?.let { parseEnum<ThresholdDirection>(it) },
                        threshold = criteria.threshold,
                        koreaExchange = criteria.koreaExchange?.let { parseEnum<Exchange>(it) },
                        foreignExchange = criteria.foreignExchange?.let { parseEnum<Exchange>(it) },
                    ),
                ),
            )
        }

    fun delete(criteria: NotificationSubscriptionCriteria.Delete) {
        translate { service.delete(criteria.id, criteria.memberId, clock.instant()) }
    }

    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: NotificationSubscriptionNotFoundException) {
            throw ApplicationException(ApplicationError.NOTIFICATION_SUBSCRIPTION_NOT_FOUND, ex)
        } catch (ex: IllegalArgumentException) {
            throw ApplicationException(ApplicationError.DOMAIN_ERROR, ex)
        }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String): T =
        try {
            enumValueOf<T>(raw)
        } catch (ex: IllegalArgumentException) {
            throw ApplicationException(ApplicationError.DOMAIN_ERROR, ex)
        }

    private fun toDetail(entity: NotificationSubscription): NotificationSubscriptionResult.Detail =
        NotificationSubscriptionResult.Detail(
            id = entity.id,
            memberId = entity.memberId,
            symbol = entity.symbol,
            direction = entity.direction.name,
            threshold = entity.threshold,
            status = entity.status.name,
            koreaExchange = entity.marketPair.koreaExchange.name,
            foreignExchange = entity.marketPair.foreignExchange.name,
        )
}
