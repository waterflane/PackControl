package org.wodichka.packcontrol.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.wodichka.packcontrol.config.PackControlConfig;
import org.wodichka.packcontrol.packwiz.PackControlPresetService;
import org.wodichka.packcontrol.packwiz.PackFileSelectionService;
import org.wodichka.packcontrol.packwiz.PackFileTreeService;
import org.wodichka.packcontrol.packwiz.PackwizGenerator;
import org.wodichka.packcontrol.snapshot.PackSnapshotService;
import org.wodichka.packcontrol.snapshot.SnapshotDownloadService;
import org.wodichka.packcontrol.snapshot.SnapshotInstallPlan;
import org.wodichka.packcontrol.snapshot.SnapshotProgress;
import org.wodichka.packcontrol.snapshot.SnapshotSaveOptions;
import org.wodichka.packcontrol.updateformat.CancellationToken;
import org.wodichka.packcontrol.updateformat.GitHubReleaseDiscoveryService;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Environment(EnvType.CLIENT)
public final class PackControlScreen extends Screen {
    private static final int BACKGROUND = 0xF0101320;
    private static final int PANEL = 0xCC182033;
    private static final int PANEL_DARK = 0xCC121827;
    private static final int BUTTON = 0xFF202A40;
    private static final int BUTTON_HOVER = 0xFF2A3655;
    private static final int BUTTON_BORDER = 0xFF3B496B;
    private static final int BUTTON_ACCENT = 0xFF6F64C9;
    private static final int BORDER = 0xFF34415F;
    private static final int BORDER_BRIGHT = 0xFF6F64C9;
    private static final int TEXT = 0xFFE7E9F3;
    private static final int MUTED = 0xFF9EA7BC;
    private static final int WARNING = 0xFFFFD863;
    private static final int GOOD = 0xFF72E0A2;
    private static final int ERROR = 0xFFFF7D7D;
    private static final int TREE_ROWS_PER_PAGE = 8;
    private static final int TREE_ROW_HEIGHT = 18;
    private static GitHubReleaseDiscoveryService releaseService;
    private static String releaseServiceApiBase;

    private final Screen parent;
    private final PackControlUiState state;
    private final View view;
    private final int filePage;
    private final DialogMode dialogMode;
    private final List<Renderable> widgets = new ArrayList<>();
    private int baseWidgetCount;
    private PackFileSelectionService.PackFileScanResult scanResult;
    private PackFileTreeService.PackFileTreeView treeView;
    private EditBox customPatternBox;
    private EditBox snapshotNameBox;
    private EditBox snapshotVersionBox;
    private EditBox snapshotAuthorBox;
    private EditBox snapshotCommitBox;
    private PackSnapshotService.LoadedSnapshot selectedSnapshot;
    private List<PackSnapshotService.SnapshotSummary> snapshotChoices = List.of();
    private int selectedSnapshotIndex;
    private SnapshotInstallPlan pendingInstallPlan;
    private Component notice;
    private volatile SnapshotProgress taskProgress = SnapshotProgress.step("Idle", 0, 1, "");
    private volatile boolean taskRunning;
    private volatile boolean refreshRequested;
    private volatile Component pendingNotice;
    private CompletableFuture<?> runningTask;

    public PackControlScreen(Screen parent) {
        this(parent, View.DASHBOARD, 0, Component.translatable("packcontrol.notice.ready"), DialogMode.NONE);
    }

    private PackControlScreen(Screen parent, View view, int filePage) {
        this(parent, view, filePage, Component.translatable("packcontrol.notice.ready"), DialogMode.NONE);
    }

    private PackControlScreen(Screen parent, View view, int filePage, Component notice) {
        this(parent, view, filePage, notice, DialogMode.NONE);
    }

    private PackControlScreen(Screen parent, View view, int filePage, Component notice, DialogMode dialogMode) {
        super(Component.translatable("packcontrol.screen.title"));
        this.parent = parent;
        this.view = view;
        this.filePage = Math.max(0, filePage);
        this.notice = notice;
        this.dialogMode = dialogMode;
        this.state = PackControlUiState.placeholder();
    }

    @Override
    protected void init() {
        widgets.clear();
        scanResult = PackFileSelectionService.scan();
        treeView = PackFileTreeService.view(filePage, TREE_ROWS_PER_PAGE);
        refreshSnapshotChoices();

        int left = contentLeft();
        int top = contentTop();
        int panelTop = panelTop(top);
        int contentWidth = contentWidth();
        int leftWidth = leftPanelWidth();
        int buttonWidth = leftWidth - 48;
        int dashboardX = left + 24;
        int dashboardY = panelTop + 52;

        int tabWidth = 104;
        int tabY = top + 39;
        int tabX = left + contentWidth / 2 - tabWidth - 4;
        addCustomButton(Component.translatable("packcontrol.tab.dashboard"), tabX, tabY, tabWidth, 18,
                () -> open(View.DASHBOARD, 0), view == View.DASHBOARD);
        addCustomButton(Component.translatable("packcontrol.tab.pack_files"), tabX + tabWidth + 8, tabY, tabWidth, 18,
                () -> open(View.PACK_FILES, 0), view == View.PACK_FILES);

        if (view == View.DASHBOARD) {
            initDashboardButtons(dashboardX, dashboardY, buttonWidth);
            addCustomButton(Component.translatable("packcontrol.action.back"), dashboardX, top + contentHeight() - 33, buttonWidth, 20,
                    () -> minecraft.setScreen(parent));
        } else {
            initPackFileButtons(left, panelTop, contentWidth);
            addCustomButton(Component.translatable("packcontrol.action.back"), left + contentWidth - 122, top + contentHeight() - 33, 96, 20,
                    () -> minecraft.setScreen(parent));
        }

        baseWidgetCount = widgets.size();
        if (dialogMode != DialogMode.NONE) {
            for (Renderable renderable : widgets) {
                if (renderable instanceof AbstractWidget widget) {
                    widget.active = false;
                }
            }
        }
        if (dialogMode == DialogMode.SAVE_SNAPSHOT) {
            initSnapshotDialog();
        } else if (dialogMode == DialogMode.LOAD_SNAPSHOT) {
            initLoadSnapshotDialog();
        } else if (dialogMode == DialogMode.RESTART_REQUIRED) {
            initRestartDialog();
        }
    }

