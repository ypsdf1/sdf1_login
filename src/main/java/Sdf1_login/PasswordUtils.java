package Sdf1_login;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordUtils {

    private static final SecureRandom RANDOM =
            new SecureRandom();

    public static String generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest md =
                    MessageDigest.getInstance("SHA-256");
            // 清理非 Base64 字符，防止数据库中转义字符导致解析错误
            String cleanSalt = salt.replaceAll("[^A-Za-z0-9+/=]", "");
            if (cleanSalt.isEmpty()) {
                throw new IllegalArgumentException("Invalid salt: " + salt);
            }
            md.update(Base64.getDecoder().decode(cleanSalt));
            byte[] hash =
                    md.digest(password.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean validate(String password) {
        if (password == null) return false;
        if (password.length() < 6 || password.length() > 20)
            return false;
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            if (Character.isLowerCase(c)) hasLower = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        return hasUpper && hasLower && hasDigit;
    }

    public static String generateTempPassword() {
        String chars =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(
                    RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public static String generateInviteCode() {
        String chars =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(
                    RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public static String generateVerifyCode() {
        int code = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(code);
    }
}
