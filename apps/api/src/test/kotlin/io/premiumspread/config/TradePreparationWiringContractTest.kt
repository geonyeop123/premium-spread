package io.premiumspread.config

import io.premiumspread.domain.tradeprep.BalanceSnapshotReadPort
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

/**
 * AC20(FROZEN, design.md D22) — production 배선에 `VerifiedBalanceReadPort` 구현이 존재하면 declared
 * 신고값만으로 exposure-increasing 판정(ARMED)에 도달하는 길이 열려 D9의 신뢰 경계가 무너진다.
 *
 * `BalanceReadPort`는 코드의 타입 이름이 아니라 표시용·판정용 두 계약의 총칭이다(Ruling 10, `T1`이
 * `BalanceSnapshotReadPort`/`VerifiedBalanceReadPort`로 실체화). 그래서 동결된 "`BalanceReadPort`
 * 구현이 `DeclaredBalanceAdapter`뿐이다"는 분리 후 아래 세 가지로 검증한다.
 *
 * 선례(`Phase1ConfigurationContractTest`)를 따라 Spring `ApplicationContext`를 부팅하지 않는다.
 * `ClassPathScanningCandidateComponentProvider`는 ASM으로 클래스 메타데이터만 읽을 뿐 빈을
 * 인스턴스화하지 않는다 — `useDefaultFilters = false` + 명시적 `AssignableTypeFilter`로 Spring
 * stereotype 유무와 무관하게 "해당 타입을 구현하는 클래스가 classpath에 몇 개 있는가"만 센다.
 */
class TradePreparationWiringContractTest {

    private val basePackage = "io.premiumspread"

    @Test
    fun `production classpath의 BalanceSnapshotReadPort 구현은 DeclaredBalanceAdapter 하나뿐이다`() {
        // 무엇을 재는가: `:apps:api:test`의 실제 컴파일·런타임 classpath(domain·infrastructure_
        // common·infrastructure_api·apps_api main 산출물 전부)에서 BalanceSnapshotReadPort를
        // 구현하는 클래스 전체 집합. Spring이 빈으로 등록하든 안 하든 관계없이 "코드에 존재하는
        // 구현 개수"를 정확히 잰다.
        val implementations = scanImplementations(BalanceSnapshotReadPort::class.java)

        assertThat(implementations).containsExactly(
            "io.premiumspread.infrastructure.common.tradeprep.DeclaredBalanceAdapter",
        )
    }

    @Test
    fun `production classpath에는 VerifiedBalanceReadPort 구현이 존재하지 않는다 — D9의 귀결이다`() {
        // 무엇을 재는가: 위와 같은 classpath에서 VerifiedBalanceReadPort 구현 개수. D9는 declared
        // 원천으로 판정용 잔고를 만들 수 없다고 못박았고, 그 결과 production에는 이 계약의 구현이
        // 아예 없어야 한다 — "0개"가 곧 그 안전장치가 실제로 배선에 반영됐다는 증거다.
        val implementations = scanImplementations(VerifiedBalanceReadPort::class.java)

        assertThat(implementations).isEmpty()
    }

    @Test
    fun `RecordedBalanceAdapter는 production main classpath에 존재하지 않는다`() {
        // 무엇을 재는가: 이 FQN이 `:apps:api:test`가 참조 가능한 classpath(=production 배선이
        // 소비하는 모든 모듈의 main 산출물)에 없다는 사실뿐이다. **이 assertion만으로는 "클래스가
        // test 쪽에는 실재한다"를 증명하지 못한다** — 클래스가 애초에 어디에도 없어도 똑같이
        // 통과하는 형태라 그 자체로는 D22가 막으려는 배선 실수를 검증하지 않는다.
        //
        // 실재 증거는 여기 없다: `domain/src/test/kotlin/.../RecordedBalanceAdapter.kt`가 실제로
        // 존재하고, `TradePreparationBalanceTrustTest`(:domain:test, AC13)가 그 클래스로
        // `VerifiedBalance`를 얻어 `TradePreparation.evaluateCondition`을 ARMED까지 실행하는
        // 테스트를 컴파일·통과시킨다 — 그 테스트가 이 클래스의 유일한 실재 보증이다.
        assertThatThrownBy {
            Class.forName("io.premiumspread.infrastructure.common.tradeprep.RecordedBalanceAdapter")
        }.isInstanceOf(ClassNotFoundException::class.java)
    }

    private fun scanImplementations(type: Class<*>): List<String> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(type))
        return scanner.findCandidateComponents(basePackage)
            .mapNotNull { it.beanClassName }
            .filterNot { it == type.name }
    }
}
