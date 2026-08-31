package jp.minecraftbackup;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.config.Configuration;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Mod(modid = AutoWorldBackupMod.MOD_ID, name = AutoWorldBackupMod.MOD_NAME,
        version = AutoWorldBackupMod.VERSION, acceptableRemoteVersions = "*",
        acceptedMinecraftVersions = "[1.7.10]")
public final class AutoWorldBackupMod {
    public static final String MOD_ID = "autoworldbackup";
    public static final String MOD_NAME = "Auto World Backup";
    public static final String VERSION = "1.0.0";

    @Mod.Instance(MOD_ID)
    public static AutoWorldBackupMod instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private MinecraftServer server;
    private File worldDirectory;
    private File backupDirectory;
    private int intervalMinutes;
    private int initialDelayMinutes;
    private int retentionCount;
    private long nextBackupAt;
    private boolean forceRequested;
    private boolean backupRunning;
    private boolean stopping;
    private boolean[] previousSavingStates;
    private Future<?> activeTask;
    private volatile BackupResult completedResult;
    private volatile String lastResult = "まだ実行されていません";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        try {
            config.load();
            intervalMinutes = config.getInt("intervalMinutes", "backup", 60, 1, 10080,
                    "バックアップ間隔（分）");
            initialDelayMinutes = config.getInt("initialDelayMinutes", "backup", 5, 0, 10080,
                    "サーバー起動後、最初のバックアップまでの時間（分）");
            retentionCount = config.getInt("retentionCount", "backup", 24, 1, 10000,
                    "保持するバックアップZIPの最大数");
            backupDirectory = new File(config.getString("backupDirectory", "backup", "backups",
                    "サーバールートからの相対パス、または絶対パス"));
        } finally {
            if (config.hasChanged()) config.save();
        }
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new BackupCommand());
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        server = MinecraftServer.getServer();
        File serverRoot = server.getFile(".").getAbsoluteFile();
        worldDirectory = new File(serverRoot, server.getFolderName()).getAbsoluteFile();
        if (!backupDirectory.isAbsolute()) backupDirectory = new File(serverRoot, backupDirectory.getPath());
        backupDirectory = backupDirectory.getAbsoluteFile();
        try {
            validateDirectories();
            if (!backupDirectory.isDirectory() && !backupDirectory.mkdirs()) {
                throw new IOException("バックアップ先を作成できません: " + backupDirectory);
            }
        } catch (IOException error) {
            lastResult = "設定エラー: " + error.getMessage();
            FMLLog.severe("[%s] %s", MOD_NAME, lastResult);
            worldDirectory = null;
            return;
        }
        nextBackupAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(initialDelayMinutes);
        FMLLog.info("[%s] Started. world=%s, backup=%s, interval=%d minutes",
                MOD_NAME, worldDirectory, backupDirectory, intervalMinutes);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || stopping) return;
        BackupResult result = completedResult;
        if (result != null) {
            completedResult = null;
            restoreSaving();
            synchronized (stateLock) {
                backupRunning = false;
                activeTask = null;
                lastResult = result.message;
            }
            if (result.error == null) FMLLog.info("[%s] %s", MOD_NAME, result.message);
            else FMLLog.severe("[%s] %s", MOD_NAME, result.message);
        }
        boolean due;
        synchronized (stateLock) {
            due = !backupRunning && worldDirectory != null
                    && (forceRequested || System.currentTimeMillis() >= nextBackupAt);
            if (due) forceRequested = false;
        }
        if (due) startBackup();
    }

    private void startBackup() {
        synchronized (stateLock) {
            if (backupRunning || stopping) return;
            backupRunning = true;
            nextBackupAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(intervalMinutes);
        }
        try {
            saveAndDisableSaving();
        } catch (Exception error) {
            restoreSaving();
            synchronized (stateLock) {
                backupRunning = false;
                lastResult = "バックアップ前のワールド保存に失敗しました: " + error.getMessage();
            }
            FMLLog.severe("[%s] %s", MOD_NAME, lastResult);
            return;
        }
        final File part = new File(backupDirectory, fileName() + ".part");
        final File destination = new File(backupDirectory,
                part.getName().substring(0, part.getName().length() - 5));
        FMLLog.info("[%s] Creating %s", MOD_NAME, destination);
        activeTask = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (part.exists() && !part.delete()) throw new IOException("一時ファイルを削除できません: " + part);
                    createZip(worldDirectory, part);
                    moveCompleted(part, destination);
                    pruneBackups();
                    completedResult = new BackupResult("バックアップ完了: " + destination.getName(), null);
                } catch (Throwable error) {
                    if (part.exists() && !part.delete()) part.deleteOnExit();
                    completedResult = new BackupResult("バックアップ失敗: " + error.getMessage(), error);
                }
            }
        });
    }

    private void saveAndDisableSaving() throws MinecraftException {
        WorldServer[] worlds = server.worldServers;
        previousSavingStates = new boolean[worlds.length];
        for (int i = 0; i < worlds.length; i++) {
            WorldServer world = worlds[i];
            if (world != null) previousSavingStates[i] = world.levelSaving;
        }
        for (WorldServer world : worlds) if (world != null) world.saveAllChunks(true, null);
        for (WorldServer world : worlds) if (world != null) world.levelSaving = true;
    }

    private void restoreSaving() {
        if (server == null || previousSavingStates == null) return;
        WorldServer[] worlds = server.worldServers;
        for (int i = 0; i < worlds.length; i++) {
            if (worlds[i] != null) {
                worlds[i].levelSaving = i < previousSavingStates.length && previousSavingStates[i];
            }
        }
        previousSavingStates = null;
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        stopping = true;
        Future<?> task;
        synchronized (stateLock) {
            task = activeTask;
        }
        if (task != null) {
            FMLLog.info("[%s] Waiting for the active backup before shutdown", MOD_NAME);
            try {
                task.get();
            } catch (Exception error) {
                FMLLog.severe("[%s] Active backup ended with an error: %s", MOD_NAME, error.getMessage());
            }
        }
        restoreSaving();
        executor.shutdown();
        server = null;
    }

    private void validateDirectories() throws IOException {
        if (!worldDirectory.isDirectory()) throw new IOException("ワールドが見つかりません: " + worldDirectory);
        String worldPath = worldDirectory.getCanonicalPath();
        String backupPath = backupDirectory.getCanonicalPath();
        if (backupPath.equals(worldPath) || backupPath.startsWith(worldPath + File.separator)) {
            throw new IOException("バックアップ先をワールド内には設定できません");
        }
    }

    private static String fileName() {
        return "world-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".zip";
    }

    private void createZip(File root, File destination) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destination)));
        try {
            addToZip(root, root.getName(), zip);
        } finally {
            zip.close();
        }
    }

    private void addToZip(File file, String entryName, ZipOutputStream zip) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("バックアップが中断されました");
        if (file.getName().equals("session.lock") || Files.isSymbolicLink(file.toPath())) return;
        if (file.isDirectory()) {
            zip.putNextEntry(new ZipEntry(entryName.replace('\\', '/') + "/"));
            zip.closeEntry();
            File[] children = file.listFiles();
            if (children == null) throw new IOException("フォルダーを読み取れません: " + file);
            Arrays.sort(children, new Comparator<File>() {
                @Override
                public int compare(File left, File right) { return left.getName().compareTo(right.getName()); }
            });
            for (File child : children) addToZip(child, entryName + "/" + child.getName(), zip);
            return;
        }
        ZipEntry entry = new ZipEntry(entryName.replace('\\', '/'));
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);
        BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
        } finally {
            input.close();
            zip.closeEntry();
        }
    }

    private static void moveCompleted(File part, File destination) throws IOException {
        try {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupported) {
            Files.move(part.toPath(), destination.toPath());
        }
    }

    private void pruneBackups() throws IOException {
        File[] files = backupDirectory.listFiles();
        if (files == null) throw new IOException("バックアップ先を読み取れません: " + backupDirectory);
        List<File> backups = new ArrayList<File>();
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("world-backup-") && file.getName().endsWith(".zip")) backups.add(file);
        }
        Collections.sort(backups, new Comparator<File>() {
            @Override
            public int compare(File left, File right) { return left.getName().compareTo(right.getName()); }
        });
        while (backups.size() > retentionCount) {
            File old = backups.remove(0);
            if (!old.delete()) throw new IOException("古いバックアップを削除できません: " + old);
        }
    }

    private final class BackupCommand extends CommandBase {
        @Override public String getCommandName() { return "autobackup"; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/autobackup <now|status>"; }
        @Override public int getRequiredPermissionLevel() { return 4; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length != 1) {
                sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
                return;
            }
            if ("now".equalsIgnoreCase(args[0])) {
                synchronized (stateLock) {
                    if (backupRunning) sender.addChatMessage(new ChatComponentText("バックアップは既に実行中です"));
                    else if (worldDirectory == null) sender.addChatMessage(new ChatComponentText("バックアップ設定が無効です: " + lastResult));
                    else {
                        forceRequested = true;
                        sender.addChatMessage(new ChatComponentText("バックアップを予約しました"));
                    }
                }
                return;
            }
            if ("status".equalsIgnoreCase(args[0])) {
                synchronized (stateLock) {
                    sender.addChatMessage(new ChatComponentText("状態: " + (backupRunning ? "実行中" : "待機中") + " / " + lastResult));
                }
                return;
            }
            sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
        }
    }

    private static final class BackupResult {
        final String message;
        final Throwable error;
        BackupResult(String message, Throwable error) { this.message = message; this.error = error; }
    }
}
