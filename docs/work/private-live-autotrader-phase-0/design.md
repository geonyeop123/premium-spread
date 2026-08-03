# Phase 0 — Foundation Alignment

| 항목 | 값 |
|---|---|
| 문서 역할 | `feature-workflow` ④ spec 문서 |
| slug | `private-live-autotrader-phase-0` |
| 실행 단위 | 상위 plan `#1 Phase 0 Foundation Alignment` |
| 상위 spec | [`../private-live-autotrader/design.md`](../private-live-autotrader/design.md) |
| 상위 plan | [`../private-live-autotrader/plan.md`](../private-live-autotrader/plan.md) |
| 완료 기준 계약서 | [`dod.md`](dod.md) |
| 실행 계획 | [`plan.md`](plan.md) |
| base branch | `dev` |
| branch | `refactor/private-live-autotrader-phase-0` |
| 진입 상태 | `MASTER_SPEC_APPROVED` |
| 종료 판정 | `FOUNDATION_ALIGNED` |
| status | `DRAFT` |

> 이 문서는 Phase 0 하나의 실행 단위만 소유한다. 프로그램 전체의 목표·보장·안전 불변식·상태축은 상위
> spec이 소유하며 여기서 재정의하지 않는다. 상태축 현재값은 `.ai/planning/private-live-autotrader/progress.md`가
> 단독 소유한다.

## 1. 목표와 범위

### 1.1 목표

현재 제품의 의미와 이후 자동매매 개발이 전제할 용어·방향·관찰 가능성을 일치시킨다. 이 Phase는 주문을 내지
않고 전략을 만들지 않는다. 이미 존재하는 기능이 **무엇이 아닌지**를 코드·API·화면·문서가 같은 말로 하게
만드는 것이 전부다.

### 1.2 포함

- 기존 Position 기능이 **실제 주문을 생성하지 않는 수동 추적 record**임을 REST 계약·도메인 타입·화면 문구가
  일관되게 드러내도록 정렬한다 (`P0-O1`, `SEM-1`, `SEM-2`)
- gross 손익의 보장 범위(제외 항목, 백분율 분모, 레버리지 무관성)를 응답과 화면이 명시하게 한다
  (`P0-O2`, `SEM-4`)
- 진입·청산 방향 설명이 서로 모순되지 않게 한다 (`SEM-3`)
- 종료된 추적의 손익이 현재 시세로 계속 변하는 결함을 **청산 시점 스냅샷 확정**으로 고친다 (`P0-O2`)
- `ARCH-9` 정본 identity 요구와 현재 identity의 차이를 판정해 문서에 고정한다 (`P0-O3`)
- dead 또는 미연결된 기존 계약의 유지·수정·제거를 근거와 함께 결정한다 (`P0-O4`)
- As-Is 아키텍처 문서와 Planned capability 문서를 상호 참조로 구분한다 (`P0-O5`, `ARCH-7`)

위 outcome을 **검증 가능하게** 만들기 위해 아래 두 가지가 함께 들어온다. 상위 spec이 지시한 항목은 아니므로
⑦ 승인에서 명시적으로 확인받는다.

- `apps/web` 테스트 인프라 도입 (vitest + Testing Library). 현재 `apps/web`에는 테스트가 없어
  "사용자가 고지를 본다"는 주장을 문자열 검색으로밖에 확인할 수 없고, 그 검사는 주석·미사용 컴포넌트·도달
  불가 분기로도 통과한다. `SEM-1`·`SEM-4`가 사용자가 **보는 것**에 관한 계약이므로 렌더 검증이 필요하다
- `.github/workflows/quality-gate.yml`의 web job에 `npm run test` 추가와 `ci/quality-gate-contract-test.sh`
  기대값 동시 갱신

### 1.3 제외 *(scope creep 차단선)*

- 새 시장 observation type, archive provider, 전략 엔진, LIVE 계약 — 상위 spec이 Phase 1~3에 배정
- `MarketPair` 타입 자체의 확장 — §4 `D5`가 Phase 1로 배정
- 수수료·펀딩비·슬리피지를 **반영한** 순손익 계산 — Phase 1 `P1-O5` 경제 모델의 범위
- 계정 ROI, 실제 체결 손익, 외부 계정 대조 — Phase 3 이후
- `position` DB 테이블명 변경, Redis key 포맷 변경, premium/ticker/notification 도메인 변경
- 인증·보안 경계 변경 — 추적 endpoint는 지금도 인증 필수이며 그대로 유지

## 2. 진입 조건 검증

상위 spec이 요구하는 진입 조건은 `MASTER_SPEC_APPROVED`이며 최신 `dev` 기준선이 기존 repository gate를
통과하는 것이다.

| 조건 | 근거 | 판정 |
|---|---|---|
| `MASTER_SPEC_APPROVED` | `docs/work/private-live-autotrader/dod.md` `status: FROZEN`, `frozen_at: 2026-07-31T11:38:14+09:00`, 판정 `VERIFIED` | 충족 |
| 최신 `dev` 기준선이 gate 통과 | PR #64 병합 후 `dev`(`5319a2d`)의 Quality Gate 7/7 success | 충족 |
| Phase -1 산출물 동결 유지 | `.ai/planning/private-live-autotrader/phase-minus-1-*.md`, `docs/dod/private-live-autotrader-phase-minus-1.dod.md` 무변경 | 이 Phase에서도 유지 |

## 3. As-Is 실측 판정

문서가 아니라 `dev`(`5319a2d`)의 실제 코드를 읽어 확인한 사실이다. 각 항목은 이후 절의 변경 근거가 된다.

### 3.1 Position은 주문을 내지 않지만 모든 표면이 주문을 암시한다

`Position` aggregate에는 주문 ID, 체결 ID, 거래소 응답, 수수료, 체결 시각이 없다. 저장소 어디에도 주문·체결
record 타입이 존재하지 않는다. 그런데 표면은 다음과 같다.

| 표면 | 현재 | 읽히는 의미 |
|---|---|---|
| `POST /api/v1/positions/auto` | 최신 premium snapshot을 읽어 진입가를 자동으로 채워 저장 | 자동으로 주문을 낸다 |
| `POST /api/v1/positions/manual` | 사용자 입력값으로 저장 | 수동으로 주문을 낸다 |
| `POST /api/v1/positions/{id}/close` | `status`만 `CLOSED`로 변경 | 포지션을 청산한다 |
| 화면 `포지션 열기 (AUTO)` | 위와 동일 | 자동 주문 |
| 화면 `현재 데이터 채우기` | snapshot 조회 후 폼 채움 | — (유일하게 정직) |
| 화면 `포지션을 종료하시겠습니까?` | status 변경 | 청산 확인 |

