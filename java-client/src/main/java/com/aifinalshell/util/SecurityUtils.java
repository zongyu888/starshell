package com.aifinalshell.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security utilities: command safety checking + AES-GCM encryption for sensitive data.
 * Encryption uses PBKDF2 derived key from a machine-bound secret.
 */
public class SecurityUtils {

    private static final Logger logger = LoggerFactory.getLogger(SecurityUtils.class);

    // ========== Command Safety ==========

    private static final Pattern DANGEROUS_COMMANDS = Pattern.compile(
            "rm\\s+(-rf|--force)\\s+(/|~|\\$HOME|\\*)|" +
            "mkfs\\.|" +
            "dd\\s+if=.*of=/dev/|" +
            "format\\s+|" +
            ":\\(\\)\\s*\\{\\s*:|\\|\\s*:&\\s*\\};:|" +
            "chmod\\s+-R\\s+777\\s+/|" +
            "chown\\s+-R\\s+.*\\s+/\\s*$|" +
            "mv\\s+/\\s+|" +
            ">\\s*/dev/sd|" +
            "shutdown|" +
            "reboot|" +
            "halt|" +
            "init\\s+0|" +
            "kill\\s+-9\\s+1\\b|" +
            "systemctl\\s+stop\\s+(sshd|network|dbus)|" +
            "iptables\\s+-F|" +
            "echo\\s+.*>\\s*/proc/sysrq-trigger|" +
            "dd\\s+if=/dev/zero.*of=/dev/sd|" +
            "\\bshred\\s+.*-f.*/|" +
            "\\bwipe\\s+-f\\s+/",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SUSPICIOUS_PATTERNS = Pattern.compile(
            "password\\s*=|" +
            "passwd\\s+|" +
            "shadow\\s+|" +
            "/etc/passwd|" +
            "/etc/shadow|" +
            "curl\\s+.*\\|\\s*(sh|bash)|" +
            "wget\\s+.*\\|\\s*(sh|bash)|" +
            "chmod\\s+\\+sx|" +
            "\\bsudo\\s+rm\\b",
            Pattern.CASE_INSENSITIVE
    );

    public static boolean isDangerousCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        return DANGEROUS_COMMANDS.matcher(command).find();
    }

