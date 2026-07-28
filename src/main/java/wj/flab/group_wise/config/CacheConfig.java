package wj.flab.group_wise.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
@Profile("!test")   // 테스트 환경(Redis 미기동)에서는 캐싱 비활성화
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        // BasicPolymorphicTypeValidator : 역직렬화 시 허용할 클래스 범위를 지정 (보안 목적)
        BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class) // 모든 타입 허용 (프로젝트 내부 캐시이므로 넓게 설정)
                .build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule()); // LocalDateTime 등 Java 8 날짜/시간 타입 지원
        mapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL); // JSON에 @class 타입 정보 포함시켜서 역직렬화 가능하게 함
        // typeValidator — 역직렬화할 때 허용할 클래스 범위입니다. 아무 클래스나 복원하면 보안 위험이 있어서 (악의적 클래스 실행 가능) 허용 범위를 지정하는 겁니다.
        // DefaultTyping.NON_FINAL — final이 아닌 모든 타입에 @class 정보를 넣겠다는 뜻입니다.
        //                           String, Integer 같은 final 클래스는 이미 타입이 명확하니까 제외하고, 나머지 커스텀 클래스들에 타입 정보를 포함시킵니다

        RedisCacheConfiguration config = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper))
                        // new GenericJackson2JsonRedisSerializer() 기본 생성자를 쓰면 이 설정이 내부적으로 자동 적용됩니다.
                        // 그런데 ObjectMapper를 직접 만들어서 넘기면 자동 적용이 안 되니까, 직접켜줘야 하는 겁니다.
                );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }


}
