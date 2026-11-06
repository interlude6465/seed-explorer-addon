package me.seedexplorer.addon.render;

/** Constant-memory square spiral used by the background terrain streamer. */
final class TerrainStreamingCursor {
    private int ring;
    private int index;

    void reset() {
        ring = 0;
        index = 0;
    }

    int ring() {
        return ring;
    }

    State snapshot() {
        return new State(ring, index);
    }

    void restore(State state) {
        if (state == null || state.ring() < 0 || state.index() < 0
            || (state.ring() > 0 && state.index() >= state.ring() * 8)) {
            reset();
            return;
        }
        ring = state.ring();
        index = state.index();
    }

    Coordinate next(int centerX, int centerZ) {
        if (ring == 0) {
            ring = 1;
            return new Coordinate(centerX, centerZ, 0);
        }

        int currentRing = ring;
        int side = currentRing * 2;
        int offset = index / 4;
        int edge = index & 3;
        int dx;
        int dz;
        switch (edge) {
            case 0 -> {
                dx = -currentRing + offset;
                dz = -currentRing;
            }
            case 1 -> {
                dx = currentRing;
                dz = -currentRing + offset;
            }
            case 2 -> {
                dx = currentRing - offset;
                dz = currentRing;
            }
            default -> {
                dx = -currentRing;
                dz = currentRing - offset;
            }
        }

        index++;
        if (index >= currentRing * 8) {
            ring++;
            index = 0;
        }
        return new Coordinate(centerX + dx, centerZ + dz, currentRing);
    }

    record Coordinate(int chunkX, int chunkZ, int ring) {
    }


    record State(int ring, int index) {
        long completedPositions() {
            if (ring <= 0) return 0L;
            long completedSquareWidth = (long) ring * 2L - 1L;
            return completedSquareWidth * completedSquareWidth + index;
        }
    }
}