`openAuto`의 `auto`는 **주문 자동화가 아니라 입력값 자동 채움**을 뜻한다. 이 사실은 코드 밖 어디에도 적혀
있지 않다.

### 3.2 gross 손익이 보장 범위 없이 "현재 PnL"로 표시된다

`Position.calculatePnl()`은 다음을 계산한다.

```text
koreaPnl        = (현재 한국가 - 진입 한국가) × 한국수량            [KRW]
foreignPnlUsd   = (진입 해외가 - 현재 해외가) × 해외수량            [USD]
foreignPnlKrw   = foreignPnlUsd × 현재환율                          [KRW]
totalPnlKrw     = koreaPnl + foreignPnlKrw
koreaCurrentValue = 현재 한국가 × 한국수량
totalPnlPercent = totalPnlKrw / koreaCurrentValue × 100
```

확인된 사실:

- 거래 수수료, 무기한선물 펀딩비, 슬리피지, 환전 스프레드가 **전부 빠져 있다**. gross다.
- `totalPnlPercent`의 분모 `koreaCurrentValue`는 **현재** 한국 leg 명목가다. 진입 자본도, 투입 증거금도,
  계정 자산도 아니다. 같은 손익이라도 시세가 오르면 백분율이 작아진다.
- `foreignLeverage`는 저장·검증(1~125)·응답까지 존재하지만 **어떤 계산에도 쓰이지 않는다.**
- 화면은 이 값을 `현재 PnL`로만 표시한다. `SEM-4`가 금지하는 "계정 ROI나 실제 체결 손익으로 보이는" 상태다.

### 3.3 종료된 추적의 손익이 현재 시세로 계속 변한다 *(실제 결함)*

`Position.close()`는 `status`만 뒤집고 **청산 시점의 가격·환율·프리미엄율·시각을 저장하지 않는다.**
`GET /positions/{id}/pnl`에는 상태 가드가 없어 `CLOSED` 포지션도 최신 snapshot으로 계산한다.
화면 `apps/web/src/app/positions/[id]/page.tsx`의 `fetchPnl`은 상태와 무관하게 호출된다.

결과: 종료한 추적을 며칠 뒤에 열면 손익 숫자가 달라져 있다. 목록 화면(`PositionList.tsx`)이 `OPEN`만 조회해
우연히 이 문제를 피하고 있을 뿐, 계약 자체가 확정 손익을 갖지 않는다.

### 3.4 정본 identity에 instrument class와 quote currency가 없다

```kotlin
class MarketPair(val symbol: Symbol, val koreaExchange: Exchange, val foreignExchange: Exchange)
enum class Exchange(val region: ExchangeRegion) { UPBIT(KOREA), BITHUMB(KOREA), BINANCE(FOREIGN), FX_PROVIDER(FOREIGN) }
```

`ARCH-9`가 정본 identity에 요구하는 두 leg의 **instrument class**(현물 / 무기한선물)와 **quote currency**
(KRW / USDT)가 타입에도 스키마에도 없다. "한국 leg은 KRW 현물, 해외 leg은 USDT 무기한선물"이라는 전제가
코드 전체에 암묵적으로 깔려 있고, 어떤 값도 그 전제를 기록하지 않는다.

### 3.5 미연결·미사용 계약

| 대상 | 실측 | 
|---|---|
| `Exchange.UPBIT` | main 소스 사용처 **0건**. 테스트 픽스처에서만 등장. 수집·표시 경로 없음 |
| `Position.foreignLeverage` | 저장·검증·응답하지만 계산 사용처 0건 |
| `PositionRepository.findAllOpen()` / `findAllByStatus` | Facade 사용처 0건. `PositionServiceTest`만 호출 |
| `GET /positions/{id}/pnl` | `CLOSED` 가드 없음 (§3.3) |

### 3.6 As-Is와 Planned가 문서에서 연결돼 있지 않다

`.ai/architecture/ARCHITECTURE_DESIGN.md`는 첫 줄에 `구현 기준(As-Is), 2026-07-15`를 선언한다. Planned
capability는 `docs/work/private-live-autotrader/design.md`가 소유하지만 두 문서 사이에 상호 참조가 없다.
As-Is 문서만 읽은 사람은 자동매매 계획의 존재를 알 수 없고, 계획 문서만 읽은 사람은 현재 구현 경계를 알 수 없다.

## 4. 결정

사용자 합의(`feature-workflow` ③)로 확정된 결정이다. 각 결정은 버린 대안과 그 이유를 함께 기록한다.

### D1. REST 계약과 도메인 타입을 함께 rename한다

추적 record를 `position`이 아닌 **`tracking`**으로 부른다. HTTP 리소스, 도메인 타입, 패키지, 화면 경로가 모두
바뀐다. `position` **DB 테이블명은 유지**한다.

근거:

- 이름 자체가 거짓말을 하지 않게 하는 것이 `SEM-1`의 요구다. 문구만 고치면 `openAuto`·`close`라는 이름이
  계속 주문을 암시한다.
- `SEM-2`는 추적 record와 자동매매 execution record를 같은 개념으로 재사용하지 못하게 한다. 지금 이름을
  비워 두어야 Phase 3의 실제 체결 결과가 `Position`이라는 정확한 단어를 쓸 수 있다. 파생상품에서 "position"은
  실제 보유 익스포저를 가리키는 옳은 단어이고, 그 자리를 추적 record가 점유하고 있다.
- 유일한 소비자가 `apps/web`이다. breaking change 비용이 지금보다 싸질 시점은 오지 않는다.

버린 대안: **문구·문서만 수정** — diff가 작지만 코드를 읽는 사람에게 `openAuto`가 계속 주문을 암시하고,
Phase 3에서 이름 충돌을 따로 풀어야 한다. **메타 필드만 추가**(`recordType`, `placesRealOrder`) — 계약을
명시하지만 엔드포인트 이름의 오해가 남는다.

> **⑦ 승인 시 확인 필요:** ③ 합의 시점에 제시한 미리보기는 HTTP 표면과 화면 문구까지였다. 위 근거가
> 성립하려면 도메인 타입(`Position` → `Tracking`)과 패키지까지 함께 바뀌어야 하므로 §5.2를 포함해
> 승인받는다. 동작 변경은 없고 컴파일러가 전수 검증하는 기계적 rename이다.

### D2. 청산 시점 스냅샷을 저장하고 종료된 추적은 그 고정값으로 계산한다

