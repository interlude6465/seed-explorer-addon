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
        this(width,height,format,minFilter,magFilter,1);
    }

    public GpuTextureResource(int width,int height,GpuFormat format,FilterMode minFilter,FilterMode magFilter,int mipLevels) {
        texture = RenderSystem.getDevice().createTexture(
            "Seed Explorer terrain atlas", 15, format, width, height, 1, mipLevels);
        sampler = RenderSystem.getSamplerCache().getSampler(
            AddressMode.REPEAT, AddressMode.REPEAT,
            minFilter, magFilter, mipLevels > 1);
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

    /** CPU mip generation runs once per atlas, with sprite gutters preventing color bleed. */
    public void uploadMipmapped(ByteBuffer buffer,int levels) {
        NativeImage previous=createUploadImage();
        buffer.rewind();MemoryUtil.memCopy(MemoryUtil.memAddress(buffer),previous.getPointer(),buffer.remaining());
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(texture,previous);
        try {
            for(int level=1;level<levels;level++) {
                NativeImage next=new NativeImage(Math.max(1,width()>>level),Math.max(1,height()>>level),false);
                for(int y=0;y<next.getHeight();y++)for(int x=0;x<next.getWidth();x++) {
                    int a=previous.getPixel(x*2,y*2),b=previous.getPixel(Math.min(x*2+1,previous.getWidth()-1),y*2);
                    int c=previous.getPixel(x*2,Math.min(y*2+1,previous.getHeight()-1));
                    int d=previous.getPixel(Math.min(x*2+1,previous.getWidth()-1),Math.min(y*2+1,previous.getHeight()-1));
                    int color=0;for(int shift=0;shift<=24;shift+=8)
                        color|=((((a>>>shift)&255)+((b>>>shift)&255)+((c>>>shift)&255)+((d>>>shift)&255)+2)/4)<<shift;
                    next.setPixel(x,y,color);
                }
                encoder.writeToTexture(texture,next,level,0,0,0);
                previous.close();previous=next;
            }
        } finally {previous.close();}
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