    public static boolean isSuspiciousCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        return SUSPICIOUS_PATTERNS.matcher(command).find();
    }

    /**
     * Sanitize input by escaping shell metacharacters.
     * Instead of removing characters (which breaks commands), we escape them.
     */
    public static String sanitizeInput(String input) {
        if (input == null) {
            return "";
        }
        // Escape dangerous shell metacharacters by single-quoting
        if (input.contains("'")) {
            // If input contains single quotes, use the '\'' escape pattern
            return "'" + input.replace("'", "'\\''") + "'";
        }
        return "'" + input + "'";
    }

    /**
     * Sanitize a path/identifier (alphanumeric, dash, underscore, dot, slash only).
     */
    public static String sanitizePath(String path) {
        if (path == null) return "";
        // Only allow safe path characters
        return path.replaceAll("[^a-zA-Z0-9._/\\-]", "");
    }

    /**
     * Sanitize a port number to prevent command injection.
     */
    public static String sanitizePort(String port) {
        if (port == null) return "";
        return port.replaceAll("[^0-9]", "");
    }

    public static String getSecurityWarning(String command) {
        if (isDangerousCommand(command)) {
            return "⚠️ 危险命令！此操作可能导致系统损坏或数据丢失。";
        }
        if (isSuspiciousCommand(command)) {
            return "⚠️ 可疑命令！可能涉及敏感信息或远程执行。";
        }
        return null;
    }

    // ========== AES-GCM Encryption ==========

    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12;   // bytes
    private static final int SALT_LENGTH = 16;     // bytes
    private static final int KEY_LENGTH = 256;      // bits
    private static final int ITERATION_COUNT = 65536;

    /**
     * C6: 旧版本硬编码密钥常量，保留作为 decrypt 回退，确保升级后历史加密值仍可解密。
     */
    private static final String LEGACY_MACHINE_SECRET = "AiFinalShell_2024_Security_Key_v1";

    /**
     * C6: 机器指纹缓存（volatile + 双检，避免每次加密都遍历网卡）。
     */
    private static volatile String cachedFingerprint = null;

    /**
     * Machine-bound secret for key derivation.
     * C6: 改为机器指纹派生（MAC + user.name + os.name），提升密钥文件跨机不可移植性。
     */
    private static final String MACHINE_SECRET = computeMachineFingerprint();

    /**
     * C6: 采集机器指纹——取首个有效网卡 MAC + 用户名 + 操作系统名拼接。
     * 采集失败时回退到旧常量，保证加密功能可用（logger.warn 提示）。
     */
    private static String computeMachineFingerprint() {
        String cached = cachedFingerprint;
        if (cached != null) return cached;

        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) ifs = Collections.enumeration(Collections.emptyList());
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                try {
                    if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
                    byte[] mac = ni.getHardwareAddress();
                    if (mac == null || mac.length == 0) continue;
                    for (byte b : mac) sb.append(String.format("%02x", b));
                    break; // 取第一个有效 MAC 即可
                } catch (Exception ignored) {
                    // 某些虚拟网卡可能抛异常，跳过继续
                }
            }
        } catch (Exception e) {
            logger.debug("遍历网卡失败: {}", e.getMessage());
        }

        // 辅助因子：用户名 + 操作系统，提升跨用户/跨机区分度
        sb.append("|").append(System.getProperty("user.name", ""));
        sb.append("|").append(System.getProperty("os.name", ""));

        String fp = sb.toString();
        if (fp.length() < 3) {
            logger.warn("机器指纹采集失败，回退到旧密钥常量（密钥文件跨机可移植性降低）");
            fp = LEGACY_MACHINE_SECRET;
        }
        cachedFingerprint = fp;
        return fp;
    }

    /**
     * Derive an AES key from the given secret + salt via PBKDF2.
     */
    private static SecretKey deriveKey(byte[] salt, String secret) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
            PBEKeySpec spec = new PBEKeySpec(
                    secret.toCharArray(),
                    salt,
                    ITERATION_COUNT,
                    KEY_LENGTH
            );
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    /** 向后兼容：用当前机器指纹派生密钥。 */
    private static SecretKey deriveKey(byte[] salt) {
        return deriveKey(salt, MACHINE_SECRET);
    }

    /**
     * Encrypt a plaintext string using AES-GCM.
     * Returns Base64-encoded string containing salt + IV + ciphertext.
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_LENGTH];
            random.nextBytes(salt);
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine: salt(16) + iv(12) + ciphertext
            byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(iv, 0, combined, salt.length, iv.length);
            System.arraycopy(ciphertext, 0, combined, salt.length + iv.length, ciphertext.length);

            return "ENC:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt an AES-GCM encrypted string.
     * Handles both encrypted (ENC: prefix) and legacy plaintext values.
     */
    public static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        // If not encrypted (no prefix), return as-is for backward compatibility
        if (!encrypted.startsWith("ENC:")) {
            return encrypted;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted.substring(4));

            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - SALT_LENGTH - GCM_IV_LENGTH];

            System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            // C6: 先用机器指纹派生密钥解密；失败则回退旧常量（向后兼容历史加密值）
            try {
                SecretKey key = deriveKey(salt, MACHINE_SECRET);
                Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 机器指纹不匹配（可能为旧版本加密值），尝试旧常量回退
            }
            try {
                SecretKey legacyKey = deriveKey(salt, LEGACY_MACHINE_SECRET);
                Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, legacyKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 两种密钥均失败，返回原值（可能是损坏数据或明文残留）
                return encrypted;
            }
        } catch (Exception e) {
            // If decryption fails, return original (might be legacy plaintext)
            return encrypted;
        }
    }

    /**
     * Check if a value is encrypted.
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith("ENC:");
    }
}
