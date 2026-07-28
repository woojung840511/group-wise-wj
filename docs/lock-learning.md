# 동시성 이슈와 여러가지 해결 방안 탐색

## GroupPurchaseService.joinGroupPurchase()에서 발생할 수 있는 문제
- `product.decreaseStockQuantity()`에서 재고 차감과 참여자 추가가 동시에 호출될 때 Lost Update 발생 가능
- 두 트랜잭션이 같은 시점의 재고를 읽고 각각 차감하면 재고 초과(overselling) 위험

### 왜 이런 문제가 발생하는지 분석해보자면:
1. 트랜잭션 A와 B가 거의 동시에 `joinGroupPurchase()`를 호출한다고 가정
2. A와 B가 각각 `product.decreaseStockQuantity()`를 호출하여 현재 재고를 읽음 (예: 10개)
3. A와 B가 각각 1개씩 차감하여 9개로 업데이트
4. 결과적으로 재고가 8개로 줄어야 하는데 9개로 남아버림 (overselling)

```
< 영속성 컨텍스트와 스레드 관계 >

1. 스레드마다 별도의 영속성 컨텍스트
  - Spring 에서 @Transactional 메서드가 호출되면, 해당 스레드에 트랜젝션이 바인딩 되고,
    그 트랜젝션에 영속성 컨텍스트가 1:1 로 매핑된다.
  - 다른 스레드의 영속성 컨텍스트는 서로 완전히 격리되어 있어서, A 스레드가 엔티티를 수정해도
    B 스레드는 그 변경사항을 전혀 알 수 없다. (B 스레드의 영속성 컨텍스트에는 반영되지 않음)
  
2. 같은 레코드를 조회 시점 기준으로 각자 들고 있음
  - 스레드 A에서 ProductStock(quantity=1)을 읽고, 스레드 B에서도 비슷한 시점에 ProductStock(quantity=1)을 읽으면
    각자의 영속성 컨텍스트에 quantity=1 인 스냅샷이 존재하게 된다.
  - 둘다 quantity - 1=0 으로 dirty checking이 발생 -> 커밋 시 UPDATE proudct_stock SET quantity=0 이 두 번 나간다.
  - DB 에는 0이 저장되지만, 실제로는 2명이 차감했으니 -1 이어야 하는 상황
  
```

이 문제는 MySQL의 기본 격리 수준인 REPEATABLE READ에서도 막아줄 수 없다.
REPEATABLE READ는 "같은 트랜젝션 내에서 같은 데이터를 다시 읽으면 같은 결과를 보장"하는 것이지,
"다른 트랜젝션이 동시에 수정하는 것을 막는 것"이 아니다. 그래서 별도의 락 매커니즘이 필요하다.

## 잠금의 종류: 공유 잠금 vs 배타적 잠금

| 잠금 종류 | SQL | 의미 |
|---|---|---|
| 공유 잠금 (Shared Lock, S-Lock) | `SELECT ... FOR SHARE` | 읽기는 허용, 쓰기는 블로킹. "나 읽는 중이니 수정하지 마" |
| 배타적 잠금 (Exclusive Lock, X-Lock) | `SELECT ... FOR UPDATE` | 읽기(락 있는)도 쓰기도 모두 블로킹. "나 수정할 거니까 아무도 건드리지 마" |

호환성 정리:
- S-Lock + S-Lock → 동시 획득 가능 (여러 스레드가 동시에 읽기 OK)
- S-Lock + X-Lock → 불가 (공유 잠금이 풀릴 때까지 배타적 잠금 대기)
- X-Lock + X-Lock → 불가 (하나만 획득 가능, 나머지는 대기)

JPA에서의 매핑:
- `PESSIMISTIC_READ` → 공유 잠금 (FOR SHARE)
- `PESSIMISTIC_WRITE` → 배타적 잠금 (FOR UPDATE)

## 비관적 락 (SELECT FOR UPDATE) 살펴보기

핵심: "내가 이 데이터를 쓰는 동안 다른 누구도 건드리지 못하게 잠근다"

### DB 레벨에서 일어나는 일

```sql
-- 일반 SELECT (락 없음)
SELECT * FROM product_stock WHERE id = 1;

-- 비관적 락 SELECT (해당 row에 배타적 잠금 획득)
SELECT * FROM product_stock WHERE id = 1 FOR UPDATE;
```
- 잠금이 걸린 row는 다른 트랜잭션에서 읽거나 수정하려고 하면 대기 상태가 된다.
  - 예: 트랜잭션 A가 `SELECT ... FOR UPDATE`로 product_stock을 잠그면, 트랜잭션 B가 같은 row를 읽거나 수정하려고 하면 A가 커밋될 때까지 대기한다.
- `FOR UPDATE`를 붙이면 해당 row에 배타적 잠금(exclusive lock)이 걸린다.
- FK 로 잠긴 행을 참조하는 다른 테이블의 row는 잠기지 않는다.
  - 예: product 행에 SELECT FOR UPDATE 걸어도 product_stock 행은 잠기지 않는다.

```
스레드 A                              스레드 B
──────────────────────────────────────────────────
SELECT ... FOR UPDATE → 락 획득
(재고 1개 확인)
                                    SELECT ... FOR UPDATE → 대기 (블로킹!)
quantity = 0 으로 수정
COMMIT → 락 해제
                                    → 이제 락 획득
                                    (재고 0개 확인 ← A가 차감한 최신 값!)
                                    → 재고 부족 예외 발생
```

