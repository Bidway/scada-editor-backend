package com.example.runtime.archive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Ограниченная очередь без блокировок на вставке: полна — элемент сброшен и посчитан. */
public class BoundedQueue<T> {

    private final ArrayBlockingQueue<T> queue;
    private final int capacity;
    private final AtomicLong dropped = new AtomicLong();

    public BoundedQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** false — очередь полна, элемент сброшен и учтён в {@link #dropped()}. */
    public boolean offer(T item) {
        if (queue.offer(item)) {
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    public List<T> drain(int max) {
        List<T> out = new ArrayList<>(Math.min(max, queue.size()));
        queue.drainTo(out, max);
        return out;
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public long dropped() {
        return dropped.get();
    }
}
