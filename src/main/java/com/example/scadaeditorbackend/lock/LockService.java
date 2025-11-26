package com.example.scadaeditorbackend.lock;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class LockService {

//    private final RedisTemplate<String, String> redis;
//    private static final long LOCK_TTL_SECONDS = 300;
//
//    public LockService(RedisTemplate<String, String> redis) {
//        this.redis = redis;
//    }
//
//    private String getKey(String model, Long id) {
//        return "LOCK:" + model + ":" + id;
//    }
//
//    public boolean tryLock(String model, Long id, Long userId) {
//        String key = getKey(model, id);
//
//        Boolean success = redis.opsForValue().setIfAbsent(
//                key,
//                userId.toString(),
//                Duration.ofSeconds(LOCK_TTL_SECONDS)
//        );
//
//        return Boolean.TRUE.equals(success);
//    }
//
//    public boolean unlock(String model, Long id, Long userId) {
//        String key = getKey(model, id);
//
//        String owner = redis.opsForValue().get(key);
//        if (owner == null) return true;
//
//        if (!owner.equals(userId.toString())) return false;
//
//        redis.delete(key);
//        return true;
//    }
//
//    public boolean isLockedByAnother(String model, Long id, Long userId) {
//        String key = getKey(model, id);
//
//        String owner = redis.opsForValue().get(key);
//
//        return owner != null && !owner.equals(userId.toString());
//    }
}

