package com.whaltermc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_from_mask;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2;

// String target (not class literal) so other mods / different JavaCV versions
// never break at mixin-config time. All handlers are require=0 + WrapOperation
// so they chain with other mods instead of conflicting like Redirect.
@Mixin(targets = "org.bytedeco.javacv.FFmpegFrameRecorder", remap = false, priority = 1000)
public abstract class FlashbackFFmpegFrameRecorderMixin {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("flashback-redroided/nv12");

    private static final long AV_CH_LAYOUT_MONO     = 0x4L;
    private static final long AV_CH_LAYOUT_STEREO   = 0x3L;
    private static final long AV_CH_LAYOUT_SURROUND = 0x7L;
    private static final long AV_CH_LAYOUT_QUAD     = 0x33L;
    private static final long AV_CH_LAYOUT_5POINT0  = 0x607L;
    private static final long AV_CH_LAYOUT_5POINT1  = 0x60FL;
    private static final long AV_CH_LAYOUT_6POINT1  = 0x70FL;
    private static final long AV_CH_LAYOUT_7POINT1  = 0x63FL;

    @Shadow
    private AVCodec video_codec;

    @Shadow
    private AVCodecContext video_c;

    private static long defaultMask(int nbChannels) {
        return switch (nbChannels) {
            case 1  -> AV_CH_LAYOUT_MONO;
            case 2  -> AV_CH_LAYOUT_STEREO;
            case 3  -> AV_CH_LAYOUT_SURROUND;
            case 4  -> AV_CH_LAYOUT_QUAD;
            case 5  -> AV_CH_LAYOUT_5POINT0;
            case 6  -> AV_CH_LAYOUT_5POINT1;
            case 7  -> AV_CH_LAYOUT_6POINT1;
            case 8  -> AV_CH_LAYOUT_7POINT1;
            default -> nbChannels > 0 ? ((1L << nbChannels) - 1) : 0L;
        };
    }

    private static void applyChLayout(AVCodecContext ctx, int channels) {
        if (ctx == null) return;
        AVChannelLayout layout = new AVChannelLayout();
        av_channel_layout_default(layout, channels);
        ctx.ch_layout(layout);
    }

    private static void applyChLayoutMask(AVCodecContext ctx, long mask) {
        if (ctx == null) return;
        AVChannelLayout layout = new AVChannelLayout();
        if (mask != 0) av_channel_layout_from_mask(layout, mask);
        else av_channel_layout_default(layout, 2);
        ctx.ch_layout(layout);
    }

    private static void applyFrameChLayout(AVFrame frame, int channels) {
        if (frame == null) return;
        AVChannelLayout layout = new AVChannelLayout();
        av_channel_layout_default(layout, channels);
        frame.ch_layout(layout);
    }

    private static void applyFrameChLayoutMask(AVFrame frame, long mask) {
        if (frame == null) return;
        AVChannelLayout layout = new AVChannelLayout();
        if (mask != 0) av_channel_layout_from_mask(layout, mask);
        else av_channel_layout_default(layout, 2);
        frame.ch_layout(layout);
    }

