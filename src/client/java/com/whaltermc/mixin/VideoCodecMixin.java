package com.whaltermc.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;

@Mixin(targets = "com.moulberry.flashback.combo_options.VideoCodec", remap = false, priority = 1000)
public abstract class VideoCodecMixin {

    @Inject(
            method = "doesEncoderWork",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_open2(" +
                             "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;" +
                             "Lorg/bytedeco/ffmpeg/avcodec/AVCodec;" +
                             "Lorg/bytedeco/ffmpeg/avutil/AVDictionary;)I",
                    remap = false
            ),
            remap = false
    )
    private static void flashbackRedroided$forceNdkOnProbe(
            AVCodec codec,
            CallbackInfoReturnable<Boolean> cir,
            @Local AVCodecContext codecContext,
            @Local AVDictionary options
    ) {
        if (codec == null || codec.name() == null) return;
        String name = codec.name().getString();
        if (!name.endsWith("_mediacodec")) return;

        if (options != null) {
            av_dict_set(options, "ndk_codec", "1", 0);
        }
        if (codecContext != null) {
            codecContext.pix_fmt(AV_PIX_FMT_NV12);
        }
    }
}
