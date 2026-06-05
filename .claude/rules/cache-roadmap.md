# Cache 학습 로드맵

이 프로젝트는 학습 목적으로 캐시를 단계적으로 도입하고 있다. 새 세션에서 캐시 관련 작업을 이어갈 때 이 문서를 참고할 것.

## 진행 완료

### 1단계: 로컬 캐시 적용 (완료)
- Spring Cache 추상화 학습 (전략 5가지, evict vs put, 복합 키 등)
- ConcurrentMapCacheManager로 ProductService에 캐시 적용
- 관련 커밋: `5413c03`

### 2단계: Redis 기초 (완료)
- Redis 개념 학습 (인메모리 DB, 자료구조: String, Hash, List, Set, Sorted Set)
- Docker로 로컬 Redis 설치, CLI 체험 (SET, GET, TTL, KEYS 등)

### 3단계: Spring + Redis 캐시 전환 (완료)
- spring-boot-starter-data-redis 의존성 추가
- CacheConfig에서 RedisCacheManager 설정 (TTL 10분, GenericJackson2JsonRedisSerializer)
- ConcurrentMap → Redis 전환 후 동일 동작 확인
- 관련 커밋: `6e1a3c0`

## 현재 진행 중

### 4단계: Redis 활용 심화 — 분산 락

**문제 상황**: `GroupPurchaseService.joinGroupPurchase()`에서 재고 차감(`product.decreaseStockQuantity()`)과 참여자 추가가 동시에 호출될 때 Lost Update 발생 가능. 두 트랜잭션이 같은 시점의 재고를 읽고 각각 차감하면 재고 초과(overselling) 위험.

**학습 목표**:
- DB 비관적 락(SELECT FOR UPDATE), 낙관적 락(@Version)의 개념과 한계 이해
- Redis 분산 락(Redisson)으로 여러 서버 환경에서의 동시성 제어 구현
- 실무에서 어떤 상황에 어떤 방식을 선택하는지 판단 기준 학습

**진행 상황**:
- DB 비관적 락 적용 완료 — GroupPurchase에 `PESSIMISTIC_WRITE` 적용 (커밋 완료)
- DB 낙관적 락 적용 완료 — Product에 `@Version` 추가 (커밋 예정)
- 다음: 동시성 테스트 작성 → Redis 분산 락(Redisson) 구현

**이후 후보 주제**:
- 실시간 카운터 (참여자 수 Redis 카운트)
- Redis Pub/Sub (스케줄러 폴링 → 이벤트 기반 전환)
- Redis 장애 대응 (fallback, circuit breaker)

## 현재 캐시 적용 현황
- **대상**: ProductService (상품 조회/수정/삭제)
- **캐시 키**: `products::{productId}`
- **설정**: `CacheConfig.java` — RedisCacheManager, TTL 10분, JSON 직렬화
- **어노테이션**: 조회 `@Cacheable`, 수정/삭제 `@CacheEvict`

## 학습 TODO (4단계 병행)

### 테스트 코드 작성
- [ ] 동시성 테스트: `ExecutorService` + `CountDownLatch`로 멀티스레드 테스트 작성
- [ ] 비관적 락 테스트: 재고 < 스레드 수 시나리오로 overselling 방지 검증
- [ ] 낙관적 락 테스트: `OptimisticLockException` 발생 확인 및 재시도 없이 실패하는 케이스
- [ ] 테스트 기초: 단위 테스트 vs 통합 테스트, 모킹(Mockito) 개념 학습

### Java 동시성 기초
- [ ] `ExecutorService`: 스레드풀 생성과 관리
- [ ] `CountDownLatch`: 스레드 동시 시작 보장 메커니즘
- [ ] `AtomicInteger` 등 thread-safe 카운터
- [ ] `Future` / `CompletableFuture`: 비동기 결과 수집

## 기술 결정 사항
- Caffeine은 건너뛰고 Redis로 직행 (로컬 캐시 원리는 ConcurrentMap으로 충분히 이해)
- 학습 순서: 캐시 → Redis → MQ → 모니터링