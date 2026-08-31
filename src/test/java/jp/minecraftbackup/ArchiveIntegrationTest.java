package jp.minecraftbackup;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ArchiveIntegrationTest {
    public static void main(String[] args) throws Exception {
        File testRoot = new File("build/archive-integration-test").getAbsoluteFile();
        deleteTree(testRoot);
        File world = new File(testRoot, "world");
        File region = new File(world, "region");
        File backups = new File(testRoot, "backups");
        if (!region.mkdirs() || !backups.mkdirs()) throw new AssertionError("Test directories were not created");
        Files.write(new File(region, "r.0.0.mca").toPath(), new byte[] {1, 2, 3});
        Files.write(new File(world, "level.dat").toPath(), new byte[] {4, 5});
        Files.write(new File(world, "session.lock").toPath(), new byte[] {6});

        AutoWorldBackupMod mod = new AutoWorldBackupMod();
        Method createZip = AutoWorldBackupMod.class.getDeclaredMethod("createZip", File.class, File.class);
        createZip.setAccessible(true);
        File part = new File(backups, "world-backup-20200101-000002.zip.part");
        createZip.invoke(mod, world, part);

        Method move = AutoWorldBackupMod.class.getDeclaredMethod("moveCompleted", File.class, File.class);
        move.setAccessible(true);
        File completed = new File(backups, "world-backup-20200101-000002.zip");
        move.invoke(null, part, completed);
        assertTrue(completed.isFile() && !part.exists(), "Completed ZIP move failed");

        Set<String> entries = new HashSet<String>();
        ZipFile zip = new ZipFile(completed);
        try {
            Enumeration<? extends ZipEntry> values = zip.entries();
            while (values.hasMoreElements()) entries.add(values.nextElement().getName());
        } finally {
            zip.close();
        }
        assertTrue(entries.contains("world/level.dat"), "level.dat is missing");
        assertTrue(entries.contains("world/region/r.0.0.mca"), "region file is missing");
        assertTrue(!entries.contains("world/session.lock"), "session.lock must be excluded");

        Files.write(new File(backups, "world-backup-20200101-000000.zip").toPath(), new byte[] {1});
        Files.write(new File(backups, "world-backup-20200101-000001.zip").toPath(), new byte[] {1});
        setField(mod, "backupDirectory", backups);
        setField(mod, "retentionCount", 1);
        Method prune = AutoWorldBackupMod.class.getDeclaredMethod("pruneBackups");
        prune.setAccessible(true);
        prune.invoke(mod);
        File[] remaining = backups.listFiles();
        int zipCount = 0;
        if (remaining != null) {
            for (File file : remaining) if (file.getName().endsWith(".zip")) zipCount++;
        }
        assertTrue(zipCount == 1 && completed.exists(), "Retention did not keep only the newest ZIP");
        System.out.println("Archive integration test passed");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void deleteTree(File file) throws Exception {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) throw new Exception("Could not delete " + file);
    }
}