    private void initDashboardButtons(int x, int y, int buttonWidth) {
        addCustomButton(Component.translatable("packcontrol.snapshot.save"), x, y, buttonWidth, 20, this::saveSnapshot);
        addCustomButton(Component.translatable("packcontrol.snapshot.load"), x, y + 25, buttonWidth, 20, this::loadSnapshot);
        addCustomButton(Component.translatable("packcontrol.snapshot.download"), x, y + 50, buttonWidth, 20, this::downloadPack);
        addCustomButton(Component.translatable("packcontrol.snapshot.edit_urls"), x, y + 75, buttonWidth, 20, this::editModUrls);
        addCustomButton(Component.translatable("packcontrol.action.generate_packwiz"), x, y + 108, buttonWidth, 20, this::generatePackwiz);
        addActionButton("packcontrol.action.repository", x, y + 133, buttonWidth);
        addCustomButton(Component.translatable("packcontrol.action.releases"), x, y + 158, buttonWidth, 20, this::checkReleases);
    }

    private void initPackFileButtons(int panelX, int panelTop, int panelWidth) {
        int innerX = panelX + 28;
        int innerWidth = panelWidth - 56;
        int rowY = treeStartY(panelTop);
        for (PackFileTreeService.TreeRow row : treeView.rows()) {
            int indent = Math.min(168, row.depth() * 14);
            if (row.directory()) {
                addCustomButton(Component.literal(row.expanded() ? "-" : "+"), innerX + indent, rowY - 2, 16, 15,
                        () -> toggleExpanded(row.relativePath()));
            }
            addCustomButton(Component.literal(row.partial() ? "~" : row.selected() ? "-" : "+"), innerX + indent + 20, rowY - 2, 22, 15,
                    () -> toggleTreeSelection(row), row.selected());
            rowY += TREE_ROW_HEIGHT;
        }

        int actionY = panelTop + panelHeight() - 116;
        int fieldWidth = Math.min(420, innerWidth / 2);
        customPatternBox = new EditBox(font, innerX, actionY, fieldWidth, 18, Component.translatable("packcontrol.snapshot.mod_url_mapping"));
        customPatternBox.setMaxLength(260);
        widgets.add(addRenderableWidget(customPatternBox));
        addCustomButton(Component.translatable("packcontrol.snapshot.edit_urls"), innerX + fieldWidth + 8, actionY, 128, 18, this::editModUrls);

        int actionRowY = actionY + 26;
        int actionWidth = Math.max(116, (innerWidth - 24) / 5);
        addCustomButton(Component.translatable("packcontrol.snapshot.save"), innerX, actionRowY, actionWidth, 18, this::saveSnapshot);
        addCustomButton(Component.translatable("packcontrol.snapshot.load"), innerX + actionWidth + 6, actionRowY, actionWidth, 18, this::loadSnapshot);
        addCustomButton(Component.translatable("packcontrol.snapshot.download"), innerX + (actionWidth + 6) * 2, actionRowY, actionWidth, 18, this::downloadPack);
        addCustomButton(Component.translatable("packcontrol.action.generate_packwiz"), innerX + (actionWidth + 6) * 3, actionRowY, actionWidth, 18, this::generatePackwiz);
        addCustomButton(Component.translatable("packcontrol.action.push_github"), innerX + (actionWidth + 6) * 4, actionRowY, actionWidth, 18,
                () -> notice = Component.translatable("packcontrol.notice.coming_soon", Component.translatable("packcontrol.action.push_github")).withStyle(ChatFormatting.YELLOW));
    }

    private void initSnapshotDialog() {
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        int dialogWidth = Math.min(420, contentWidth() - 80);
        int dialogX = (width - dialogWidth) / 2;
        int dialogY = contentTop() + 96;
        int fieldX = dialogX + 22;
        int fieldWidth = dialogWidth - 44;

        snapshotNameBox = new EditBox(font, fieldX, dialogY + 48, fieldWidth, 18, Component.translatable("packcontrol.snapshot.name"));
        snapshotNameBox.setMaxLength(80);
        snapshotNameBox.setValue(defaultSnapshotName());
        widgets.add(addRenderableWidget(snapshotNameBox));

        snapshotVersionBox = new EditBox(font, fieldX, dialogY + 86, fieldWidth, 18, Component.translatable("packcontrol.snapshot.version"));
        snapshotVersionBox.setMaxLength(48);
        snapshotVersionBox.setValue(pack.packVersion);
        widgets.add(addRenderableWidget(snapshotVersionBox));

        snapshotAuthorBox = new EditBox(font, fieldX, dialogY + 124, fieldWidth, 18, Component.translatable("packcontrol.snapshot.author"));
        snapshotAuthorBox.setMaxLength(80);
        snapshotAuthorBox.setValue(pack.packAuthor);
        widgets.add(addRenderableWidget(snapshotAuthorBox));

        snapshotCommitBox = new EditBox(font, fieldX, dialogY + 162, fieldWidth, 18, Component.translatable("packcontrol.snapshot.commit"));
        snapshotCommitBox.setMaxLength(240);
        widgets.add(addRenderableWidget(snapshotCommitBox));

        addCustomButton(Component.translatable("packcontrol.snapshot.confirm_save"), fieldX, dialogY + 198, (fieldWidth - 8) / 2, 20, this::confirmSnapshotSave);
        addCustomButton(Component.translatable("packcontrol.snapshot.cancel"), fieldX + (fieldWidth + 8) / 2, dialogY + 198, (fieldWidth - 8) / 2, 20,
                () -> minecraft.setScreen(new PackControlScreen(parent, view, filePage, notice)));
    }

