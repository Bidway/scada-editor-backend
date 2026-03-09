package com.example.channel.service.imlp;

import com.example.channel.service.LockService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class LockServiceImpl implements LockService {

    private final RedisTemplate<String, String> redis;
    private static final long LOCK_TTL_SECONDS = 300;

    public LockServiceImpl(RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public List<String> tryLock(List<String> idNodes, Long userId) {
        List<String> successIdNodes = new ArrayList<>();
        idNodes.forEach(idNode ->{
            if(redis.opsForValue().setIfAbsent(
                    idNode,
                    userId.toString(),
                    Duration.ofSeconds(LOCK_TTL_SECONDS))){
                successIdNodes.add(idNode);
            }
        });

        return successIdNodes;
    }

    public List<String> unlock(List<String> idNodes, Long userId) {
        List<String> successIdNodes = new ArrayList<>();
        idNodes.forEach(idNode ->{
            if(redis.opsForValue().get(idNode)==null)
            {
                successIdNodes.add(idNode);
            } else
            if(redis.opsForValue().get(idNode).equals(userId.toString())){
                redis.delete(idNode);
                successIdNodes.add(idNode);
            }
        });
        return successIdNodes;
    }

//    public boolean isLockedByAnother(String model, Long id, Long userId) {
//        String key = getKey(model, id);
//
//        String owner = redis.opsForValue().get(key);
//
//        return owner != null && !owner.equals(userId.toString());
//    }
}