`archive` 시점의 한국가·해외가·환율·프리미엄율·관측시각을 `position` 행에 확정 저장한다. Flyway `V15`가
nullable 컬럼을 추가한다. 종료된 추적의 손익은 그 스냅샷으로만 계산한다.

시세를 얻을 수 없을 때도 **종료 자체는 막지 않는다.** 대신 그 사실을 `close_price_source`에 기록하고, 확정
손익을 제공하지 않는다. 사용자가 종료하려는 순간에 시세가 없다고 거절하는 것은 추적 도구로서 부당하고,
없는 시세를 추정해 채우는 것은 `SEM-4`가 금지하는 거짓 확정이다.

버린 대안: **CLOSED에 `/pnl`을 409로 차단** — 스키마 변경 없이 거짓 숫자는 막지만 종료 손익을 영영 알 수
없어 이력 탭이 진입 정보만 남는다. **판정만 기록하고 Phase 1로 이월** — 사용자가 지금 보는 화면의 거짓
숫자가 그대로 남는다.

### D3. gross 손익의 보장 범위를 응답 스키마가 스스로 말하게 한다

주석·문서가 아니라 **응답 필드**로 표현한다. 필드명에 `gross`와 분모를 박고, `pnlBasis`·`priceBasis` 두
메타 필드를 추가한다 (§5.3.3). 문서는 지워지거나 안 읽히지만 응답 스키마는 소비자가 반드시 통과한다.

### D4. 상태값은 domain·API만 `ACTIVE`/`ARCHIVED`로 정렬하고 DB 저장값은 유지한다

`TrackingStatus`의 값은 `ACTIVE`/`ARCHIVED`다. **DB `position.status` 컬럼에는 기존 `OPEN`/`CLOSED`를 그대로
저장한다.** JPA `AttributeConverter` 하나가 `ACTIVE ↔ OPEN`, `ARCHIVED ↔ CLOSED`를 매개한다. `V15`는 컬럼을
추가할 뿐 기존 컬럼의 값을 재작성하지 않는다.

근거는 저장소의 배포 계약이다. `docs/runbooks/deployment.md` "Rollback 제약"은 다음을 규정한다.

> 자동 rollback은 application image rollback이며 DB down migration을 수행하지 않는다. 모든 forward
> migration은 최소 한 배포 동안 이전 application image와 호환되어야 한다. 호환되지 않는 migration은
> 별도 expand/contract 배포로 나눈다.
>
> V13/V14 적용 뒤 image rollback은 이전 image가 추가 column/table을 무시하는 호환 범위에서만 허용한다.

기존 컬럼의 값을 `ACTIVE`/`ARCHIVED`로 재작성하면 이전 image가 `PositionStatus.valueOf("ACTIVE")`에서
실패한다. 롤백이 DB를 되돌리지 않으므로 **롤백 자체가 불가능해진다.** 이는 runbook이 금지하는 비호환
migration이고, `verifyMigrations`의 destructive gate(`TRUNCATE`·`DROP`만 검사)가 잡아주지 못하는 종류다.

이 결정은 D1의 "DB 테이블명은 유지"와 같은 규칙의 적용이다. **DB는 legacy 표현을 유지하고 domain과 API가
정렬된 표현을 쓴다.** 매핑은 converter 한 곳에 모이고 §5.8이 그 경계를 기록한다.

**converter가 안전한지 실측했다.** 상태를 쓰는 기존 쿼리는 전부 JPQL 또는 derived query다.

| 위치 | 형태 |
|---|---|
| `SpringDataPositionRepository.findAllByStatus` | `@Query("SELECT p FROM Position p WHERE p.status = :status")` — JPQL |
| `SpringDataPositionRepository.findAllByMemberIdAndStatus` | 〃 |
| `SpringDataPositionRepository.countByMemberIdAndStatusAndDeletedAtIsNull` | derived query |

JPQL과 derived query의 파라미터 바인딩은 `AttributeConverter`를 거치므로 `TrackingStatus.ACTIVE`가 `'OPEN'`으로
바인딩된다. **`position.status`를 비교하는 native query는 없다.** native query가 추가되면 converter를
우회하므로, 그때는 저장값 리터럴을 직접 써야 하고 `AC24`의 `leaked` 검사가 그 사실을 드러낸다.

버린 대안: **`V15`가 값을 `UPDATE`** — 초안이 택했던 방식이며 위 이유로 폐기했다. **expand/contract 2단계
배포** — 새 코드가 구·신 값을 모두 읽고 한 배포 뒤 수축한다. 정석이지만 개인 단일 계정 제품에서 "다음
배포"가 보장되지 않아 영구 이중 상태로 남을 위험이 크고, converter 방식이 같은 안전성을 더 적은 상태로 얻는다.
**상태값 정렬 포기** — `/archive`가 `CLOSED`를 만드는 절반짜리 rename이 남는다.

### D5. identity는 판정·기록만 하고 타입 확장은 Phase 1이 수행한다

`MarketPair`에 instrument class와 quote currency를 **추가하지 않는다.** 차이와 한계와 확장 담당 Phase를
문서에 고정한다(§5.5).

근거: `MarketPair` 확장은 premium·ticker·notification 전 도메인과 Redis key 포맷, 집계 테이블, runbook에
파급된다. 그 범위는 "실행 없음, 문서·표현·identity 정렬"이라는 Phase 0의 성격을 벗어나고, 최소 수집 계약
(`P1-O1`)을 확정하는 Phase 1이 확장 형태를 함께 결정하는 편이 정확하다. 상위 spec의 `P0-O3`도 Phase 0에
**판정**만 요구한다.

### D6. Phase 0 전체를 단일 PR로 낸다

의미 정렬·결함 수정·판정 기록이 한 문맥에서 리뷰되고 `FOUNDATION_ALIGNED`를 한 번에 판정한다.

## 5. 변경 설계

### 5.1 REST 계약

`position` 테이블명은 유지하고 HTTP 리소스만 `trackings`로 바꾼다. 모든 endpoint는 지금과 같이 **인증
필수**이며 `PublicEndpointPolicy`는 변경하지 않는다.