    private void initLoadSnapshotDialog() {
        int dialogWidth = Math.min(380, contentWidth() - 80);
        int dialogHeight = 156;
        int dialogX = (width - dialogWidth) / 2;
        int dialogY = contentTop() + 128;
        int innerX = dialogX + 22;
        int innerWidth = dialogWidth - 44;
        addCustomButton(Component.translatable("packcontrol.snapshot.load_local"), innerX, dialogY + 62, innerWidth, 20, this::loadLocalSnapshot);
        addCustomButton(Component.translatable("packcontrol.snapshot.load_github"), innerX, dialogY + 88, innerWidth, 20, this::loadGithubSnapshot);
        addCustomButton(Component.translatable("packcontrol.snapshot.cancel"), innerX, dialogY + 118, innerWidth, 20,
                () -> minecraft.setScreen(new PackControlScreen(parent, view, filePage, notice)));
    }

    private void initRestartDialog() {
        int dialogWidth = Math.min(380, contentWidth() - 80);
        int dialogX = (width - dialogWidth) / 2;
        int dialogY = contentTop() + 132;
        int innerX = dialogX + 22;
        int innerWidth = dialogWidth - 44;
        addCustomButton(Component.translatable("packcontrol.restart.now"), innerX, dialogY + 78, innerWidth, 20, () -> minecraft.stop());
        addCustomButton(Component.translatable("packcontrol.restart.later"), innerX, dialogY + 106, innerWidth, 20,
                () -> minecraft.setScreen(new PackControlScreen(parent, view, filePage, notice)));
    }
    private void addActionButton(String key, int x, int y, int width) {
        addCustomButton(Component.translatable(key), x, y, width, 20, () ->
                notice = Component.translatable("packcontrol.notice.coming_soon", Component.translatable(key)).withStyle(ChatFormatting.YELLOW));
    }

    private void addCustomButton(Component label, int x, int y, int width, int height, Runnable onPress) {
        addCustomButton(label, x, y, width, height, onPress, false);
    }

    private void addCustomButton(Component label, int x, int y, int width, int height, Runnable onPress, boolean selected) {
        PackControlButton button = new PackControlButton(x, y, width, height, label, onPress, selected);
        button.active = !taskRunning;
        widgets.add(addRenderableWidget(button));
    }

    private void refreshSnapshotChoices() {
        snapshotChoices = new ArrayList<>(PackSnapshotService.snapshots());
        selectedSnapshotIndex = 0;
        String selectedPath = PackControlConfig.pack().selectedSnapshotPath;
        boolean found = false;
        for (int i = 0; i < snapshotChoices.size(); i++) {
            if (!selectedPath.isBlank() && snapshotChoices.get(i).snapshotDirectory().toString().equals(selectedPath)) {
                selectedSnapshotIndex = i;
                found = true;
                break;
            }
        }
        selectedSnapshot = PackSnapshotService.selectedSnapshot();
        if (!found && selectedSnapshot.success() && !selectedPath.isBlank()) {
            snapshotChoices.add(0, new PackSnapshotService.SnapshotSummary(
                    selectedSnapshot.manifest().name,
                    selectedSnapshot.manifest().version,
                    selectedSnapshot.manifest().createdAt,
                    selectedSnapshot.snapshotDirectory(),
                    selectedSnapshot.manifest().mods == null ? 0 : selectedSnapshot.manifest().mods.size(),
                    PackSnapshotService.unresolvedModNames(selectedSnapshot.manifest()).size()
            ));
            selectedSnapshotIndex = 0;
        }
        if (!snapshotChoices.isEmpty()) {
            selectedSnapshotIndex = Math.max(0, Math.min(selectedSnapshotIndex, snapshotChoices.size() - 1));
        }
    }

private void toggleExpanded(String relativePath) {
        PackFileTreeService.toggleExpanded(relativePath);
        open(View.PACK_FILES, filePage);
    }

    private void toggleTreeSelection(PackFileTreeService.TreeRow row) {
        PackFileTreeService.setSelected(row.relativePath(), row.directory(), !row.selected());
        open(View.PACK_FILES, filePage);
    }

    private void editModUrls() {
        if (customPatternBox == null || customPatternBox.getValue().isBlank()) {
            notice = Component.translatable("packcontrol.snapshot.empty_url_mapping").withStyle(ChatFormatting.YELLOW);
            return;
        }
        PackControlConfig.addManualModUrl(customPatternBox.getValue());
        notice = Component.translatable("packcontrol.snapshot.url_saved").withStyle(ChatFormatting.GREEN);
    }

    private void saveSnapshot() {
        minecraft.setScreen(new PackControlScreen(parent, view, filePage, notice, DialogMode.SAVE_SNAPSHOT));
    }

    private void confirmSnapshotSave() {
        SnapshotSaveOptions options = new SnapshotSaveOptions(
                snapshotNameBox == null ? "" : snapshotNameBox.getValue(),
                snapshotVersionBox == null ? "" : snapshotVersionBox.getValue(),
                snapshotCommitBox == null ? "" : snapshotCommitBox.getValue(),
                snapshotAuthorBox == null ? "" : snapshotAuthorBox.getValue()
        );
        PackControlScreen next = new PackControlScreen(parent, view, filePage, Component.translatable("packcontrol.snapshot.saving"));
        minecraft.setScreen(next);
        next.startSaveSnapshot(options);
    }

