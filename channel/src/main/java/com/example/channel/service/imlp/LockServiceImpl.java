package com.example.channel.service.imlp;

import com.example.channel.service.LockService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class LockServiceImpl implements LockService {

    private final RedisTemplate<String, String> redis;

    private static final long LOCK_TTL_SECONDS = 300;

    /** Префикс, чтобы локи не делили пространство ключей с остальными данными в Redis. */
    private static final String KEY_PREFIX = "lock:";

    /**
     * Compare-and-delete одним обращением к Redis: снимаем лок, только если он ещё наш.
     * Возвращает 1, если ключа уже нет (свободен) либо он принадлежал нам и удалён;
     * 0 — если лок держит кто-то другой (тогда чужой лок не трогаем).
     * Атомарность скрипта исключает гонку между проверкой владельца и удалением.
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) " +
                    "if v == false then return 1 end " +
                    "if v == ARGV[1] then redis.call('del', KEYS[1]) return 1 end " +
                    "return 0",
            Long.class);

    public LockServiceImpl(RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    @Override
    public List<String> tryLock(List<String> idNodes, Long userId) {
        List<String> acquired = new ArrayList<>();
        String owner = userId.toString();
        idNodes.forEach(idNode -> {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    KEY_PREFIX + idNode,
                    owner,
                    Duration.ofSeconds(LOCK_TTL_SECONDS));
            if (Boolean.TRUE.equals(ok)) {
                acquired.add(idNode);
            }
        });
        return acquired;
    }

    @Override
    public List<String> unlock(List<String> idNodes, Long userId) {
        List<String> released = new ArrayList<>();
        String owner = userId.toString();
        idNodes.forEach(idNode -> {
            Long result = redis.execute(UNLOCK_SCRIPT, List.of(KEY_PREFIX + idNode), owner);
            if (result != null && result == 1L) {
                released.add(idNode);
            }
        });
        return released;
    }
}
