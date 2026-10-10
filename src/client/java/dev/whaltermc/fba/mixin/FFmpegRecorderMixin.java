package dev.whaltermc.fba.mixin;

import com.moulberry.flashback.exporting.FlashbackFFmpegFrameRecorder;
import com.llamalad7.mixinextras.sugar.Local;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;

@Mixin(FlashbackFFmpegFrameRecorder.class)
public abstract class FFmpegRecorderMixin {

    @Shadow
    private AVCodec video_codec;

    @Shadow
    private AVCodecContext video_c;

    @Shadow
    private Map<String, String> videoOptions;

    @Inject(
            method = "startUnsafe",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_open2(" +
                            "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;" +
                            "Lorg/bytedeco/ffmpeg/avcodec/AVCodec;" +
                            "Lorg/bytedeco/ffmpeg/avutil/AVDictionary;)I"
            )
    )
    private void flashbackRedroided$configureMediaCodec(
            CallbackInfo ci,
            @Local AVDictionary options
    ) {
        if (video_codec == null || video_codec.name() == null) {
            return;
        }

        String codecName = video_codec.name().getString();

        if (!codecName.endsWith("_mediacodec")) {
            return;
        }

        if (options != null && videoOptions != null && !videoOptions.containsKey("ndk_codec")) {
            av_dict_set(options, "ndk_codec", "1", 0);
        }

        if (video_c != null) {
            video_c.pix_fmt(AV_PIX_FMT_NV12);
        }
    }

    @ModifyVariable(
            method = "recordImage",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 2,
            order = 900
    )
    private int flashbackRedroided$useNv12(int pixelFormat) {
        if (pixelFormat != AV_PIX_FMT_YUV420P) {
            return pixelFormat;
        }

        if (video_codec == null || video_codec.name() == null) {
            return pixelFormat;
        }

        if (!video_codec.name().getString().endsWith("_mediacodec")) {
            return pixelFormat;
        }

        return AV_PIX_FMT_NV12;
    }

    @Inject(
            method = "recordImage",
            at = @At("HEAD"),
            order = 800
    )
    private void flashbackRedroided$convertYuv420pToNv12(
            int width,
            int height,
            int pixelFormat,
            ByteBuffer image,
            CallbackInfo ci
    ) {
        if (pixelFormat != AV_PIX_FMT_YUV420P) {
            return;
        }

        if (video_codec == null || video_codec.name() == null) {
            return;
        }

        if (!video_codec.name().getString().endsWith("_mediacodec")) {
            return;
        }

        int ySize = width * height;
        int chromaSize = ySize / 4;
        int requiredSize = ySize + chromaSize + chromaSize;

        ByteBuffer source = image.duplicate();
        source.clear();

        if (source.capacity() < requiredSize) {
            return;
        }

        byte[] u = new byte[chromaSize];
        byte[] v = new byte[chromaSize];

        source.position(ySize);
        source.get(u);

        source.position(ySize + chromaSize);
        source.get(v);

        ByteBuffer destination = image.duplicate();
        destination.clear();
        destination.position(ySize);

        for (int i = 0; i < chromaSize; i++) {
            destination.put(u[i]);
            destination.put(v[i]);
        }
    }
}