    private void startSaveSnapshot(SnapshotSaveOptions options) {
        if (taskRunning) {
            return;
        }
        taskRunning = true;
        taskProgress = SnapshotProgress.step("Queued", 0, 1, "Save Snapshot");
        runningTask = CompletableFuture.runAsync(() -> {
            PackSnapshotService.SnapshotSaveResult result = PackSnapshotService.saveSnapshot(options, this::setTaskProgress);
            pendingInstallPlan = null;
            pendingNotice = Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
            taskRunning = false;
            refreshRequested = true;
        });
    }

    private void loadSnapshot() {
        minecraft.setScreen(new PackControlScreen(parent, view, filePage, notice, DialogMode.LOAD_SNAPSHOT));
    }

    private void loadLocalSnapshot() {
        String start = PackControlConfig.pack().selectedSnapshotPath.isBlank()
                ? String.valueOf(PackControlConfig.gameDirectory())
                : PackControlConfig.pack().selectedSnapshotPath;
        String selected = TinyFileDialogs.tinyfd_selectFolderDialog(Component.translatable("packcontrol.snapshot.pick_folder").getString(), start);
        if (selected == null || selected.isBlank()) {
            minecraft.setScreen(new PackControlScreen(parent, view, filePage, notice));
            return;
        }
        PackSnapshotService.LoadedSnapshot result = PackSnapshotService.selectSnapshot(Path.of(selected));
        Component nextNotice = Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
        pendingInstallPlan = null;
        minecraft.setScreen(new PackControlScreen(parent, view, filePage, nextNotice));
    }

    private void loadGithubSnapshot() {
        Component nextNotice = Component.translatable("packcontrol.snapshot.github_planned").withStyle(ChatFormatting.YELLOW);
        minecraft.setScreen(new PackControlScreen(parent, view, filePage, nextNotice));
    }

    private void downloadPack() {
        if (snapshotChoices.isEmpty()) {
            notice = Component.translatable("packcontrol.snapshot.none").withStyle(ChatFormatting.YELLOW);
            return;
        }
        PackSnapshotService.selectSnapshot(snapshotChoices.get(selectedSnapshotIndex).snapshotDirectory());
        if (pendingInstallPlan == null || !pendingInstallPlan.success()) {
            pendingInstallPlan = SnapshotDownloadService.previewSelected();
            notice = Component.literal(pendingInstallPlan.message() + (pendingInstallPlan.success() ? " Click Download Pack again to install." : "")).withStyle(pendingInstallPlan.success() ? ChatFormatting.YELLOW : ChatFormatting.RED);
            return;
        }
        if (taskRunning) {
            return;
        }
        taskRunning = true;
        taskProgress = SnapshotProgress.step("Queued", 0, 1, "Download Pack");
        runningTask = CompletableFuture.runAsync(() -> {
            SnapshotDownloadService.SnapshotInstallResult result = SnapshotDownloadService.installSelected(this::setTaskProgress);
            pendingInstallPlan = null;
            pendingNotice = Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
            taskRunning = false;
            refreshRequested = true;
        });
    }

