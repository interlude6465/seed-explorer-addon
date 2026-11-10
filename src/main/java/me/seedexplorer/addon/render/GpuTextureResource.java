package me.seedexplorer.addon.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jspecify.annotations.NonNull;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Addon-owned GPU texture used by the standalone terrain renderer. */
public final class GpuTextureResource extends AbstractTexture {
    public GpuTextureResource(int width, int height, GpuFormat format,
                              FilterMode minFilter, FilterMode magFilter) {
        texture = RenderSystem.getDevice().createTexture(
            "Seed Explorer terrain atlas", 15, format, width, height, 1, 1);
        sampler = RenderSystem.getSamplerCache().getSampler(
            AddressMode.REPEAT, AddressMode.REPEAT,
            minFilter, magFilter, false);
        textureView = RenderSystem.getDevice().createTextureView(texture);
    }

    public int width() {
        return getTexture().getWidth(0);
    }

    public int height() {
        return getTexture().getHeight(0);
    }

    public void upload(ByteBuffer buffer) {
        try (NativeImage image = createUploadImage()) {
            buffer.rewind();
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), image.getPointer(), buffer.remaining());
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
        }
    }

    public static GpuTextureResource readResource(String path, boolean flipY,
                                                  FilterMode filter) {
        try (var input = GpuTextureResource.class.getResourceAsStream(path)) {
            if (input == null) return null;
            ByteBuffer encoded = TextureUtil.readResource(input).rewind();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer components = stack.mallocInt(1);
                STBImage.stbi_set_flip_vertically_on_load(flipY);
                try {
                    ByteBuffer pixels = STBImage.stbi_load_from_memory(
                        encoded, width, height, components, 4);
                    if (pixels == null) return null;
                    try {
                        GpuTextureResource result = new GpuTextureResource(
                            width.get(0), height.get(0), GpuFormat.RGBA8_UNORM,
                            filter, filter);
                        result.upload(pixels);
                        return result;
                    } finally {
                        STBImage.stbi_image_free(pixels);
                    }
                } finally {
                    STBImage.stbi_set_flip_vertically_on_load(false);
                }
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    private @NonNull NativeImage createUploadImage() {
        NativeImage.Format imageFormat = switch (texture.getFormat()) {
            case RGBA8_UNORM -> NativeImage.Format.RGBA;
            case R8_UNORM -> NativeImage.Format.LUMINANCE;
            default -> throw new IllegalArgumentException(
                "Unsupported Seed Explorer texture format: " + texture.getFormat());
        };
        return new NativeImage(imageFormat, width(), height(), false);
    }
}
