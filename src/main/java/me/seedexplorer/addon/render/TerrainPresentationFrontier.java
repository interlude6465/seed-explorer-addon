package me.seedexplorer.addon.render;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Keeps first-pass terrain visible in dispatch order while workers finish in parallel. */
final class TerrainPresentationFrontier {
    private final ConcurrentHashMap<Long, Long> sequenceByChunk = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> chunkBySequence = new ConcurrentHashMap<>();
    private final Set<Long> readySequences = new HashSet<>();
    private final ConcurrentLinkedQueue<Long> newlyRevealedChunks =
        new ConcurrentLinkedQueue<>();
    private long nextSequence;
    private volatile long revealedThrough = -1L;

    synchronized long sequenceFor(long chunkKey) {
        Long existing = sequenceByChunk.get(chunkKey);
        if (existing != null) return existing;
        long sequence = nextSequence++;
        sequenceByChunk.put(chunkKey, sequence);
        chunkBySequence.put(sequence, chunkKey);
        return sequence;
    }

    synchronized void markReady(long chunkKey) {
        Long sequence = sequenceByChunk.get(chunkKey);
        if (sequence == null || sequence <= revealedThrough) return;
        readySequences.add(sequence);
        while (readySequences.remove(revealedThrough + 1L)) {
            long revealed = ++revealedThrough;
            Long key = chunkBySequence.remove(revealed);
            if (key != null) {
                sequenceByChunk.remove(key, revealed);
                newlyRevealedChunks.add(key);
            }
        }
    }

    boolean hasSequence(long chunkKey) {
        return sequenceByChunk.containsKey(chunkKey);
    }

    boolean isRevealed(long chunkKey) {
        Long sequence = sequenceByChunk.get(chunkKey);
        return sequence == null || sequence <= revealedThrough;
    }

    Long pollNewlyRevealed() {
        return newlyRevealedChunks.poll();
    }

    synchronized void reset() {
        sequenceByChunk.clear();
        chunkBySequence.clear();
        readySequences.clear();
        newlyRevealedChunks.clear();
        nextSequence = 0L;
        revealedThrough = -1L;
    }

    long revealedThrough() {
        return revealedThrough;
    }

    int trackedChunks() {
        return sequenceByChunk.size();
    }
}
