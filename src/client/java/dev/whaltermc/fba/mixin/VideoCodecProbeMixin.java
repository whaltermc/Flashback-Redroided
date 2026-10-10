// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.javacpp.IntPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NONE;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;

@Mixin(targets = "com.moulberry.flashback.combo_options.VideoCodec", remap = false, priority = 1000)
public abstract class VideoCodecProbeMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback_android");

    /**
     * Pixel format fallback order for MediaCodec encoder probing.
     * Devices vary: some only accept NV12, others YUV420P, a few prefer NV21.
     */
    private static final int[] MEDIACODEC_PIX_FMT_FALLBACKS = {
            AV_PIX_FMT_NV12,      // preferred on most modern Snapdragon/Dimensity
            AV_PIX_FMT_YUV420P,   // widely supported, good Exynos fallback
            24,                    // AV_PIX_FMT_NV21 — older Qualcomm/MediaTek
    };

    private static boolean isMediaCodecEncoder(AVCodec codec) {
        try {
            return codec != null && !codec.isNull()
                    && codec.name() != null
                    && codec.name().getString().endsWith("_mediacodec");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean codecSupportsFmt(AVCodec codec, int fmt) {
        try {
            IntPointer fmts = codec.pix_fmts();
            if (fmts == null || fmts.isNull()) return true; // no static constraint
            for (long i = 0; ; i++) {
                int f = fmts.get(i);
                if (f == AV_PIX_FMT_NONE) break;
                if (f == fmt) return true;
            }
            return false;
        } catch (Throwable t) {
            return true; // can't read list, let FFmpeg decide
        }
    }

    /**
     * Probe MediaCodec encoders with multiple pixel format fallbacks.
     *
     * A failed avcodec_open2 can leave the context in a partially-initialised
     * state. Rather than reusing that dirty context across retries, we allocate
     * a fresh AVCodecContext for each attempt and copy the minimal fields
     * Flashback sets (codec_id, codec_type, bit_rate, width, height, time_base).
     * This keeps behaviour identical to what Flashback would do on a clean probe.
     *
     * require=0 so the handler is silently skipped if Flashback ever removes
     * avcodec_open2 from doesEncoderWork.
     */
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
    private static int fba$probeWithPixFmtFallbacks(
            Operation<Integer> original,
            AVCodecContext ctx,
            AVCodec codec,
            AVDictionary options
    ) {
        if (!isMediaCodecEncoder(codec)) {
            return original.call(ctx, codec, options);
        }

        // Force NDK path for all MediaCodec encoders.
        try {
            if (options != null && !options.isNull()) {
                av_dict_set(options, "ndk_codec", "1", 0);
            }
        } catch (Throwable t) {
            LOGGER.debug("ndk_codec option unavailable", t);
        }

        String codecName = "<unknown>";
        try { codecName = codec.name().getString(); } catch (Throwable ignored) {}

        int lastResult = -1;

        for (int fmt : MEDIACODEC_PIX_FMT_FALLBACKS) {
            if (ctx == null || ctx.isNull()) break;

            if (!codecSupportsFmt(codec, fmt)) continue;

            // Set pixel format on the context Flashback already prepared.
            // On the first attempt we use it directly; on subsequent attempts
            // we need a fresh context because a failed open may have touched
            // internal state. We free the old context and allocate a new one,
            // then re-apply the fields Flashback set before calling us.
            if (lastResult < 0 && lastResult != -1) {
                // Previous attempt failed — get a clean context.
                AVCodecContext fresh = null;
                try {
                    fresh = avcodec_alloc_context3(codec);
                    if (fresh == null || fresh.isNull()) break;

                    // Copy fields Flashback sets before avcodec_open2.
                    fresh.codec_id(ctx.codec_id());
                    fresh.codec_type(ctx.codec_type());
                    fresh.bit_rate(ctx.bit_rate());
                    fresh.width(ctx.width());
                    fresh.height(ctx.height());
                    fresh.time_base(ctx.time_base());
                    if ((codec.capabilities() & 512) != 0) {
                        fresh.strict_std_compliance(-2);
                    }

                    avcodec_free_context(ctx);
                    ctx = fresh;
                } catch (Throwable t) {
                    LOGGER.debug("Context reset failed for {}", codecName, t);
                    if (fresh != null && !fresh.isNull()) {
                        try { avcodec_free_context(fresh); } catch (Throwable ignored) {}
                    }
                    break;
                }
            }

            try {
                ctx.pix_fmt(fmt);
            } catch (Throwable t) {
                LOGGER.debug("pix_fmt {} unavailable on {}", fmt, codecName, t);
                continue;
            }

            lastResult = original.call(ctx, codec, options);
            if (lastResult >= 0) {
                return lastResult;
            }
        }

        return lastResult;
    }

    /**
     * avcodec_close() was removed in FFmpeg 5.0.
     * avcodec_free_context() handles all cleanup on FFmpeg 5+.
     * Swallowing this call prevents UnsatisfiedLinkError on devices
     * where the symbol no longer exists in the native library.
     * require = 1: a future Flashback update moving this call must fail
     * loudly at startup, never silently at the export screen.
     */
    @WrapOperation(
            method = "doesEncoderWork",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_close(" +
                             "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;)I",
                    remap = false
            ),
            remap = false,
            require = 1
    )
    private static int fba$suppressAvcodecClose(
            Operation<Integer> original,
            AVCodecContext codecContext
    ) {
        return 0;
    }
}
