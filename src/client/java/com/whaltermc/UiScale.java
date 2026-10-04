package com.whaltermc;

import imgui.moulberry92.ImGui;
import imgui.moulberry92.ImGuiIO;
import imgui.moulberry92.ImGuiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automatically scales Flashback's ImGui editor so it stays usable on
 * phones, tablets and other screen sizes.
 *
 * Runs once per ImGui frame (see ImGuiNewFrameMixin), i.e. only while the
 * Flashback editor is actually open.
 *
 * Configure with the JVM property {@code flashback_redroided.uiscale}:
 *   - "auto" (default): scale from the window's shorter side
 *   - "off":            never touch the UI scale
 *   - a number, e.g. "1.5": use that fixed scale
 *
 * Note: this only scales fonts (ImGui sizes widgets from font height).
 * Padding/spacing are left alone so Flashback's own layout is unchanged.
 */
public final class UiScale {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("flashback-redroided/ui");

    private static final String PROPERTY = "flashback_redroided.uiscale";

    /** Window height (px) at which the UI is shown at its normal size. */
    private static final float REFERENCE_SHORT_SIDE = 720f;
    private static final float AUTO_MIN = 1.0f;
    private static final float AUTO_MAX = 2.0f;
    private static final float MANUAL_MIN = 0.5f;
    private static final float MANUAL_MAX = 4.0f;

    private static final String MODE = System
            .getProperty(PROPERTY, "auto")
            .trim()
            .toLowerCase(java.util.Locale.ROOT);

    private static float applied = -1f;
    private static boolean failed;

    private UiScale() {}

    public static void apply() {
        if (failed || "off".equals(MODE)) {
            return;
        }

        try {
            ImGuiIO io = ImGui.getIO();

            float width = io.getDisplaySizeX();
            float height = io.getDisplaySizeY();

            if (width <= 0f || height <= 0f) {
                return;
            }

            float target = resolve(width, height);

            if (Math.abs(target - applied) < 0.01f) {
                return;
            }

            ImGuiStyle style = ImGui.getStyle();
            style.setFontScaleMain(target);

            LOGGER.info(
                    "UI scale set to {} (display {}x{}, mode {})",
                    target, (int) width, (int) height, MODE
            );

            applied = target;

        } catch (Throwable t) {
            // Never break the editor over a cosmetic feature.
            failed = true;
            LOGGER.warn("UI auto-scale disabled after error", t);
        }
    }

    private static float resolve(float width, float height) {
        float fixed = parseFixed();

        if (fixed > 0f) {
            return clamp(fixed, MANUAL_MIN, MANUAL_MAX);
        }

        float shortSide = Math.min(width, height);
        return clamp(shortSide / REFERENCE_SHORT_SIDE, AUTO_MIN, AUTO_MAX);
    }

    private static float parseFixed() {
        if ("auto".equals(MODE)) {
            return -1f;
        }

        try {
            return Float.parseFloat(MODE);
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
