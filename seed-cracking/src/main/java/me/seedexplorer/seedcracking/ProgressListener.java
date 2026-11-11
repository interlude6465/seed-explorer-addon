package me.seedexplorer.seedcracking;

/** Receives coarse progress updates from long-running searches. */
@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = progress -> {
    };

    void onProgress(CrackingProgress progress);
}
