package org.jerkar.api.file;

import org.jerkar.api.utils.JkUtilsIO;
import org.jerkar.api.utils.JkUtilsPath;
import org.jerkar.api.utils.JkUtilsString;
import org.jerkar.api.utils.JkUtilsThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A path standing for a file (not a directory). This class provides path methods relevant for files only.
 */
public final class JkPathFile {

    private final Path path;

    private JkPathFile(Path path) {
        this.path = path;
    }

    public static JkPathFile of(Path path) {
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException(path + " is a directory");
        }
        return new JkPathFile(path);
    }

    public Path get() {
        return path;
    }

    public JkPathFile createIfNotExist() {
        if (!Files.exists(path)) {
            JkUtilsPath.createDirectories(path.getParent());
            JkUtilsPath.createFile(path);
        }
        return this;
    }

    public JkPathFile copyReplacingTokens(Path to, Map<String, String> tokens, Charset charset) {
        JkPathFile.of(to).createIfNotExist();
        if (tokens.isEmpty()) {
            JkUtilsPath.copy(path, to, StandardCopyOption.REPLACE_EXISTING);
            return this;
        }
        try (Stream<String> stream = Files.lines(path, charset)) {
            List<String> result = stream.map(line -> interpolated(line, tokens))
                .collect(Collectors.toList());
            Files.write(to, result, charset, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public JkPathFile copyFrom(URL url) {
        createIfNotExist();
        JkUtilsIO.copyUrlToFile(url, this.path);
        return this;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public JkPathFile deleteIfExist() {
        if (!exists()) {
            JkUtilsPath.deleteFile(this.path);
        }
        return this;
    }

    /**
     * Returns a ASCII string representation of the checksum of this file for the specified algorithm.
     * @param algorithm Hashing algorithm as MD5, SHA-2, ...
     */
    public String getChecksum(String algorithm) {
        try (final InputStream is = Files.newInputStream(path)) {
            final MessageDigest md = MessageDigest.getInstance(algorithm);
            md.reset();
            final byte[] buf = new byte[2048];
            int len = 0;
            while ((len = is.read(buf)) != -1) {
                md.update(buf, 0, len);
            }
            final byte[] bytes = md.digest();
            return JkUtilsString.toHexString(bytes);
        } catch (final Exception e) {
            throw JkUtilsThrowable.unchecked(e);
        }
    }

    public JkPathFile write(byte[] bytes, OpenOption ... options) {
        JkUtilsPath.write(path, bytes, options);
        return this;
    }

    public JkPathFile checksum(String ... algorithms) {
        for (String algorithm : algorithms) {
            final String fileName = this.path.getFileName().toString() + "." + algorithm.toLowerCase();
            JkPathFile checksumPath = JkPathFile.of(path.resolveSibling(fileName)).deleteIfExist().write(
                    this.getChecksum(algorithm).getBytes(Charset.forName("ASCII")));
        }
        return this;
    }

    private static String interpolated(String original, Map<String, String> tokenValues) {
        boolean changed = false;
        String result = original;
        for(Map.Entry<String, String> entry : tokenValues.entrySet()) {
            String newResult = result.replace(entry.getKey(), entry.getValue());
            if (!newResult.equals(result)) {
                changed = true;
                result = newResult;
            }
        }
        if (changed) {
            return interpolated(result, tokenValues);
        }
        return result;
    }

}