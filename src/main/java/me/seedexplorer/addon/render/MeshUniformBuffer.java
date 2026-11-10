package me.seedexplorer.addon.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/** Render-thread-owned projection and model-view uniform storage. */
final class MeshUniformBuffer {
    private static final int SIZE = new Std140SizeCalculator()
        .putMat4f()
        .putMat4f()
        .get();

    private DynamicGpuDataStorage<Data> storage;
    private final Matrix4f projection = new Matrix4f();
    private final Data data = new Data();

    Matrix4f projection() {
        return projection;
    }

    void beginFrame() {
        if (storage != null) storage.endFrame();
    }

    GpuBufferSlice write(Matrix4f modelView) {
        if (storage == null) {
            storage = new DynamicGpuDataStorageMapped<>(
                "Seed Explorer Mesh UBO", SIZE, GpuBuffer.USAGE_UNIFORM, 16);
        }
        data.projection = projection;
        data.modelView = modelView;
        return storage.writeData(data);
    }

    private static final class Data implements DynamicGpuDataStorage.DynamicGpuData {
        private Matrix4f projection;
        private Matrix4f modelView;

        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                .putMat4f(projection)
                .putMat4f(modelView);
        }

        @Override
        public boolean equals(Object other) {
            return false;
        }
    }
}
