package com.whaltermc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;

@Mixin(targets = "com.moulberry.flashback.combo_options.VideoCodec", remap = false, priority = 1000)
public abstract class VideoCodecMixin {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("flashback-redroided/nv12");

    @WrapOperation(
            method = "doesEncoderWork",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_open2(" +
                             "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;" +
                             "Lorg/bytedeco/ffmpeg/avcodec/AVCodec;" +
                             "Lorg/bytedeco/ffmpeg/avutil/AVDictionary;)I",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private static int flashbackRedroided$forceNdkOnProbe(
            Operation<Integer> original,
            AVCodecContext codecContext,
            AVCodec codec,
            AVDictionary options
    ) {
        try {
            if (codec != null && codec.name() != null && !codec.isNull()
                    && codec.name().getString().endsWith("_mediacodec")) {
                if (options != null && !options.isNull()) {
                    av_dict_set(options, "ndk_codec", "1", 0);
                }
                if (codecContext != null && !codecContext.isNull()) {
                    codecContext.pix_fmt(AV_PIX_FMT_NV12);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("NV12 probe forcing skipped", t);
        }
        return original.call(codecContext, codec, options);
    }
}