| 현재 | 변경 후 | 의미 |
|---|---|---|
| `POST /api/v1/positions/auto` | `POST /api/v1/trackings/from-market` | 최신 시세로 진입값을 채워 추적 기록 생성 |
| `POST /api/v1/positions/manual` | `POST /api/v1/trackings` | 입력값으로 추적 기록 생성 |
| `GET /api/v1/positions` | `GET /api/v1/trackings` | 활성(`ACTIVE`) 추적 목록 |
| `GET /api/v1/positions/history` | `GET /api/v1/trackings/archived` | 종료(`ARCHIVED`) 추적 목록 |
| `GET /api/v1/positions/summary` | `GET /api/v1/trackings/summary` | 개수 요약 |
| `GET /api/v1/positions/{id}` | `GET /api/v1/trackings/{id}` | 단건 조회 |
| `GET /api/v1/positions/{id}/pnl` | `GET /api/v1/trackings/{id}/gross-pnl` | gross 손익 |
| `POST /api/v1/positions/{id}/close` | `POST /api/v1/trackings/{id}/archive` | 추적 종료 + 청산 스냅샷 확정 |

`GET /trackings/archived`와 `GET /trackings/{id}`는 literal 경로가 우선 매칭된다. 기존 `/positions/history`와
`/positions/{id}`가 동일 구조로 이미 동작한다.

에러 코드도 함께 바뀐다. 이는 응답 body의 `code` 값이므로 계약 변경이다.

| 현재 | 변경 후 |
|---|---|
| `POSITION_NOT_FOUND` | `TRACKING_NOT_FOUND` |
| `INVALID_POSITION` | `INVALID_TRACKING` |
| — (신규) | `TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE` |

`PREMIUM_SNAPSHOT_NOT_AVAILABLE`, `STALE_PREMIUM_SNAPSHOT`, `PREMIUM_NOT_FOUND`는 premium 도메인 소유이므로
변경하지 않는다.

### 5.2 도메인·패키지 rename

`@Table(name = "position")`은 유지한다. 컴파일러가 전수 검증하는 기계적 변경이며 동작 변경은 없다.

| 현재 | 변경 후 |
|---|---|
| `domain/position/Position.kt` | `domain/tracking/Tracking.kt` |
| `PositionOpenSpec` | `TrackingRecordSpec` |
| `domain/position/PositionPnl.kt` | `domain/tracking/TrackingGrossPnl.kt` |
| `domain/position/PositionCommand.kt` | `domain/tracking/TrackingCommand.kt` |
| `domain/position/PositionRepository.kt` | `domain/tracking/TrackingRepository.kt` |
| `domain/position/PositionService.kt` | `domain/tracking/TrackingService.kt` |
| `domain/position/PositionStatus.kt` | `domain/tracking/TrackingStatus.kt` |
| `domain/position/InvalidPositionException.kt` | `domain/tracking/InvalidTrackingException.kt` |
| `infrastructure/common/.../jpa/position/JpaPositionRepositoryAdapter.kt` | `.../jpa/tracking/JpaTrackingRepositoryAdapter.kt` |
| `infrastructure/common/.../jpa/position/SpringDataPositionRepository.kt` | `.../jpa/tracking/SpringDataTrackingRepository.kt` |
| `apps/api/application/position/PositionFacade.kt` | `apps/api/application/tracking/TrackingFacade.kt` |
| `apps/api/application/position/PositionDtos.kt` | `apps/api/application/tracking/TrackingDtos.kt` (`TrackingCriteria`, `TrackingResult`) |
| `apps/api/interfaces/api/position/PositionController.kt` | `apps/api/interfaces/api/tracking/TrackingController.kt` |
| `apps/api/interfaces/api/position/PositionDtos.kt` | `apps/api/interfaces/api/tracking/TrackingDtos.kt` (`TrackingRequest`, `TrackingResponse`) |

DTO inner class 이름은 `.ai/rules/naming.md`의 동작 기준을 따른다.

```kotlin
class TrackingRequest  private constructor() { data class RecordFromMarket(...) ; data class Record(...) }
class TrackingCriteria private constructor() { data class RecordFromMarket(...) ; data class Record(...) ; data class Archive(...) ; ... }
class TrackingResult   private constructor() { data class Detail(...) ; data class Details(...) ; data class GrossPnl(...) ; data class Summary(...) }
class TrackingCommand  private constructor() { data class Create(...) }
```

변경하지 않는 것: `V12MigrationSafety*`(V12 전용 동결 guard), `docs/runbooks/v12-migration.md`,
`.ai/planning/**`(역사 기록), `docs/superpowers/**`(과거 실행 단위 산출물).

### 5.3 청산 스냅샷

#### 5.3.1 스키마 (`V15`)

```sql
-- V15: 추적 종료 시점의 시세를 확정 저장한다.
-- 기존 컬럼의 값을 재작성하지 않는다 — 이전 application image 롤백 호환을 유지해야 한다
-- (docs/runbooks/deployment.md "Rollback 제약", design.md D4).
ALTER TABLE position
    ADD COLUMN closed_at           DATETIME(6)     NULL AFTER status,
    ADD COLUMN close_price_source  VARCHAR(30)     NULL AFTER closed_at,
    ADD COLUMN close_observed_at   DATETIME(6)     NULL AFTER close_price_source,
    ADD COLUMN close_korea_price   DECIMAL(30, 10) NULL AFTER close_observed_at,
    ADD COLUMN close_foreign_price DECIMAL(30, 10) NULL AFTER close_korea_price,
    ADD COLUMN close_fx_rate       DECIMAL(20, 6)  NULL AFTER close_foreign_price,
    ADD COLUMN close_premium_rate  DECIMAL(10, 2)  NULL AFTER close_fx_rate;

-- 신규 컬럼만 채운다. 이전 image는 이 컬럼을 무시하므로 롤백 호환 범위 안이다.
UPDATE position SET close_price_source = 'LEGACY_UNKNOWN' WHERE status = 'CLOSED';
```

`status` 컬럼은 `OPEN`/`CLOSED`를 유지한다 (D4). 기존 종료 행은 청산 시세를 복원할 방법이 없으므로 추정해
채우지 않고 `LEGACY_UNKNOWN`으로 표시해 확정 손익을 제공하지 않는다.

#### 5.3.2 확정 판정과 요청·응답 계약

**확정 판정 규칙 (단일 정의).** 종료된 추적이 확정 손익을 갖는 것은 다음이 모두 참일 때뿐이다.

```text
status == ARCHIVED
  AND close_price_source == 'MARKET_SNAPSHOT'
  AND closed_at, close_observed_at,
      close_korea_price, close_foreign_price, close_fx_rate, close_premium_rate
      6개가 모두 non-null
```

하나라도 아니면 **확정 손익을 제공하지 않는다**(fail-closed). `close_price_source`가 `NULL`인 경우도 여기
포함된다. 이 규칙 하나가 아래 모든 조합을 덮으므로 값마다 분기를 따로 두지 않는다.