스레드 B는 A가 커밋할 때까지 SELECT 자체가 블로킹된다.
그래서 항상 최신 데이터를 읽게 되고, Lost Update가 발생하지 않는다.

### JPA에서 사용하는 방법

```java
public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ps FROM ProductStock ps WHERE ps.id = :id")
    Optional<ProductStock> findByIdForUpdate(@Param("id") Long id);
}
```

`@Lock(LockModeType.PESSIMISTIC_WRITE)` → Hibernate가 `SELECT ... FOR UPDATE` 쿼리를 생성해준다.

### 주의할 점

| 항목       | 설명                                                                 |
|------------|----------------------------------------------------------------------|
| 데드락     | A가 row1 잠그고 row2 대기, B가 row2 잠그고 row1 대기 → 교착 상태. 락 순서를 일관되게 해야 함 |
| 성능       | 락을 기다리는 동안 스레드가 블로킹되므로 동시 처리량이 줄어듦                  |
| 락 범위    | 트랜잭션이 끝날 때까지 유지되므로 트랜잭션을 짧게 가져가야 함                  |
| 타임아웃   | 무한 대기를 막기 위해 @QueryHints로 lock timeout 설정 권장                  |

### 비관적 락을 실제 적용하면서 고려한 점 기록 (그리고 DDD 구조와 락) 2026-06-05
- groupPurchaseService.joinGroupPurchase()에서 ProductStock을 조회할 때 `findByIdWithLock()`로 GroupPurchase에 락을 걸어서 재고 차감 시 동시성 문제 해결했다.
  - 공동구매 참여 로직에서 변경이 일어나는 테이블은, 
    - group_purchase.id 참조하는 group_purchase_member
    - group_purchase_member.id 참조하는 product 의 id를 참조하는 product_stock 이다.
- 배타적 락을 적용한 테이블은 GroupPurchase 이다. 그 이유는:
  - 각 변경 테이블에 직접 락을 걸 수도 있겠지만, joinGroupPurchase()에서 가장 먼저 조회하는 테이블이 GroupPurchase 이었다.
  - 빠른 락 획득으로 이후 로직(member, stock 조회 및 수정)을 다른 트랜젝션으로부터 자연스럽게 전부 보호할 수 있었다.
  - 또한 DDD 구조로 member 와 productStock 의 조회가 각각 groupPurchase 와 Product 엔티티에서 일어나기 때문에, 직접적으로 각 테이블에 락을 걸기 위해선 DDD 구조를 풀어야 하는 상황이었다.
  - 결과적으로 GroupPurchase 엔티티에 배타적 락을 걸어서, joinGroupPurchase() 메서드 전체가 하나의 트랜잭션으로 묶이게 되었고, 이로 인해 동시성 문제를 효과적으로 해결할 수 있었다.
  - 다만 Aggregate Root에 락을 걸면, 경합 범위가 넓어지는 트레이드 오프가 있을 수 있다.

### 락 전략 단계 정리
| 단계 | 전략                            |
|----|-------------------------------|
| 기본 | Aggregate Root에 배타적 락 (지금 접근) |
| 성능 이슈 발생 시 | 경합 엔티티에 낙관적 락 (@Version) 추가   |
| 분산 환경 / 고트래픽 | Redis 기반 분산 락 Aggregate 단위 잠금 |
| 극단적 트래픽 | DDD 경계를 조정하거나, CQRS 쓰기 모델 분리 |

## 낙관적 락 살펴보기
- 낙관적 락은 "내가 이 데이터를 수정할 때 다른 사람이 먼저 수정했는지 체크"하는 방식
- 적용 방법:
  - `@Version` 필드를 엔티티에 추가해서 사용한다.
  - 트랜잭션이 커밋될 때, JPA가 `WHERE version = ?` 조건으로 업데이트 쿼리를 날리고, 영향받은 행이 0이면 `OptimisticLockException`을 던진다.
  - 참고 - @Lock(LockModeType.OPTIMISTIC) 
    - 조회 시점에 버전 체크하는 용도로, "이 트랜잭션에서 읽기만 해도 커밋 시점에 version이 변경되었는지 검증해라" 입니다. 즉 수정 없이 조회만
      하는데도 다른 트랜잭션의 수정을 감지하고 싶을 때 쓰는 것이지, 수정하는 경우에는 @Version만으로 충분합니다.
    - 조회 메서드에 선언 → 읽기만 하는 트랜잭션에서도 커밋 시점에 "내가 읽은 version이 아직 유효한가?" 검증
  ```
    // 상품 정보를 읽어서 화면에 보여주는 API
    @Transactional(readOnly = true)
    public ProductViewResponse getProductInfo(Long productId) {
        Product product = productRepository.findByIdWithOptimisticLock(productId); // version=1 읽음
        // ... 응답 조립하는 동안 다른 트랜잭션이 Product 수정 (version 1→2)
        // 이 트랜잭션 커밋 시점에 version 불일치 감지 → OptimisticLockException
    }
  ```
- 적합한 상황:
  - 낙관적 락은 충돌이 드물 때 성능이 좋지만, 충돌이 잦으면 재시도가 필요해서 오히려 성능이 나빠질 수 있다.
