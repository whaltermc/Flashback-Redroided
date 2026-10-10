// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.dialog;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * In-game file browser standing in for the native dialogs on Android.
 *
 * <p>Hands back a real filesystem path, because every Flashback caller wraps
 * the dialog result in {@code java.io.File} straight away.
 */
public final class FilePickerScreen extends Screen {

    private static final int ROW_H = 24;
    private static final int LIST_TOP = 48;
    private static final int NAV_Y_OFF = 62;
    private static final int NAME_Y_OFF = 92;

    private final AndroidFileDialogs.Mode mode;
    private final CompletableFuture<String> future;
    private final List<String> extensions;

    private File dir;
    private List<Row> rows = List.of();
    private int firstRow;
    private EditBox nameBox;
    private boolean finished;

    private record Row(File file, boolean directory, long size) {
        String label() {
            return file.getName();
        }
    }

    FilePickerScreen(AndroidFileDialogs.Mode mode,
                     File startDir,
                     String startName,
                     List<String> extensions,
                     CompletableFuture<String> future) {
        super(Component.literal(switch (mode) {
            case SAVE_FILE -> "Save file";
            case OPEN_FILE -> "Open file";
            case OPEN_FOLDER -> "Select folder";
        }));
        this.mode = mode;
        this.extensions = extensions == null ? List.of() : extensions;
        this.future = future;
        this.dir = readableOrParent(startDir);
        this.suggestedName = (startName == null || startName.isBlank()) ? "export" : startName;
    }

    private String suggestedName;

    private static File readableOrParent(File start) {
        File p = start;
        for (int i = 0; i < 64 && p != null; i++) {
            if (p.isDirectory() && p.canRead()) {
                return p;
            }
            p = p.getParentFile();
        }
        return new File(".");
    }

    private void rescan() {
        List<Row> out = new ArrayList<>();
        File parent = dir.getParentFile();
        if (parent != null) {
            out.add(new Row(parent, true, 0L));
        }

        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                boolean isDir = f.isDirectory();
                if (!isDir && mode != AndroidFileDialogs.Mode.OPEN_FOLDER
                        && !AndroidFileDialogs.matchesExtension(f.getName(), extensions)) {
                    continue;
                }
                if (f.getName().startsWith(".")) {
                    continue;
                }
                out.add(new Row(f, isDir, isDir ? 0L : f.length()));
            }
            out.sort(Comparator
                    .comparing((Row r) -> !r.directory())
                    .thenComparing(r -> r.file().getName().toLowerCase(Locale.ROOT)));
        }

        rows = out;
        firstRow = 0;
        rebuildWidgets();
    }

    private int navY() {
        return height - NAV_Y_OFF;
    }

    private int listBottom() {
        int reserved = mode == AndroidFileDialogs.Mode.SAVE_FILE ? NAV_Y_OFF + 34 : NAV_Y_OFF;
        return height - reserved;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - LIST_TOP) / ROW_H);
    }

    private void buildRows() {
        int perPage = visibleRows();
        int last = Math.min(rows.size(), firstRow + perPage);
        int bw = Math.max(120, width - 40);
        int x = 20;

        for (int i = firstRow; i < last; i++) {
            Row row = rows.get(i);
            final int index = i;
            String text = row.directory() ? "< " + row.label() + " >" : row.label();
            if (!row.directory() && row.size() > 0) {
                text = text + "  (" + (row.size() / 1024) + " KB)";
            }
            addRenderableWidget(Button.builder(Component.literal(text), b -> onRowPressed(index))
                    .bounds(x, LIST_TOP + (i - firstRow) * ROW_H, bw, ROW_H - 2)
                    .build());
        }

        if (rows.isEmpty()) {
            // Nothing listable here; the Up button below still works.
            assert true;
        }

        File parent = dir.getParentFile();
        Button up = Button.builder(Component.literal("Up"), b -> {
            File p = dir.getParentFile();
            if (p != null) {
                dir = p;
                rescan();
            }
        }).bounds(20, navY(), 80, 20).build();
        up.active = parent != null;
        addRenderableWidget(up);

        Button prev = Button.builder(Component.literal("< Prev"), b -> {
            firstRow = Math.max(0, firstRow - perPage);
            rebuildWidgets();
        }).bounds(108, navY(), 80, 20).build();
        prev.active = firstRow > 0;
        addRenderableWidget(prev);

        Button next = Button.builder(Component.literal("Next >"), b -> {
            firstRow = Math.min(Math.max(0, rows.size() - perPage), firstRow + perPage);
            rebuildWidgets();
        }).bounds(196, navY(), 80, 20).build();
        next.active = last < rows.size();
        addRenderableWidget(next);

        if (mode == AndroidFileDialogs.Mode.OPEN_FOLDER) {
            addRenderableWidget(Button.builder(Component.literal("Use this folder"),
                            b -> finish(dir.getAbsolutePath()))
                    .bounds(width - 200, navY(), 180, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> finish(null))
                    .bounds(width - 200, navY(), 90, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
                        String name = nameBox != null ? nameBox.getValue() : suggestedName;
                        if (name == null || name.isBlank()) {
                            return;
                        }
                        finish(new File(dir, sanitize(name)).getAbsolutePath());
                    })
                    .bounds(width - 100, navY(), 90, 20).build());
        }

        if (mode == AndroidFileDialogs.Mode.SAVE_FILE) {
            nameBox = new EditBox(this.font, 20, height - NAME_Y_OFF,
                    Math.max(120, width - 40), 20, Component.literal("File name"));
            nameBox.setValue(suggestedName);
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);
        }
    }

    private void onRowPressed(int index) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        Row row = rows.get(index);
        if (row.directory()) {
            dir = row.file();
            rescan();
        } else if (mode == AndroidFileDialogs.Mode.OPEN_FILE) {
            finish(row.file().getAbsolutePath());
        } else {
            suggestedName = row.label();
            if (nameBox != null) {
                nameBox.setValue(row.label());
            }
        }
    }

    private static String sanitize(String name) {
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[<>:\"|?*\\x00-\\x1f]", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isEmpty() ? "export" : cleaned;
    }

    private void finish(String path) {
        if (finished) {
            return;
        }
        finished = true;
        try {
            future.complete(path);
        } finally {
            AndroidFileDialogs.clearActive();
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
        }
    }

    @Override
    protected void init() {
        buildRows();
    }

    @Override
    public void onClose() {
        finish(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Not renderBackground(): that blurs whenever a level is loaded, and
        // GuiRenderState.applyBlur throws "Can only blur once per frame" when
        // something else already blurred this frame (other mods, or a second
        // render pass). renderTransparentBackground is a plain dim gradient and
        // cannot trip that guard.
        renderTransparentBackground(graphics);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font, shorten(dir.getAbsolutePath()),
                this.width / 2, 28, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, delta);
    }

    private static String shorten(String path) {
        if (path == null || path.length() <= 72) {
            return path == null ? "" : path;
        }
        return "..." + path.substring(path.length() - 69);
    }
}