**규칙의 구성 원칙:** 확정 판정은 **확정 응답이 노출하는 모든 값의 원천을 빠짐없이 포함한다.** 응답 계약에
필드를 추가하면 그 원천 컬럼도 이 규칙에 들어가야 한다. 초안은 가격 4개만 검사해 `close_observed_at`이
`NULL`인 부분 행이 `observedAt` 없이 `200 ARCHIVED_SNAPSHOT`으로 통과할 수 있었다 — 관측 시각을 모르는 값을
"확정 시점 값"으로 표시하는 fail-open이다.

| 확정 응답 필드 | 원천 | 규칙 포함 |
|---|---|---|
| `observedAt` | `close_observed_at` | O |
| `referencePremiumRate`, `premiumRateDelta` | `close_premium_rate` | O |
| `koreaLegGrossPnlKrw`, `koreaLegNotionalKrw` | `close_korea_price` | O |
| `foreignLegGrossPnlKrw` | `close_foreign_price`, `close_fx_rate` | O |
| `totalGrossPnlKrw`, `grossPnlPercentOfKoreaNotional`, `isGrossProfit` | 위 값들의 파생 | O |
| `Detail.closedAt` | `closed_at` | O |
| `entryPremiumRate` | 진입 시 확정, `NOT NULL` | 해당 없음 |
| `priceBasis`, `pnlBasis`, `calculatedAt` | 파생·상수·`Clock` | 해당 없음 |

| `close_price_source` | 생성 경로 | 확정 손익 |
|---|---|---|
| `MARKET_SNAPSHOT` | 이 Phase 이후의 archive, snapshot 신선 | 제공 |
| `SNAPSHOT_UNAVAILABLE` | 이 Phase 이후의 archive, snapshot 부재·stale | 미제공 |
| `LEGACY_UNKNOWN` | `V15`가 백필한 기존 종료 행 | 미제공 |
| `NULL` | **`V15` 적용 후 이전 image가 종료시킨 행** — 이전 image는 `status`만 쓰고 신규 컬럼을 모르므로 `NULL`이 남는다. 롤백했거나 API 교체 중 이전 image가 아직 요청을 처리한 구간에서 발생한다 | 미제공 |

`closed_at`도 같은 이유로 `NULL`일 수 있다. 이 Phase의 archive는 항상 기록하지만(주입된 `Clock`) 이전
image가 종료시킨 행에는 없다. `NULL`이면 종료 시각을 추정하지 않고 "종료 시각 불명"으로 표시한다.
`updatedAt`으로 대체하지 않는다 — 그것은 종료 시각이 아니다.

`V15`는 재실행되지 않으므로 이런 행을 자동 보정하지 않는다. 보정하지 않는 것이 옳다: 없는 시세를 만들어
채우는 것은 `SEM-4`가 금지하는 거짓 확정이다.

**신선도.** 기준은 기록 생성과 같은 60초(`SNAPSHOT_MAX_AGE_SECONDS`)다. 기록 생성은 stale이면 **거절**하고,
종료는 **거절하지 않고 `SNAPSHOT_UNAVAILABLE`로 기록**한다. 생성은 사용자가 재시도하면 되지만 종료는
사용자의 실제 행위를 사후 기록하는 것이라 막을 근거가 없다.

**요청·응답 계약 (세 문서의 단일 출처).** `409`가 어느 요청에 붙는지를 여기서 고정한다.

| 요청 | 조건 | 응답 |
|---|---|---|
| `POST /trackings/{id}/archive` | snapshot 신선 | `200` + `close_price_source = MARKET_SNAPSHOT` |
| `POST /trackings/{id}/archive` | snapshot 부재·stale | **`200`** + `close_price_source = SNAPSHOT_UNAVAILABLE` |
| `POST /trackings/{id}/archive` | 이미 `ARCHIVED` (동시 요청의 패자 포함) | `400 INVALID_TRACKING` |
| `GET /trackings/{id}/gross-pnl` | `ACTIVE` | `200`, `priceBasis = CURRENT_MARKET` |
| `GET /trackings/{id}/gross-pnl` | `ARCHIVED` + 확정 판정 통과 | `200`, `priceBasis = ARCHIVED_SNAPSHOT` |
| `GET /trackings/{id}/gross-pnl` | `ARCHIVED` + 확정 판정 실패 | `409 TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE` |

**`archive`는 어떤 경우에도 시세를 이유로 `409`를 반환하지 않는다.** 확정 실패는 종료를 막는 사유가 아니라
그 종료가 확정 손익을 갖지 못한다는 사실일 뿐이며, 그 사실은 `gross-pnl` 조회 시점에 드러난다.

#### 5.3.3 `gross-pnl` 응답 스키마

```json
{
  "trackingId": 1,
  "priceBasis": "CURRENT_MARKET",
  "pnlBasis": "GROSS_EXCLUDING_FEES_FUNDING_SLIPPAGE_FX_SPREAD",
  "entryPremiumRate": 3.50,
  "referencePremiumRate": 2.10,
  "premiumRateDelta": -1.40,
  "koreaLegGrossPnlKrw": 120000,
  "foreignLegGrossPnlKrw": -35000,
  "totalGrossPnlKrw": 85000,
  "koreaLegNotionalKrw": 8500000,
  "grossPnlPercentOfKoreaNotional": 1.00,
  "isGrossProfit": true,
  "calculatedAt": "2026-08-02T05:00:00Z",
  "observedAt": "2026-08-02T04:59:58Z"
}
```

필드 대응과 근거:

| 현재 | 변경 후 | 근거 |
|---|---|---|
| `positionId` | `trackingId` | D1 |
| `currentPremiumRate` | `referencePremiumRate` | `ARCHIVED`에서는 "현재"가 아니라 확정 시점 값이다 |
| `premiumDiff` | `premiumRateDelta` | 부호 방향을 §5.4.2가 문서·화면에서 설명한다 |
| `koreaPnl` | `koreaLegGrossPnlKrw` | 통화와 gross 여부를 이름이 말한다 |
| `foreignPnlKrw` | `foreignLegGrossPnlKrw` | 〃 |
| `totalPnlKrw` | `totalGrossPnlKrw` | 〃 |
| `koreaCurrentValue` | `koreaLegNotionalKrw` | 명목가임을 명시 |
| `totalPnlPercent` | `grossPnlPercentOfKoreaNotional` | **분모가 무엇인지 이름이 말한다** (`SEM-4`) |
| `isProfit` | `isGrossProfit` | gross 기준 판정임을 명시 |
| — | `priceBasis` | `CURRENT_MARKET` \| `ARCHIVED_SNAPSHOT` |
| — | `pnlBasis` | 제외 항목을 값 자체가 열거 |
| — | `observedAt` | 계산에 쓴 시세의 관측 시각 |

