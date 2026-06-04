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

## 다음 단계

### 4단계: Redis 활용 심화
- 캐시 외 Redis 활용: 세션 저장소, 분산 락, 조회수 카운터 등
- 공동구매 프로젝트에 맞는 활용 (예: 참여자 수 실시간 카운트)
- Redis 장애 시 대응 (fallback, circuit breaker 개념)

## 현재 캐시 적용 현황
- **대상**: ProductService (상품 조회/수정/삭제)
- **캐시 키**: `products::{productId}`
- **설정**: `CacheConfig.java` — RedisCacheManager, TTL 10분, JSON 직렬화
- **어노테이션**: 조회 `@Cacheable`, 수정/삭제 `@CacheEvict`

## 기술 결정 사항
- Caffeine은 건너뛰고 Redis로 직행 (로컬 캐시 원리는 ConcurrentMap으로 충분히 이해)
- 학습 순서: 캐시 → Redis → MQ → 모니터링