    @WrapOperation(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/global/avutil;av_get_default_channel_layout(I)J", remap = false), remap = false, require = 0)
    private long flashbackRedroided$defaultChannelLayout(Operation<Long> original, int nbChannels) {
        return defaultMask(nbChannels);
    }

    @WrapOperation(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channels(I)Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;", remap = false), remap = false, require = 0)
    private AVCodecContext flashbackRedroided$ctxChannelsSet(Operation<AVCodecContext> original, AVCodecContext ctx, int channels) {
        applyChLayout(ctx, channels);
        return ctx;
    }

    @WrapOperation(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channel_layout(J)Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;", remap = false), remap = false, require = 0)
    private AVCodecContext flashbackRedroided$ctxChannelLayoutSet(Operation<AVCodecContext> original, AVCodecContext ctx, long mask) {
        applyChLayoutMask(ctx, mask);
        return ctx;
    }

    @WrapOperation(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channels()I", remap = false), remap = false, require = 0)
    private int flashbackRedroided$ctxChannelsGet(Operation<Integer> original, AVCodecContext ctx) {
        if (ctx == null || ctx.ch_layout() == null) return 0;
        return ctx.ch_layout().nb_channels();
    }

    @WrapOperation(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channel_layout()J", remap = false), remap = false, require = 0)
    private long flashbackRedroided$ctxChannelLayoutGet(Operation<Long> original, AVCodecContext ctx) {
        int nb = 0;
        if (ctx != null && ctx.ch_layout() != null) nb = ctx.ch_layout().nb_channels();
        return defaultMask(nb);
    }

    @WrapOperation(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channels(I)Lorg/bytedeco/ffmpeg/avutil/AVFrame;", remap = false), remap = false, require = 0)
    private AVFrame flashbackRedroided$frameChannelsSet(Operation<AVFrame> original, AVFrame frame, int channels) {
        applyFrameChLayout(frame, channels);
        return frame;
    }

    @WrapOperation(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channel_layout(J)Lorg/bytedeco/ffmpeg/avutil/AVFrame;", remap = false), remap = false, require = 0)
    private AVFrame flashbackRedroided$frameChannelLayoutSet(Operation<AVFrame> original, AVFrame frame, long mask) {
        applyFrameChLayoutMask(frame, mask);
        return frame;
    }

    @WrapOperation(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channels()I", remap = false), remap = false, require = 0)
    private int flashbackRedroided$frameChannelsGet(Operation<Integer> original, AVFrame frame) {
        if (frame == null || frame.ch_layout() == null) return 0;
        return frame.ch_layout().nb_channels();
    }

    @WrapOperation(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channel_layout()J", remap = false), remap = false, require = 0)
    private long flashbackRedroided$frameChannelLayoutGet(Operation<Long> original, AVFrame frame) {
        int nb = 0;
        if (frame != null && frame.ch_layout() != null) nb = frame.ch_layout().nb_channels();
        return defaultMask(nb);
    }

    @WrapOperation(method = {"startUnsafe", "start", "initAudioResampler", "createAudioResampler"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/global/swresample;swr_alloc_set_opts(Lorg/bytedeco/ffmpeg/swresample/SwrContext;JIIJIIILorg/bytedeco/javacpp/Pointer;)Lorg/bytedeco/ffmpeg/swresample/SwrContext;", remap = false), remap = false, require = 0)
    private SwrContext flashbackRedroided$swrAllocSetOpts(
            Operation<SwrContext> original,
            SwrContext s,
            long outChLayout, int outSampleFmt, int outSampleRate,
            long inChLayout,  int inSampleFmt,  int inSampleRate,
            int logOffset, Pointer logCtx) {

        if (s == null || s.isNull()) s = new SwrContext();

        AVChannelLayout outLayout = new AVChannelLayout();
        AVChannelLayout inLayout  = new AVChannelLayout();

        if (outChLayout != 0) av_channel_layout_from_mask(outLayout, outChLayout);
        else av_channel_layout_default(outLayout, 2);

        if (inChLayout != 0) av_channel_layout_from_mask(inLayout, inChLayout);
        else av_channel_layout_default(inLayout, 2);

        int ret = swr_alloc_set_opts2(s, outLayout, outSampleFmt, outSampleRate, inLayout, inSampleFmt, inSampleRate, logOffset, logCtx);
        if (ret < 0) return null;
        return s;
    }

    @WrapOperation(method = {"record", "recordSamples", "recordUnsafe", "isKeyFrame", "setKeyFrame"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;key_frame()I", remap = false), remap = false, require = 0)
    private int flashbackRedroided$getKeyFrame(Operation<Integer> original, AVFrame frame) {
        if (frame == null) return 0;
        return (frame.flags() & AVFrame.AV_FRAME_FLAG_KEY) != 0 ? 1 : 0;
    }

    @WrapOperation(method = {"record", "recordSamples", "recordUnsafe", "isKeyFrame", "setKeyFrame"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;key_frame(I)Lorg/bytedeco/ffmpeg/avutil/AVFrame;", remap = false), remap = false, require = 0)
    private AVFrame flashbackRedroided$setKeyFrame(Operation<AVFrame> original, AVFrame frame, int key) {
        if (frame == null) return null;
        int flags = frame.flags();
        int keyFlag = AVFrame.AV_FRAME_FLAG_KEY;
        if (key != 0) frame.flags(flags | keyFlag);
        else frame.flags(flags & (keyFlag ^ -1));
        return frame;
    }

    /**
     * Force NV12 + ndk_codec for Android MediaCodec encoders.
     * WrapOperation (not Inject+@Local) so Flashback/JavaCV renames never break us.
     */
    @WrapOperation(
        method = "startUnsafe",
        at = @At(
            value = "INVOKE",
            target = "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_open2(Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;Lorg/bytedeco/ffmpeg/avcodec/AVCodec;Lorg/bytedeco/ffmpeg/avutil/AVDictionary;)I",
            remap = false
        ),
        remap = false,
        require = 0
    )
    private int flashbackRedroided$alwaysNdkMediaCodec(
            Operation<Integer> original,
            AVCodecContext ctx,
            AVCodec codec,
            AVDictionary options
    ) {
        try {
            if (codec != null && codec.name() != null && !codec.isNull()
                    && codec.name().getString().endsWith("_mediacodec")
                    && ctx != null && !ctx.isNull()) {
                if (options != null && !options.isNull()) {
                    av_dict_set(options, "ndk_codec", "1", 0);
                }
                ctx.pix_fmt(AV_PIX_FMT_NV12);
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to force NV12 for MediaCodec, continuing with default", t);
        }
        return original.call(ctx, codec, options);
    }

    /** Reused per-thread scratch for the U plane so we don't allocate per frame. */
    private static final ThreadLocal<byte[]> U_SCRATCH = new ThreadLocal<>();

    private static boolean flashbackRedroided$isMediaCodec(AVCodec codec) {
        try {
            return codec != null
                    && codec.name() != null
                    && !codec.isNull()
                    && codec.name().getString().endsWith("_mediacodec");
        } catch (Throwable t) {
            return false;
        }
    }

    @ModifyVariable(
            method = "recordImage",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 2,
            remap = false,
            require = 0
    )
    private int flashbackRedroided$useNv12(int pixelFormat) {
        // Only rewrite the exact YUV420P -> NV12 case for MediaCodec.
        if (pixelFormat != AV_PIX_FMT_YUV420P) {
            return pixelFormat;
        }
        if (!flashbackRedroided$isMediaCodec(video_codec)) {
            return pixelFormat;
        }
        return AV_PIX_FMT_NV12;
    }

    @Inject(
            method = "recordImage",
            at = @At("HEAD"),
            remap = false,
            require = 0
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
        if (!flashbackRedroided$isMediaCodec(video_codec)) {
            return;
        }
        flashbackRedroided$yuv420pToNv12InPlace(width, height, image);
    }

    /**
     * Convert tightly-packed YUV420P to NV12 in place.
     * Absolute get/put (position preserved), ThreadLocal scratch, never throws.
     */
    private static void flashbackRedroided$yuv420pToNv12InPlace(int width, int height, ByteBuffer image) {
        try {
            if (image == null || image.isReadOnly()) {
                return;
            }
            if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
                return;
            }
            long ySizeLong = (long) width * (long) height;
            if (ySizeLong <= 0 || ySizeLong > Integer.MAX_VALUE / 2) {
                return;
            }
            int ySize = (int) ySizeLong;
            int chromaSize = ySize / 4;
            if (chromaSize <= 0) {
                return;
            }
            long requiredLong = (long) ySize + (long) chromaSize * 2L;
            if (requiredLong > Integer.MAX_VALUE) {
                return;
            }
            if (image.capacity() < (int) requiredLong) {
                return;
            }

            byte[] u = U_SCRATCH.get();
            if (u == null || u.length < chromaSize) {
                u = new byte[chromaSize];
                U_SCRATCH.set(u);
            }

            int uBase = ySize;
            int vBase = ySize + chromaSize;
            for (int i = 0; i < chromaSize; i++) {
                u[i] = image.get(uBase + i);
            }
            for (int i = 0; i < chromaSize; i++) {
                byte v = image.get(vBase + i);
                image.put(uBase + (i << 1), u[i]);
                image.put(uBase + (i << 1) + 1, v);
            }
        } catch (Throwable t) {
            LOGGER.debug("NV12 in-place conversion skipped", t);
        }
    }
}