`ACTIVE`는 `priceBasis: CURRENT_MARKET`으로 최신 snapshot을 쓰고, `ARCHIVED`는
`priceBasis: ARCHIVED_SNAPSHOT`으로 저장된 확정값만 쓴다. 계산식 자체는 바뀌지 않는다 — Phase 0은 손익
**모델**을 바꾸지 않는다.

#### 5.3.4 도메인 계약

```kotlin
// Tracking
fun archive(snapshot: TrackingCloseSnapshot?, archivedAt: Instant)   // snapshot이 null이면 SNAPSHOT_UNAVAILABLE
fun grossPnl(reference: TrackingPriceReference): TrackingGrossPnl    // ACTIVE는 현재 시세, ARCHIVED는 저장값
```

이미 `ARCHIVED`인 추적을 다시 `archive`하면 `InvalidTrackingException` → `INVALID_TRACKING`이다(현재
`Position.close()`의 동작과 동일).

#### 5.3.5 확정의 단일성 (동시성)

"종료 시점 시세를 확정한다"는 계약은 **정확히 한 번만** 확정될 때 성립한다. 현재 `BaseEntity`에는
optimistic lock version이 없다. 두 요청이 같은 추적을 동시에 archive하면 둘 다 `ACTIVE`를 읽고 둘 다
성공해 마지막 쓰기가 앞선 스냅샷을 덮어쓴다. 첫 요청이 사용자에게 돌려준 확정 손익과 DB에 남은 값이
달라지므로 계약이 깨진다. 화면의 종료 버튼 연타로 충분히 재현된다.

**해결**: archive 트랜잭션은 행을 잠근 뒤 상태를 검사한다.

```kotlin
// TrackingRepository
fun findByIdAndDeletedAtIsNullForUpdate(id: Long): Tracking?   // SELECT ... FOR UPDATE
```

- **이름과 쿼리가 soft-delete 필터를 함께 가진다.** 기존 조회는 전부 `deletedAt IS NULL`을 건다
  (`findByIdAndDeletedAtIsNull`, `SpringDataPositionRepository`의 두 JPQL). 잠금 경로만 이 필터를 빠뜨리면
  **이미 삭제돼 보이지 않는 record를 archive해 상태와 스냅샷을 바꿀 수 있다** — 기존 가시성 경계를 archive
  경로에서만 우회하는 결과다. `findByIdForUpdate`라는 이름은 그 필터를 강제하지 못하므로 쓰지 않는다
- **잠금 뒤에도 소유권을 검증한다.** 잠금은 동시성을 위한 것이지 인가가 아니다. 순서는
  잠금 → 소유권 검증 → 상태 검사 → snapshot 조회 → 확정 → 저장이며 전부 한 트랜잭션이다
- 경쟁에서 진 요청은 `ARCHIVED`를 보고 `INVALID_TRACKING`을 받는다. 조용히 성공시키지 않는다
- `BaseEntity`에 `@Version`을 추가하지 않는다. 모든 Entity에 파급되는 변경이고 Phase 0 범위 밖이다.
  필요한 것은 이 한 경로의 선형화이므로 행 잠금이 정확한 도구다
- 조회 경로(`findById`, 목록, `gross-pnl`)는 잠그지 않는다

**부류 규칙:** 이 Phase가 추가하는 모든 조회 경로는 기존 경로가 거는 필터(`deletedAt IS NULL`)와 인가
검사(소유권)를 그대로 갖는다. 새 경로가 기존 계약의 예외가 되지 않는다.

`gross-pnl`은 확정 이후 읽기 전용이므로 추가 잠금이 필요 없다. 기록 생성은 확정 계약이 아니므로 대상이
아니다 — 같은 추적을 두 번 기록하면 두 개의 독립 record가 생기는 것이 맞다.

### 5.4 표현 정렬

#### 5.4.1 화면 문구 (`P0-O1`, `SEM-1`)

| 위치 | 현재 | 변경 후 |
|---|---|---|
| `Header.tsx` nav | `포지션` | `포지션 기록` |
| 목록 페이지 h1 | (없음) | `포지션 기록` + 부제 `다른 곳에서 실제로 연 포지션을 직접 적어 두고 손익을 보는 기능입니다. 이 화면은 주문을 내지 않습니다.` |
| 폼 제목 | `포지션 열기` | `포지션 기록 추가` |
| 폼 제출 (AUTO) | `포지션 열기 (AUTO)` | `현재 시세로 기록` |
| 폼 제출 (MANUAL) | `포지션 열기 (MANUAL)` | `입력값으로 기록` |
| 종료 확인 | `포지션을 종료하시겠습니까?` | `이 기록을 종료하시겠습니까? 종료 시점 시세가 확정 저장되며 실제 주문은 발생하지 않습니다.` |
| 상태 배지 | `열림` / `종료` | `추적 중` / `종료됨` |
| 탭 | `열린 포지션` / `이력` | `추적 중` / `종료된 기록` |
| 요약 카드 | `전체 / 열린 / 종료 포지션` | `전체 기록 / 추적 중 / 종료됨` |

경로도 `apps/web/src/app/positions/` → `apps/web/src/app/trackings/`로 옮기고 컴포넌트를 `TrackingList.tsx`,
`RecordTrackingForm.tsx`로 rename한다.

#### 5.4.2 손익 표시 (`P0-O2`, `SEM-3`, `SEM-4`)

| 위치 | 현재 | 변경 후 |
|---|---|---|
| 목록 컬럼 | `현재 PnL` | `gross 손익` + 각주 |
| 상세 손익 카드 제목 | (없음) | `gross 손익` |
| 각주 (두 화면 공통) | (없음) | `수수료·펀딩비·슬리피지·환전 스프레드가 반영되지 않은 값입니다. 계정 손익이나 실제 체결 손익이 아닙니다.` |
| 백분율 라벨 | `%` | `한국 leg 명목가 대비 %` |
| 레버리지 행 | `레버리지` | `레버리지` + 각주 `필요 증거금에만 영향을 주며 위 손익 금액에는 반영되지 않습니다.` |
| 프리미엄 변화 | `premiumDiff` 숫자만 | `진입 대비 프리미엄 변화` + `이 조합(한국 롱 / 해외 숏)은 프리미엄이 축소될 때 이익입니다.` |
| `ARCHIVED` 손익 | 현재 시세로 계산된 변동값 | `종료 시점(<close_observed_at>) 확정값` 배지와 함께 고정 표시 |
| `LEGACY_UNKNOWN`·`SNAPSHOT_UNAVAILABLE` | (해당 없음) | `종료 시점 시세를 확정하지 못해 손익을 제공하지 않습니다.` |

