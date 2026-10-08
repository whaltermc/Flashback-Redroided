package com.whaltermc.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_from_mask;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2;

@Mixin(value = FFmpegFrameRecorder.class, remap = false, priority = 1000)
public abstract class FlashbackFFmpegFrameRecorderMixin {

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

    private void forceNdkMediaCodec(AVDictionary options) {
        if (video_codec == null || video_codec.name() == null) return;
        String name = video_codec.name().getString();
        if (!name.endsWith("_mediacodec")) return;

        if (options != null) {
            av_dict_set(options, "ndk_codec", "1", 0);
        }
        if (video_c != null) {
            video_c.pix_fmt(AV_PIX_FMT_NV12);
        }
    }

    @Redirect(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/global/avutil;av_get_default_channel_layout(I)J", remap = false), remap = false, require = 0)
    private long flashbackRedroided$defaultChannelLayout(int nbChannels) {
        return defaultMask(nbChannels);
    }

    @Redirect(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channels(I)Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;", remap = false), remap = false, require = 0)
    private AVCodecContext flashbackRedroided$ctxChannelsSet(AVCodecContext ctx, int channels) {
        applyChLayout(ctx, channels);
        return ctx;
    }

    @Redirect(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channel_layout(J)Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;", remap = false), remap = false, require = 0)
    private AVCodecContext flashbackRedroided$ctxChannelLayoutSet(AVCodecContext ctx, long mask) {
        applyChLayoutMask(ctx, mask);
        return ctx;
    }

    @Redirect(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channels()I", remap = false), remap = false, require = 0)
    private int flashbackRedroided$ctxChannelsGet(AVCodecContext ctx) {
        if (ctx == null || ctx.ch_layout() == null) return 0;
        return ctx.ch_layout().nb_channels();
    }

    @Redirect(method = {"startUnsafe", "start", "setAudioChannels", "setAudioCodec", "getAudioChannels", "getAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;channel_layout()J", remap = false), remap = false, require = 0)
    private long flashbackRedroided$ctxChannelLayoutGet(AVCodecContext ctx) {
        int nb = 0;
        if (ctx != null && ctx.ch_layout() != null) nb = ctx.ch_layout().nb_channels();
        return defaultMask(nb);
    }

    @Redirect(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channels(I)Lorg/bytedeco/ffmpeg/avutil/AVFrame;", remap = false), remap = false, require = 0)
    private AVFrame flashbackRedroided$frameChannelsSet(AVFrame frame, int channels) {
        applyFrameChLayout(frame, channels);
        return frame;
    }

    @Redirect(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channel_layout(J)Lorg/bytedeco/ffmpeg/avutil/AVFrame;", remap = false), remap = false, require = 0)
    private AVFrame flashbackRedroided$frameChannelLayoutSet(AVFrame frame, long mask) {
        applyFrameChLayoutMask(frame, mask);
        return frame;
    }

    @Redirect(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channels()I", remap = false), remap = false, require = 0)
    private int flashbackRedroided$frameChannelsGet(AVFrame frame) {
        if (frame == null || frame.ch_layout() == null) return 0;
        return frame.ch_layout().nb_channels();
    }

    @Redirect(method = {"record", "recordSamples", "recordUnsafe", "getFrameAudioChannels", "getFrameAudioChannelLayout"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;channel_layout()J", remap = false), remap = false, require = 0)
    private long flashbackRedroided$frameChannelLayoutGet(AVFrame frame) {
        int nb = 0;
        if (frame != null && frame.ch_layout() != null) nb = frame.ch_layout().nb_channels();
        return defaultMask(nb);
    }

    @Redirect(method = {"startUnsafe", "start", "initAudioResampler", "createAudioResampler"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/global/swresample;swr_alloc_set_opts(Lorg/bytedeco/ffmpeg/swresample/SwrContext;JIIJIIILorg/bytedeco/javacpp/Pointer;)Lorg/bytedeco/ffmpeg/swresample/SwrContext;", remap = false), remap = false, require = 0)
    private SwrContext flashbackRedroided$swrAllocSetOpts(
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

    @Redirect(method = {"record", "recordSamples", "recordUnsafe", "isKeyFrame", "setKeyFrame"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;key_frame()I", remap = false), remap = false, require = 0)
    private int flashbackRedroided$getKeyFrame(AVFrame frame) {
        if (frame == null) return 0;
        return (frame.flags() & AVFrame.AV_FRAME_FLAG_KEY) != 0 ? 1 : 0;
    }

    @Redirect(method = {"record", "recordSamples", "recordUnsafe", "isKeyFrame", "setKeyFrame"}, at = @At(value = "INVOKE", target = "Lorg/bytedeco/ffmpeg/avutil/AVFrame;key_frame(I)Lorg/bytedeco/ffmpeg/avutil/AVFrame;", remap = false), remap = false, require = 0)
    private AVFrame flashbackRedroided$setKeyFrame(AVFrame frame, int key) {
        if (frame == null) return null;
        int flags = frame.flags();
        int keyFlag = AVFrame.AV_FRAME_FLAG_KEY;
        if (key != 0) frame.flags(flags | keyFlag);
        else frame.flags(flags & (keyFlag ^ -1));
        return frame;
    }

    @Inject(
        method = "startUnsafe",
        at = @At(
            value = "INVOKE",
            target = "Lorg/bytedeco/ffmpeg/global/avcodec;avcodec_open2(Lorg/bytedeco/ffmpeg/avcodec/AVCodecContext;Lorg/bytedeco/ffmpeg/avcodec/AVCodec;Lorg/bytedeco/ffmpeg/avutil/AVDictionary;)I",
            remap = false
        ),
        remap = false
    )
    private void flashbackRedroided$alwaysNdkMediaCodec(CallbackInfo ci, @Local AVDictionary options) {
        forceNdkMediaCodec(options);
    }
}