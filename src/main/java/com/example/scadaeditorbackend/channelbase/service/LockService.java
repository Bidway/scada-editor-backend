package com.example.scadaeditorbackend.channelbase.service;

import org.springframework.security.core.Authentication;

import java.util.List;

public interface LockService {
    public List<String> tryLock(List<String> idNodes, Authentication auth);
    public List<String> unlock(List<String> idNodes, Authentication auth);
}