`SEM-3`은 이미 폼의 `한국 (롱)`·`해외 (숏)`이 충족한다. 부족한 것은 **그 방향이 프리미엄과 어떤 부호
관계인지**이며 위 `프리미엄 변화` 행이 그것을 채운다.

#### 5.4.3 문서

- `http/api/positions.http` → `http/api/trackings.http`. 모든 요청을 새 경로로 갱신하고 각 요청 위에 실제
  주문이 아님을 한 줄로 적는다 (`.ai/rules/http.md`)
- `README.md`, `.ai/context/project-overview.md`, `.ai/PROJECT_STATUS.md`, `apps/api/docs/instructions.md`의
  Position 서술을 추적 record 의미로 갱신

### 5.5 identity 판정 기록 (`P0-O3`)

Phase 1이 소비할 판정이다. 코드는 바꾸지 않는다.

**현재 정본 identity**

```text
MarketPair = (symbol, koreaExchange, foreignExchange)
canonicalKey = "BTC:BITHUMB:BINANCE"
```

**`ARCH-9` 대비 누락**

| 요구 | 현재 기록 위치 | 상태 |
|---|---|---|
| 한국 leg instrument class (현물) | 없음 | 암묵 전제 |
| 한국 leg quote currency (KRW) | 없음 | 암묵 전제 |
| 해외 leg instrument class (무기한선물) | 없음 | 암묵 전제 |
| 해외 leg quote currency (USDT) | 없음 | 암묵 전제 |

**판정**

1. 기존 수집·저장 자산(`ticker`, `premium`, `premium_minute/hour/day`, Redis premium key)은
   **`BITHUMB` KRW 현물 × `BINANCE` USDT 무기한선물이라는 단일 조합의 관측**으로만 유효하다. 같은
   `canonicalKey`가 다른 instrument 조합을 가리키게 될 경우 과거 데이터와 신규 데이터를 구분할 수단이 없다.
2. 따라서 기존 자산은 **이 조합에 한해** Phase 1 이후의 입력으로 쓸 수 있다. 조합이 늘어나는 순간부터의
   데이터만 확장된 identity를 갖는다.
3. identity 확장은 **Phase 1**이 최소 수집 계약(`P1-O1`)과 함께 수행한다. 확장은 최소한 `MarketPair`,
   Redis premium key 포맷, `premium`·`premium_minute`·`premium_hour`·`premium_day` 스키마,
   `notification_subscription`, `docs/runbooks/redis-contract.md`에 파급된다.
4. 두 leg의 가격 의미가 다르다는 사실(한국=체결가, 해외=호가 중간값)도 `P1-O1`이 함께 다룬다. Phase 0은
   이 차이를 판정에 기록만 한다.

### 5.6 dead·미연결 계약 처리 (`P0-O4`)

| 대상 | 결정 | 근거 |
|---|---|---|
| `Exchange.UPBIT` | **유지 + 미연결 명시** | 수집·표시 경로가 없다는 사실을 enum 주석과 이 문서에 기록한다. 제거하면 pair 분리를 검증하는 통합 테스트가 유일한 두 번째 한국 거래소를 잃는다. 실제 연결 여부는 Phase 1의 수집 계약이 재판정한다 |
| `Tracking.foreignLeverage` | **유지 + 의미 명시** | dead가 아니다. 선형 무기한선물에서 수량이 고정이면 손익 금액은 레버리지와 무관하고 레버리지는 필요 증거금만 바꾼다. 계산에 안 쓰이는 것이 정상이며, 오해를 낳던 것은 그 사실이 어디에도 없었다는 점이다. §5.4.2의 각주가 해소한다 |
| `TrackingRepository.findAllActive()` / `findAllByStatus` | **제거** | Facade 사용처 0건. 유일한 호출자가 자기 자신을 검증하는 테스트다. `memberId` 없는 전체 조회는 소유권 검증을 우회하는 형태라 남겨 둘 이유가 없다 |
| `GET /pnl`의 상태 무가드 | **수정** | D2가 해소 |

### 5.7 As-Is와 Planned 분리 (`P0-O5`, `ARCH-7`)

- `.ai/architecture/ARCHITECTURE_DESIGN.md` 머리말의 As-Is 기준일을 이 PR 시점으로 갱신하고,
  **`## Planned capability`** 절을 추가해 자동매매 계획이 `docs/work/private-live-autotrader/design.md`에
  있음과 현재 문서가 다루지 않는 범위(전략 실행, private adapter, LIVE 계약)를 명시한다.
- 같은 절에 `ARCH-7`의 경계를 적는다: API/Web은 관찰·제어 surface를 제공하되 전략 실행과 private adapter를
  직접 구현하지 않는다. Phase 0 시점의 API/Web은 **관찰 surface만** 갖는다.
- `docs/work/private-live-autotrader/README.md`에 As-Is 문서로의 역참조를 추가한다. 동결된 마스터
  `design.md`는 건드리지 않는다 — 역참조는 프로그램 문서 목록의 성격이므로 랜딩 문서가 소유하는 편이 맞고,
  동결 산출물의 무변경 검사(`dod.md` AC17)와도 충돌하지 않는다.

### 5.8 배포 호환성

`docs/runbooks/deployment.md`의 배포 순서는 API 교체 → API readiness → Batch 교체 → **Web/Nginx 교체** →
smoke이고, 실패 시 이전 성공 SHA의 API·Batch·Web image를 **함께** 되돌린다. DB down migration은 하지 않는다.

Phase 0의 모든 변경을 이 계약 기준으로 분류한다.

| 변경 | 이전 image 호환 | 판정 |
|---|---|---|
| `V15` 컬럼 추가 (전부 nullable) | 이전 image가 무시 | **안전** — runbook이 명시한 허용 범위 |
| `V15`의 `close_price_source` 백필 | 신규 컬럼만 채움 | **안전** |
| REST 경로 `/positions` → `/trackings` | 이전 web image는 404 | **롤백은 안전**(세 image를 함께 되돌린다). 그러나 **정상 배포마다 4~7단계 구간에 단절이 생긴다** — 아래에서 별도로 다룬다 |
| 에러코드·응답 필드 rename | 〃 | 〃 |
| `status` 컬럼 값 재작성 | **이전 image가 `valueOf` 실패 → 롤백 불가** | **금지. 채택하지 않는다** (D4) |

