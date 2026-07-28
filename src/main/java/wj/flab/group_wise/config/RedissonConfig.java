package wj.flab.group_wise.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")   // 테스트 환경(H2, Redis 미기동)에서는 Redisson 빈 생성 제외
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Bean(destroyMethod = "shutdown")   // 앱 종료 시 커넥션 정리
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()    // 단일 서버모드
                .setAddress("redis://" + host + ":" + port); // 주소 형식 주의 : redis://
        return Redisson.create(config);
    }
}
