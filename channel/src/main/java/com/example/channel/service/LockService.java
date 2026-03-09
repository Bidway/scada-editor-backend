package com.example.channel.service;

import org.springframework.security.core.Authentication;

import java.util.List;

public interface LockService {
    List<String> tryLock(List<String> idNodes, Long userId);
    List<String> unlock(List<String> idNodes, Long userId);
}