정리하면 Phase 0이 DB에 남기는 것은 **추가 컬럼뿐**이고, 나머지는 함께 배포·함께 롤백되는 application
계약 변경이다. 이 원칙이 깨지는 유일한 후보였던 상태값 재작성을 D4가 제거했다.

**이전 image가 남기는 데이터.** `V15` 적용 후 이전 image가 종료시킨 행은 `status`만 바뀌고 신규 컬럼이
`NULL`로 남는다. 이는 롤백 호환의 대가이며 §5.3.2의 확정 판정 규칙이 fail-closed로 처리한다. "이전 image가
쓴 데이터를 새 image가 읽는 경로"는 이 한 규칙으로 전부 덮인다.

#### 배포 중 단절 — 수용하고 기록한다

배포 순서는 API 교체 → readiness → Batch → Web이므로, 그 구간에 **이전 Web이 새 API를 호출해 404를 받는다.**
정상 배포마다 발생하는 가용성 회귀이고 롤백 가능성과는 별개 문제다.

호환 alias(`/positions`를 한 배포 동안 유지)로는 해결되지 않는다. 경로만 살려도 **응답 계약이 함께
바뀌기 때문이다** — 이전 Web은 `totalPnlKrw`·`isProfit`·`status: "OPEN"`을 읽고 새 API는
`totalGrossPnlKrw`·`isGrossProfit`·`status: "ACTIVE"`를 준다. `308` 리다이렉트도 같은 이유로 무의미하다.
Web을 먼저 교체하는 순서 역전도 대칭적으로 깨진다(새 Web이 이전 API에 `/trackings`를 호출).

즉 **경로·응답을 함께 바꾸는 이상 순차 교체 구간의 단절은 제거할 수 없다.** 제거하려면 세 image를 원자적으로
전환하는 배포 절차가 필요하고, 그것은 이 Phase의 범위가 아니다.

**결정: 수용한다.** 근거는 다음과 같다.

- 영향 범위가 소유자 본인의 추적 화면 하나이고, 이 제품은 개인 단일 계정이다
- 단절 구간은 API 기동~Web 교체 사이 수 분이며 배포 시점은 소유자가 정한다
- 대안(영구 alias)은 `SEM-1`이 없애려는 이름을 계속 살려 두므로 Phase 0의 목적과 정면 충돌한다

`understanding.md`와 PR 본문에 "이 배포에는 수 분의 Web 단절이 있다"를 명시한다. 조용히 넘기지 않는다.

## 6. 미해결 결정 (Phase 1 이월)

Phase 0이 발견했으나 이 Phase가 결정하지 않는 항목이다. 상위 spec §5 Phase 1 진입 조건은 "Phase 0에서
발견된 미해결 사항이 Phase 1의 설계를 차단하지 않는다"이므로, 아래는 모두 **Phase 1 설계의 입력**이지
차단 요소가 아니다.

| # | 항목 | 이월 대상 |
|---|---|---|
| `OPEN-1` | `MarketPair`에 instrument class·quote currency를 어떤 형태로 추가하고 기존 key/스키마를 어떻게 이행할지 | Phase 1 (`P1-O1`, `P1-O7`) |
| `OPEN-2` | 한국 leg 체결가와 해외 leg 호가 중간값의 의미 차이를 어떤 관측으로 맞출지 | Phase 1 (`P1-O1`) |
| `OPEN-3` | 수수료·펀딩비·슬리피지를 반영한 순손익 모델 | Phase 1 (`P1-O5`) |
| `OPEN-4` | `Exchange.UPBIT`의 실제 연결 또는 제거 | Phase 1 (수집 계약 확정 시) |
| `OPEN-5` | `SNAPSHOT_UNAVAILABLE`·`LEGACY_UNKNOWN` 기록을 사후 backfill할지 여부 | Phase 1 (`P1-O3` 품질 상태) |

## 7. Outcome 추적

상위 spec이 Phase 0에 배정한 계약과 이 문서의 대응이다. 빈칸이 없어야 `FOUNDATION_ALIGNED`를 선언할 수 있다.

| 계약 | 근거 절 | 검증 |
|---|---|---|
| `P0-O1` 비주문 추적 record임이 API·Web·문서에서 일치 | §5.1, §5.2, §5.4.1, §5.4.3 | `AC1`, `AC2`, `AC9` |
| `P0-O2` premium 방향과 PnL 보장 범위가 모순 없이 설명 | §5.3, §5.4.2 | `AC3`, `AC4`, `AC5`, `AC25` |
| `P0-O3` `ARCH-9` identity 차이 판정 | §5.5 | `AC6` |
| `P0-O4` dead·미연결 계약의 유지·수정·제거 결정 | §5.6 | `AC7` |
| `P0-O5` As-Is와 Planned 구분 | §5.7 | `AC8` |
| `SEM-1` 수동 추적 기능으로 일관 표현 | §5.1, §5.2, §5.4.1 | `AC1`, `AC2` |
| `SEM-2` 추적 record ≠ execution record | §5.2 (`Position` 이름 해방) | `AC2` |
| `SEM-3` 진입·청산 방향 설명 무모순 | §5.4.2 | `AC4` |
| `SEM-4` gross snapshot임을 드러내고 계정 ROI로 표시하지 않음 | §5.3.3, §5.4.2 | `AC3`, `AC4` |
| `ARCH-7` API/Web은 관찰·제어 surface만 | §5.7 | `AC8` |
| `ARCH-9` 정본 identity 판정 (Phase 0 몫) | §5.5 | `AC6` |

## 8. 종료 조건

`FOUNDATION_ALIGNED`는 다음이 모두 참일 때 선언한다.

1. `dod.md`의 자동 수용기준이 전부 GREEN이고 저장소 gate(`test`, `architectureTest`, `integrationTest`,
   `verifyMigrations`, 문서 검사)가 통과한다.
2. 기존 기능이 자동 주문이나 net/account PnL을 제공하는 것처럼 오해되지 않는다 — `trackings` 리소스명,
   `gross` 접두 필드, `pnlBasis`, 화면 각주가 그 근거다.
3. Phase 1이 사용할 baseline(§5.5 판정)과 미해결 결정(§6)이 명시돼 있다.
4. 사용자가 승인하고 `progress.md`에 `FOUNDATION_ALIGNED`가 append된다.