    private void savePreset() {
        PackControlPresetService.PresetSaveResult result = PackControlPresetService.saveCurrent();
        notice = Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private void loadPreset() {
        PackControlPresetService.PresetSaveResult result = PackControlPresetService.loadFirst();
        Component nextNotice = Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
        minecraft.setScreen(new PackControlScreen(parent, View.PACK_FILES, filePage, nextNotice));
    }

    private void generatePackwiz() {
        PackwizGenerator.PackwizGenerationResult result = PackwizGenerator.generate();
        notice = Component.literal(result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
        scanResult = PackFileSelectionService.scan();
        treeView = PackFileTreeService.view(filePage, TREE_ROWS_PER_PAGE);
        selectedSnapshot = PackSnapshotService.selectedSnapshot();
    }

    private void checkReleases() {
        if (taskRunning) {
            return;
        }
        PackControlConfig.PackControlUserConfig user = PackControlConfig.user();
        if (!user.useGitHubReleases) {
            notice = Component.translatable("packcontrol.github.disabled").withStyle(ChatFormatting.YELLOW);
            return;
        }
        taskRunning = true;
        taskProgress = SnapshotProgress.step("Checking", 0, 1, "GitHub Releases");
        runningTask = CompletableFuture.runAsync(() -> {
            PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
            GitHubReleaseDiscoveryService.CheckResult result;
            try {
                result = releaseService(user.githubApiBaseUrl).check(
                        new GitHubReleaseDiscoveryService.CheckRequest(
                                pack.targetGithubRepository,
                                pack.updateChannel,
                                pack.installedVersion,
                                Duration.ofMinutes(user.updateCheckIntervalMinutes)
                        ),
                        CancellationToken.none()
                );
                pack.lastUpdateCheck = result.checkedAt().toString();
                pack.lastReleaseCheckStatus = result.message();
                if (result.release() != null) {
                    pack.latestKnownVersion = result.release().version();
                }
                PackControlConfig.savePack();
                ChatFormatting color = switch (result.status()) {
                    case UPDATE_AVAILABLE -> ChatFormatting.GREEN;
                    case UP_TO_DATE, NO_MATCHING_RELEASE -> ChatFormatting.YELLOW;
                    case INVALID_RELEASE, INVALID_CONFIGURATION, NETWORK_ERROR -> ChatFormatting.RED;
                };
                pendingNotice = Component.literal(result.message()).withStyle(color);
            } catch (RuntimeException exception) {
                pendingNotice = Component.literal("GitHub release check failed: " + exception.getMessage())
                        .withStyle(ChatFormatting.RED);
            } finally {
                taskRunning = false;
                refreshRequested = true;
            }
        });
    }

    private static synchronized GitHubReleaseDiscoveryService releaseService(String apiBase) {
        if (releaseService == null || !apiBase.equals(releaseServiceApiBase)) {
            releaseService = new GitHubReleaseDiscoveryService(
                    URI.create(apiBase),
                    new org.wodichka.packcontrol.updateformat.PackHttpClient(),
                    java.time.Clock.systemUTC()
            );
            releaseServiceApiBase = apiBase;
        }
        return releaseService;
    }

    private void setTaskProgress(SnapshotProgress progress) {
        taskProgress = progress;
        PackControlConfig.pack().lastSnapshotProgress = progress.display();
    }

    private void open(View nextView, int nextPage) {
        minecraft.setScreen(new PackControlScreen(parent, nextView, nextPage, notice));
    }

    @Override
    public void tick() {
        if (pendingNotice != null) {
            notice = pendingNotice;
            pendingNotice = null;
        }
        if (refreshRequested) {
            refreshRequested = false;
            minecraft.setScreen(new PackControlScreen(parent, view, filePage, notice));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.fill(0, 6, width, 9, BORDER_BRIGHT);
        graphics.fill(0, 10, width, 11, 0xFF2F376A);

        int left = contentLeft();
        int top = contentTop();
        int contentWidth = contentWidth();
        int contentHeight = contentHeight();
        int leftWidth = leftPanelWidth();
        int gap = 16;
        int rightLeft = left + leftWidth + gap;
        int rightWidth = contentWidth - leftWidth - gap;
        int panelTop = panelTop(top);
        int panelHeight = panelHeight();

        drawTitle(graphics, left, top, contentWidth);
        drawTopTabsBar(graphics, left, top + 36, contentWidth);

        if (view == View.DASHBOARD) {
            drawPanel(graphics, left, panelTop, leftWidth, panelHeight, "packcontrol.panel.actions");
            drawPanel(graphics, rightLeft, panelTop, rightWidth, panelHeight, "packcontrol.panel.status");
            drawDashboard(graphics, left, panelTop, leftWidth, rightLeft, rightWidth);
        } else {
            drawPanel(graphics, left, panelTop, contentWidth, panelHeight, "packcontrol.panel.pack_files");
            drawPackFiles(graphics, left + 28, panelTop + 54, contentWidth - 56, panelTop);
        }

        if (!notice.getString().equals(Component.translatable("packcontrol.notice.ready").getString())) {
            int noticeY = view == View.PACK_FILES ? panelTop + 41 : top + contentHeight - 18;
            graphics.drawCenteredString(font, fit(notice.getString(), contentWidth - 60), left + contentWidth / 2, noticeY, WARNING);
        }

        if (dialogMode == DialogMode.NONE) {
            for (Renderable renderable : widgets) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        } else {
            for (int i = 0; i < Math.min(baseWidgetCount, widgets.size()); i++) {
                widgets.get(i).render(graphics, mouseX, mouseY, partialTick);
            }
            if (dialogMode == DialogMode.SAVE_SNAPSHOT) {
                drawSnapshotDialog(graphics);
            } else if (dialogMode == DialogMode.LOAD_SNAPSHOT) {
                drawLoadSnapshotDialog(graphics);
            }
            for (int i = baseWidgetCount; i < widgets.size(); i++) {
                widgets.get(i).render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private void drawDashboard(GuiGraphics graphics, int left, int panelTop, int leftWidth, int rightLeft, int rightWidth) {
        drawSnapshotSelector(graphics, left + 22, panelTop + 166, leftWidth - 44);
        drawActionHints(graphics, left, panelTop + 254, leftWidth);
        drawStatusPanel(graphics, rightLeft + 20, panelTop + 54, rightWidth - 40);
        drawFuturePanels(graphics, rightLeft + 20, panelTop + 174, rightWidth - 40);
        drawActivity(graphics, rightLeft + 20, panelTop + 290, rightWidth - 40);
    }

    private void drawPackFiles(GuiGraphics graphics, int x, int y, int width, int panelTop) {
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        int leftColumn = Math.max(360, width / 2);

        graphics.drawString(font, Component.literal("Pack root: " + fit(String.valueOf(PackControlConfig.gameDirectory()), leftColumn - 12)), x, y, MUTED, false);
        graphics.drawString(font, Component.literal("Selected: " + scanResult.includedCount() + " files"), x, y + 14, TEXT, false);
        graphics.drawString(font, Component.literal("Skipped: " + scanResult.skippedCount()), x + 150, y + 14, MUTED, false);
        graphics.drawString(font, Component.literal("Version rules: " + fit(pack.selectionVersion, 160)), x + leftColumn, y, MUTED, false);
        graphics.drawString(font, Component.literal("Snapshot: " + fit(selectedSnapshotName(), width - leftColumn - 20)), x + leftColumn, y + 14, pack.unresolvedSnapshotMods == 0 ? GOOD : WARNING, false);
        int listTop = y + 48;
        if (taskRunning) {
            graphics.drawString(font, Component.literal("Progress: " + fit(progressText(), width - leftColumn - 20)), x + leftColumn, y + 28, WARNING, false);
            drawProgressBar(graphics, x + leftColumn, y + 42, width - leftColumn - 20, 6);
            listTop = y + 62;
        }
        graphics.drawString(font, Component.translatable("packcontrol.files.tree_title"), x, listTop, TEXT, false);
        graphics.drawString(font, Component.literal("Rows: " + treeView.totalRows()), x + 128, listTop, MUTED, false);
        graphics.drawString(font, Component.literal("Snapshot file: " + fit(selectedSnapshotPath(), 260)), x + width - 360, listTop, MUTED, false);

        int rowY = treeStartY(panelTop);
        for (PackFileTreeService.TreeRow row : treeView.rows()) {
            drawTreeRow(graphics, x, rowY, width, row);
            rowY += TREE_ROW_HEIGHT;
        }

        int treeFooterY = treeStartY(panelTop) + TREE_ROWS_PER_PAGE * TREE_ROW_HEIGHT + 10;
        String missing = treeView.missingFolders().isEmpty()
                ? "Optional folders: all default folders are present"
                : "Optional folders not found: " + String.join(", ", treeView.missingFolders());
        graphics.drawString(font, fit(missing, width - 20), x, treeFooterY, treeView.missingFolders().isEmpty() ? GOOD : WARNING, false);

        int patternInfoY = panelTop + panelHeight() - 140;
        graphics.drawString(font, Component.translatable("packcontrol.files.patterns_title"), x, patternInfoY, TEXT, false);
        graphics.drawString(font, Component.literal("Include " + pack.includePatterns.size()), x + 132, patternInfoY, MUTED, false);
        graphics.drawString(font, Component.literal("Exclude " + pack.excludePatterns.size()), x + 220, patternInfoY, MUTED, false);
        graphics.drawString(font, Component.literal("Saved " + fit(pack.lastSavedPreset, 140)), x + 310, patternInfoY, MUTED, false);
        graphics.drawString(font, Component.literal("Patterns: config/packcontrol-presets"), x + width - 260, patternInfoY, MUTED, false);
    }

    private void drawTreeRow(GuiGraphics graphics, int x, int y, int width, PackFileTreeService.TreeRow row) {
        int indent = Math.min(168, row.depth() * 14);
        int textX = x + indent + 50;
        int accent = row.partial() ? WARNING : row.selected() ? GOOD : ERROR;
        int fill = row.partial() ? 0xBB2A2617 : row.selected() ? 0xCC141D2E : 0xAA26151A;
        graphics.fill(x, y - 3, x + width, y + 14, fill);
        graphics.fill(x, y - 3, x + 2, y + 14, accent);

        String suffix = row.directory() ? "/  " + row.childCount() + " items" : "  " + readableSize(row.size());
        int nameColor = row.partial() ? WARNING : row.selected() ? TEXT : 0xFFFFA0A0;
        graphics.drawString(font, fit(row.name() + suffix, width - indent - 64), textX, y, nameColor, false);
    }

    private void drawTopTabsBar(GuiGraphics graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 24, 0x66121827);
        graphics.fill(x, y + 23, x + width, y + 24, BORDER);
    }

    private void drawTitle(GuiGraphics graphics, int left, int top, int contentWidth) {
        graphics.drawCenteredString(font, title, left + contentWidth / 2, top + 8, TEXT);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.screen.subtitle"), left + contentWidth / 2, top + 24, MUTED);
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int w, int h, String titleKey) {
        graphics.fill(x, y, x + w, y + h, PANEL);
        graphics.fill(x, y, x + w, y + 1, BORDER);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF0B1020);
        graphics.fill(x, y, x + 1, y + h, BORDER);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF0B1020);
        graphics.fill(x, y + 38, x + w, y + 39, BORDER);
        graphics.drawCenteredString(font, Component.translatable(titleKey), x + w / 2, y + 15, TEXT);
    }

    private void drawSnapshotSelector(GuiGraphics graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 70, PANEL_DARK);
        graphics.fill(x, y, x + 3, y + 70, BUTTON_ACCENT);
        graphics.drawString(font, Component.translatable("packcontrol.snapshot.selected"), x + 10, y + 8, TEXT, false);
        graphics.drawString(font, fit(selectedSnapshotName(), width - 20), x + 10, y + 23, snapshotChoices.isEmpty() ? WARNING : GOOD, false);
        graphics.drawString(font, fit(selectedSnapshotPath(), width - 20), x + 10, y + 38, MUTED, false);
        String count = snapshotChoices.isEmpty() ? "0/0" : (selectedSnapshotIndex + 1) + "/" + snapshotChoices.size();
        graphics.drawString(font, count, x + 10, y + 53, MUTED, false);
    }

    private void drawActionHints(GuiGraphics graphics, int x, int y, int width) {
        drawMiniCard(graphics, x + 22, y, width - 44, 58, "packcontrol.card.sync.title", "packcontrol.card.sync.body", WARNING);
        drawMiniCard(graphics, x + 22, y + 68, width - 44, 58, "packcontrol.card.github.title", "packcontrol.card.github.body", GOOD);
    }

    private void drawMiniCard(GuiGraphics graphics, int x, int y, int w, int h, String titleKey, String bodyKey, int color) {
        graphics.fill(x, y, x + w, y + h, PANEL_DARK);
        graphics.fill(x, y, x + 3, y + h, color);
        graphics.drawString(font, Component.translatable(titleKey), x + 10, y + 9, TEXT, false);
        drawWrapped(graphics, Component.translatable(bodyKey).getString(), x + 10, y + 24, w - 18, MUTED, 2);
    }

    private void drawStatusPanel(GuiGraphics graphics, int x, int y, int width) {
        drawRow(graphics, x, y, width, "packcontrol.status.installed", state.installedVersion(), WARNING);
        drawRow(graphics, x, y + 15, width, "packcontrol.status.latest", state.latestVersion(), GOOD);
        drawRow(graphics, x, y + 30, width, "packcontrol.status.channel", state.channel(), TEXT);
        drawRow(graphics, x, y + 45, width, "packcontrol.status.branch", state.branch(), TEXT);
        drawRow(graphics, x, y + 60, width, "packcontrol.status.sync", state.syncStatus(), WARNING);
        drawRow(graphics, x, y + 75, width, "packcontrol.status.last_check", state.lastCheck(), MUTED);
        drawRowLiteral(graphics, x, y + 100, width, "Snapshot", selectedSnapshotName(), PackControlConfig.pack().unresolvedSnapshotMods == 0 ? GOOD : WARNING);
        drawRowLiteral(graphics, x, y + 115, width, "Unresolved mods", String.valueOf(PackControlConfig.pack().unresolvedSnapshotMods), PackControlConfig.pack().unresolvedSnapshotMods == 0 ? GOOD : WARNING);
        drawRowLiteral(graphics, x, y + 130, width, "Progress", progressText(), taskRunning ? WARNING : MUTED);
        drawProgressBar(graphics, x, y + 146, width, 6);
        drawRowLiteral(graphics, x, y + 158, width, "Download", PackControlConfig.pack().lastDownloadStatus, MUTED);
        drawRowLiteral(graphics, x, y + 173, width, "Backup", PackControlConfig.pack().lastBackupPath.isBlank() ? "none" : PackControlConfig.pack().lastBackupPath, MUTED);
    }

    private void drawProgressBar(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF0B1020);
        int fillWidth = Math.max(0, Math.min(width, width * taskProgress.percent() / 100));
        int color = taskProgress.success() ? GOOD : ERROR;
        graphics.fill(x, y, x + fillWidth, y + height, taskRunning ? WARNING : color);
    }

    private void drawFuturePanels(GuiGraphics graphics, int x, int y, int width) {
        int half = Math.max(150, (width - 12) / 2);
        drawFutureBox(graphics, x, y, half, "packcontrol.github.title", List.of(
                row("packcontrol.github.repository", state.repository()),
                row("packcontrol.github.releases", state.releases()),
                row("packcontrol.github.commits", state.commits()),
                row("packcontrol.github.issues", state.issues()),
                row("packcontrol.github.account", state.accountStatus())
        ));
        drawFutureBox(graphics, x + half + 12, y, width - half - 12, "packcontrol.packwiz.title", List.of(
                row("packcontrol.packwiz.manifest", state.manifestPath()),
                row("packcontrol.packwiz.index", state.indexStatus()),
                row("packcontrol.packwiz.mods", state.modCount()),
                row("packcontrol.packwiz.optional", state.optionalFiles()),
                row("packcontrol.packwiz.hashes", state.hashStatus())
        ));
    }

    private String row(String key, String value) {
        return Component.translatable(key).getString() + ": " + value;
    }

    private void drawFutureBox(GuiGraphics graphics, int x, int y, int w, String titleKey, List<String> rows) {
        graphics.fill(x, y, x + w, y + 92, PANEL_DARK);
        graphics.fill(x, y, x + w, y + 1, BORDER);
        graphics.drawString(font, Component.translatable(titleKey), x + 9, y + 8, TEXT, false);
        graphics.drawString(font, Component.translatable("packcontrol.badge.planned"), x + w - 52, y + 8, WARNING, false);

        int lineY = y + 25;
        for (String line : rows) {
            graphics.drawString(font, fit(line, w - 18), x + 9, lineY, MUTED, false);
            lineY += 12;
        }
    }

    private void drawActivity(GuiGraphics graphics, int x, int y, int width) {
        graphics.drawString(font, Component.translatable("packcontrol.panel.activity"), x, y, TEXT, false);
        int lineY = y + 18;
        for (String entry : state.activity()) {
            graphics.drawString(font, "- " + fit(entry, width), x, lineY, MUTED, false);
            lineY += 12;
        }
    }


    private void drawLoadSnapshotDialog(GuiGraphics graphics) {
        int dialogWidth = Math.min(380, contentWidth() - 80);
        int dialogHeight = 156;
        int dialogX = (width - dialogWidth) / 2;
        int dialogY = contentTop() + 128;
        graphics.fill(0, 0, width, height, 0xB8050812);
        drawModalFrame(graphics, dialogX, dialogY, dialogWidth, dialogHeight);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.snapshot.load_title"), dialogX + dialogWidth / 2, dialogY + 14, TEXT);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.snapshot.load_subtitle"), dialogX + dialogWidth / 2, dialogY + 30, MUTED);
        String selected = selectedSnapshotPath();
        graphics.drawCenteredString(font, fit(selected, dialogWidth - 44), dialogX + dialogWidth / 2, dialogY + 46, WARNING);
    }


    private void drawRestartDialog(GuiGraphics graphics) {
        int dialogWidth = Math.min(380, contentWidth() - 80);
        int dialogHeight = 156;
        int dialogX = (width - dialogWidth) / 2;
        int dialogY = contentTop() + 132;
        graphics.fill(0, 0, width, height, 0xB8050812);
        drawModalFrame(graphics, dialogX, dialogY, dialogWidth, dialogHeight);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.restart.title"), dialogX + dialogWidth / 2, dialogY + 14, TEXT);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.restart.subtitle"), dialogX + dialogWidth / 2, dialogY + 30, MUTED);
        drawWrapped(graphics, Component.translatable("packcontrol.restart.body").getString(), dialogX + 22, dialogY + 48, dialogWidth - 44, WARNING, 2);
    }
    private void drawModalFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF151C2D);
        graphics.fill(x, y, x + width, y + 1, BORDER_BRIGHT);
        graphics.fill(x, y, x + 1, y + height, BORDER);
        graphics.fill(x + width - 1, y, x + width, y + height, BORDER);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF0B1020);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 38, 0x66121827);
    }
    private void drawSnapshotDialog(GuiGraphics graphics) {
        int dialogWidth = Math.min(420, contentWidth() - 80);
        int dialogHeight = 238;
        int dialogX = (width - dialogWidth) / 2;
        int dialogY = contentTop() + 96;
        int fieldX = dialogX + 22;
        graphics.fill(0, 0, width, height, 0xB8050812);
        drawModalFrame(graphics, dialogX, dialogY, dialogWidth, dialogHeight);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.snapshot.commit_title"), dialogX + dialogWidth / 2, dialogY + 14, TEXT);
        graphics.drawCenteredString(font, Component.translatable("packcontrol.snapshot.commit_subtitle"), dialogX + dialogWidth / 2, dialogY + 28, MUTED);
        drawFieldLabel(graphics, "packcontrol.snapshot.name", fieldX, dialogY + 38);
        drawFieldLabel(graphics, "packcontrol.snapshot.version", fieldX, dialogY + 76);
        drawFieldLabel(graphics, "packcontrol.snapshot.author", fieldX, dialogY + 114);
        drawFieldLabel(graphics, "packcontrol.snapshot.commit", fieldX, dialogY + 152);
    }

    private void drawFieldLabel(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(key), x, y, MUTED, false);
    }

    private void drawRow(GuiGraphics graphics, int x, int y, int width, String labelKey, String value, int valueColor) {
        String label = Component.translatable(labelKey).getString() + ":";
        graphics.drawString(font, label, x, y, MUTED, false);
        graphics.drawString(font, fit(value, width - font.width(label) - 8), x + font.width(label) + 8, y, valueColor, false);
    }

    private void drawRowLiteral(GuiGraphics graphics, int x, int y, int width, String labelText, String value, int valueColor) {
        String label = labelText + ":";
        graphics.drawString(font, label, x, y, MUTED, false);
        graphics.drawString(font, fit(value == null ? "" : value, width - font.width(label) - 8), x + font.width(label) + 8, y, valueColor, false);
    }

    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int width, int color, int maxLines) {
        String remaining = text;
        for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
            String part = fit(remaining, width);
            graphics.drawString(font, part, x, y + line * 11, color, false);
            if (part.length() >= remaining.length()) {
                return;
            }
            remaining = remaining.substring(part.endsWith("...") ? Math.max(0, part.length() - 3) : part.length()).trim();
        }
    }

    private String fit(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int suffixWidth = font.width(suffix);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (font.width(builder.toString() + c) + suffixWidth > maxWidth) {
                break;
            }
            builder.append(c);
        }
        return builder + suffix;
    }

    private int totalFilePages() {
        return treeView == null ? 1 : Math.max(1, treeView.totalPages());
    }

    private String readableSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return (size / 1024) + " KB";
        }
        return (size / (1024 * 1024)) + " MB";
    }

    private String selectedSnapshotName() {
        if (snapshotChoices.isEmpty()) {
            return selectedSnapshot != null && selectedSnapshot.success() ? selectedSnapshot.manifest().name : "none";
        }
        PackSnapshotService.SnapshotSummary summary = snapshotChoices.get(Math.max(0, Math.min(selectedSnapshotIndex, snapshotChoices.size() - 1)));
        return summary.version().isBlank() ? summary.name() : summary.name() + " / " + summary.version();
    }

    private String selectedSnapshotPath() {
        if (snapshotChoices.isEmpty()) {
            return PackControlConfig.pack().selectedSnapshotPath.isBlank() ? "No snapshot selected" : PackControlConfig.pack().selectedSnapshotPath;
        }
        return snapshotChoices.get(Math.max(0, Math.min(selectedSnapshotIndex, snapshotChoices.size() - 1))).snapshotDirectory().resolve("snapshot.json").toString();
    }

    private String defaultSnapshotName() {
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        String version = pack.packVersion == null || pack.packVersion.isBlank() ? "release-1" : pack.packVersion;
        return version.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private String progressText() {
        if (taskProgress != null && (!"Idle".equals(taskProgress.stage()) || taskRunning)) {
            return taskProgress.display();
        }
        return PackControlConfig.pack().lastSnapshotProgress;
    }

    private int contentWidth() {
        return Math.min(width - 28, 1192);
    }

    private int contentHeight() {
        return Math.min(height - 24, 640);
    }

    private int contentLeft() {
        return (width - contentWidth()) / 2;
    }

    private int contentTop() {
        return Math.max(10, (height - contentHeight()) / 2);
    }

    private int leftPanelWidth() {
        return Math.max(250, Math.min(300, contentWidth() / 3));
    }

    private int panelTop(int top) {
        return top + 66;
    }

    private int panelHeight() {
        return contentHeight() - 76;
    }

    private int treeStartY(int panelTop) {
        return panelTop + 150;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (view == View.PACK_FILES) {
            int scrollRows = Math.max(1, Math.min(2, (int) Math.ceil(Math.abs(scrollY) * 1.5D)));
            int nextPage = scrollY < 0 ? filePage + scrollRows : filePage - scrollRows;
            nextPage = Math.max(0, Math.min(totalFilePages() - 1, nextPage));
            if (nextPage != filePage) {
                open(View.PACK_FILES, nextPage);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    public void renderTransparentBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private enum View {
        DASHBOARD,
        PACK_FILES
    }

    private enum DialogMode {
        NONE,
        SAVE_SNAPSHOT,
        LOAD_SNAPSHOT,
        RESTART_REQUIRED
    }

    private static final class PackControlButton extends AbstractWidget {
        private final Runnable onPress;
        private final boolean selected;

        private PackControlButton(int x, int y, int width, int height, Component message, Runnable onPress, boolean selected) {
            super(x, y, width, height, message);
            this.onPress = onPress;
            this.selected = selected;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (active) {
                onPress.run();
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHoveredOrFocused();
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int fill = selected ? 0xFF29365A : hovered ? BUTTON_HOVER : BUTTON;
            int accent = selected ? GOOD : hovered ? GOOD : BUTTON_ACCENT;
            int textColor = active ? TEXT : 0xFF697086;

            graphics.fill(x, y, x + w, y + h, fill);
            graphics.fill(x, y, x + w, y + 1, selected || hovered ? accent : BUTTON_BORDER);
            graphics.fill(x, y + h - 1, x + w, y + h, 0xFF101725);
            graphics.fill(x, y, x + 1, y + h, selected || hovered ? accent : BUTTON_BORDER);
            graphics.fill(x + w - 1, y, x + w, y + h, 0xFF101725);
            if (w > 28) {
                graphics.fill(x + 3, y + 4, x + 5, y + h - 4, accent);
            }
            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(), x + w / 2, y + (h - 8) / 2, textColor);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
