package org.hmrc;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Log4j2Scanner {
    public enum Status {
        NOT_VULNERABLE, VULNERABLE, MITIGATED
    }

    private static final String JNDI_LOOKUP_CLASS_PATH = "org/apache/logging/log4j/core/lookup/JndiLookup.class";
    private static final String LOG4j_CORE_POM_PROPS = "META-INF/maven/org.apache.logging.log4j/log4j-core/pom.properties";
    private static final boolean isWindows = File.separatorChar == '\\';

    private long scanDirCount = 0;
    private long scanFileCount = 0;
    private long vulnerableFileCount = 0;
    private long fixedFileCount = 0;

    private Set<File> vulnerableFiles = new LinkedHashSet<File>();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Logpresso CVE-2021-44228 Vulnerability Scanner 1.2.5 (2021-12-14)");
            System.out.println("Usage: log4j2-scan [--fix] [--force-fix] [--trace] target_path");
            System.out.println("       Do not use --force-fix unless you know what you are doing");
            return;
        }

        boolean trace = false;
        boolean fix = false;
        boolean force = false;

        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--fix")) {
                fix = true;
            } else if (args[i].equals("--force-fix")) {
                fix = true;
                force = true;
            } else if (args[i].equals("--trace")) {
                trace = true;
            } else {
                System.out.println("unsupported option: " + args[i]);
                return;
            }
        }

        String path = args[args.length - 1];

        if (fix && !force) {
            try {
                System.out.print("This command will remove JndiLookup.class from log4j2-core binaries. Are you sure [y/N]? ");
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                String answer = br.readLine();
                if (!answer.equalsIgnoreCase("y")) {
                    System.out.println("interrupted");
                    return;
                }
            } catch (Throwable t) {
                System.out.println("error: " + t.getMessage());
                return;
            }
        }

        File f = new File(path);
        new Log4j2Scanner().run(f, fix, trace);
    }

    public void run(File f, boolean fix, boolean trace) {
        long begin = System.currentTimeMillis();
        try {
            traverse(f, fix, trace);
            if (fix)
                fix(trace);
        } finally {
            long elapsed = System.currentTimeMillis() - begin;
            System.out.println();
            System.out.println("Scanned " + scanDirCount + " directories and " + scanFileCount + " files");
            System.out.println("Found " + vulnerableFileCount + " vulnerable files");
            if (fix)
                System.out.println("Fixed " + fixedFileCount + " vulnerable files");

            System.out.printf("Completed in %.2f seconds\n", elapsed / 1000.0);
        }
    }

    private void fix(boolean trace) {
        if (!vulnerableFiles.isEmpty())
            System.out.println("");

        for (File f : vulnerableFiles) {
            if (trace)
                System.out.println("Patching " + f.getAbsolutePath());

            File backupFile = new File(f.getAbsolutePath() + ".bak");

            if (backupFile.exists()) {
                System.out.println("Error: Cannot create backup file. .bak File already exists. Skipping " + f.getAbsolutePath());
                continue;
            }

            if (copyAsIs(f, backupFile)) {
                // keep inode as is for symbolic link
                if (!truncate(f)) {
                    System.out.println("Error: Cannot patch locked file " + f.getAbsolutePath());
                    continue;
                }

                if (copyExceptJndiLookup(backupFile, f)) {
                    fixedFileCount++;
                    System.out.println("Fixed: " + f.getAbsolutePath());
                } else {
                    // rollback operation
                    copyAsIs(backupFile, f);
                }
            }
        }
    }

    private boolean truncate(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            ensureClose(raf);
        }
    }

    private boolean copyAsIs(File srcFile, File dstFile) {
        FileInputStream is = null;
        FileOutputStream os = null;

        try {
            is = new FileInputStream(srcFile);
            os = new FileOutputStream(dstFile);

            byte[] buf = new byte[32768];
            while (true) {
                int len = is.read(buf);
                if (len < 0)
                    break;

                os.write(buf, 0, len);
            }

            return true;
        } catch (Throwable t) {
            System.out.println("Error: Cannot copy file " + srcFile.getAbsolutePath() + " - " + t.getMessage());
            return false;
        } finally {
            ensureClose(is);
            ensureClose(os);
        }
    }

    private boolean copyExceptJndiLookup(File srcFile, File dstFile) {
        ZipFile srcZipFile = null;
        ZipOutputStream zos = null;

        try {
            srcZipFile = new ZipFile(srcFile);
            zos = new ZipOutputStream(new FileOutputStream(dstFile));

            Enumeration<?> e = srcZipFile.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) e.nextElement();

                if (entry.getName().equals(JNDI_LOOKUP_CLASS_PATH))
                    continue;

                if (entry.isDirectory()) {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    continue;
                }

                zos.putNextEntry(new ZipEntry(entry.getName()));

                copyZipEntry(srcZipFile, entry, zos);
            }

            return true;
        } catch (Throwable t) {
            System.out.println(
                    "Error: Cannot fix file (" + t.getMessage() + "). rollback original file " + dstFile.getAbsolutePath());
            return false;
        } finally {
            ensureClose(srcZipFile);
            ensureClose(zos);
        }
    }

    private void copyZipEntry(ZipFile srcZipFile, ZipEntry entry, ZipOutputStream zos) throws IOException {
        InputStream is = null;
        try {
            is = srcZipFile.getInputStream(entry);

            if (isScanTarget(entry.getName())) {
                copyNestedJar(is, zos);
            } else {
                byte[] buf = new byte[32768];
                while (true) {
                    int len = is.read(buf);
                    if (len < 0)
                        break;

                    zos.write(buf, 0, len);
                }
            }
        } finally {
            ensureClose(is);
        }
    }

    private void copyNestedJar(InputStream is, ZipOutputStream os) throws IOException {
        ZipInputStream zis = null;
        ZipOutputStream zos = null;
        try {
            zis = new ZipInputStream(is);
            zos = new ZipOutputStream(os);

            while (true) {
                ZipEntry zipEntry = zis.getNextEntry();
                if (zipEntry == null)
                    break;

                if (zipEntry.getName().equals(JNDI_LOOKUP_CLASS_PATH))
                    continue;

                if (zipEntry.isDirectory()) {
                    zos.putNextEntry(new ZipEntry(zipEntry.getName()));
                    continue;
                }

                zos.putNextEntry(new ZipEntry(zipEntry.getName()));

                byte[] buf = new byte[32768];
                while (true) {
                    int len = zis.read(buf);
                    if (len < 0)
                        break;

                    zos.write(buf, 0, len);
                }
            }
        } finally {
            ensureClose(zis);

            if (zos != null)
                zos.finish();
        }
    }

    private void traverse(File f, boolean fix, boolean trace) {
        String path = f.getAbsolutePath();

        if (f.isDirectory()) {
            if (isSymlink(f)) {
                if (trace)
                    System.out.println("Skipping symlink: " + path);

                return;
            }

            if (isKernelFileSystem(path)) {
                if (trace)
                    System.out.println("Skipping directory: " + path);

                return;
            }

            if (trace)
                System.out.println("Scanning directory: " + path);

            scanDirCount++;

            File[] files = f.listFiles();
            if (files == null)
                return;

            for (File file : files) {
                traverse(file, fix, trace);
            }
        } else {
            scanFileCount++;

            if (isScanTarget(path)) {
                if (trace)
                    System.out.println("Scanning file: " + path);

                scanJarFile(f, fix);
            } else {
                if (trace)
                    System.out.println("Skipping file: " + path);
            }
        }
    }

    private boolean isSymlink(File f) {
        try {
            String canonicalPath = f.getCanonicalPath();
            String absolutePath = f.getAbsolutePath();

            if (isWindows) {
                canonicalPath = canonicalPath.toUpperCase();
                absolutePath = absolutePath.toUpperCase();
            }

            return f.isDirectory() && !canonicalPath.contains(absolutePath);
        } catch (IOException e) {
        }

        return false;
    }

    private boolean isKernelFileSystem(String path) {
        return (path.equals("/proc") || path.startsWith("/proc/")) || (path.equals("/sys") || path.startsWith("/sys/")) || (path.equals("/dev") || path.startsWith("/dev/"));
    }

    private void scanJarFile(File jarFile, boolean fix) {
        ZipFile zipFile = null;
        InputStream is = null;
        boolean vulnerable = false;
        boolean needFix = false;
        try {
            zipFile = new ZipFile(jarFile);

            Status status = checkLog4jVersion(jarFile, fix, zipFile);
            vulnerable = (status != Status.NOT_VULNERABLE);
            needFix = (status == Status.VULNERABLE);

            // scan nested jar files
            Enumeration<?> e = zipFile.entries();
            while (e.hasMoreElements()) {
                ZipEntry zipEntry = (ZipEntry) e.nextElement();
                if (!zipEntry.isDirectory() && isScanTarget(zipEntry.getName())) {
                    Status nestedJarStatus = scanNestedJar(jarFile, zipFile, zipEntry);
                    vulnerable |= (nestedJarStatus != Status.NOT_VULNERABLE);
                    needFix |= (nestedJarStatus == Status.VULNERABLE);
                }
            }

            if (vulnerable)
                vulnerableFileCount++;

            if (fix && needFix)
                vulnerableFiles.add(jarFile);

        } catch (Throwable t) {
            System.out.printf("Scan error: '%s' on file: %s%n", t.getMessage(), jarFile);
        } finally {
            ensureClose(is);
            ensureClose(zipFile);
        }
    }

    private Status checkLog4jVersion(File jarFile, boolean fix, ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry(LOG4j_CORE_POM_PROPS);
        if (entry == null)
            return Status.NOT_VULNERABLE;

        InputStream is = null;
        try {
            is = zipFile.getInputStream(entry);

            String version = loadVulnerableLog4jVersion(is);
            if (version != null) {
                boolean mitigated = zipFile.getEntry(JNDI_LOOKUP_CLASS_PATH) == null;
                String path = jarFile.getAbsolutePath();
                printDetection(path, version, mitigated);
                return mitigated ? Status.MITIGATED : Status.VULNERABLE;
            }

            return Status.NOT_VULNERABLE;
        } finally {
            ensureClose(is);
        }
    }

    private void printDetection(String path, String version, boolean mitigated) {
        String msg = "[*] Found CVE-2021-44228 vulnerability in " + path + ", log4j " + version;
        if (mitigated)
            msg += " (mitigated)";

        System.out.println(msg);
    }

    private Status scanNestedJar(File fatJarFile, ZipFile zipFile, ZipEntry zipEntry) {
        InputStream is = null;
        ZipInputStream zis = null;

        String vulnerableVersion = null;
        boolean mitigated = true;

        try {
            is = zipFile.getInputStream(zipEntry);
            zis = new ZipInputStream(is);

            while (true) {
                ZipEntry entry = zis.getNextEntry();
                if (entry == null)
                    break;

                if (entry.getName().equals(LOG4j_CORE_POM_PROPS))
                    vulnerableVersion = loadVulnerableLog4jVersion(zis);

                if (entry.getName().equals(JNDI_LOOKUP_CLASS_PATH))
                    mitigated = false;
            }

            if (vulnerableVersion != null) {
                String path = fatJarFile + " (" + zipEntry.getName() + ")";
                printDetection(path, vulnerableVersion, mitigated);
                return mitigated ? Status.MITIGATED : Status.VULNERABLE;
            }

            return Status.NOT_VULNERABLE;
        } catch (IOException e) {
            String msg = "cannot scan nested jar " + fatJarFile.getAbsolutePath() + ", entry " + zipEntry.getName();
            throw new IllegalStateException(msg, e);
        } finally {
            ensureClose(zis);
            ensureClose(is);
        }
    }

    private String loadVulnerableLog4jVersion(InputStream is) throws IOException {
        Properties props = new Properties();
        props.load(is);

        String groupId = props.getProperty("groupId");
        String artifactId = props.getProperty("artifactId");
        String version = props.getProperty("version");

        if (groupId.equals("org.apache.logging.log4j") && artifactId.equals("log4j-core")) {
            String[] tokens = version.split("\\.");
            int major = Integer.parseInt(tokens[0]);
            int minor = Integer.parseInt(tokens[1]);
            int patch = 0;

            // e.g. version 2.0 has only 2 tokens
            if (tokens.length > 2)
                patch = Integer.parseInt(tokens[2]);

            if (isVulnerable(major, minor, patch))
                return version;
        }

        return null;
    }

    private boolean isScanTarget(String name) {
        String loweredName = name.toLowerCase();
        return loweredName.endsWith(".jar") || loweredName.endsWith(".war") || loweredName.endsWith(".ear");
    }

    private boolean isVulnerable(int major, int minor, int patch) {
        return major == 2 && (minor < 14 || (minor == 14 && patch <= 1));
    }

    private void ensureClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable t) {
            }
        }
    }

    private void ensureClose(ZipFile zipFile) {
        if (zipFile != null) {
            try {
                zipFile.close();
            } catch (Throwable t) {
            }
        }
    }
}