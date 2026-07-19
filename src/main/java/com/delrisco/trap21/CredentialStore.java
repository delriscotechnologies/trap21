package com.delrisco.trap21;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class CredentialStore {
    record UserAccount(String username, String password, AccessProfile profile) {
    }

    private final Map<String, UserAccount> accounts = new LinkedHashMap<>();

    CredentialStore() {
        add("admin", "admin123", AccessProfile.ADMIN);
        add("administrator", "P@ssw0rd", AccessProfile.ADMIN);
        add("ftp", "112233", AccessProfile.TRANSFER);
        add("ftpadmin", "qwerty123", AccessProfile.ADMIN);
        add("ftpuser", "87654321", AccessProfile.TRANSFER);
        add("backup", "Aa112233", AccessProfile.BACKUP);
        add("operator", "Password@123", AccessProfile.OPERATOR);
        add("service", "Admin123", AccessProfile.SERVICE);
        add("guest", "121212", AccessProfile.GUEST);
    }

    Optional<UserAccount> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        String normalized = username.toLowerCase(Locale.ROOT);
        if ("anonymous".equals(normalized) && validAnonymousPassword(password)) {
            return Optional.of(new UserAccount("anonymous", "<email>", AccessProfile.ANONYMOUS));
        }
        UserAccount account = accounts.get(normalized);
        return account != null && constantTimeEquals(account.password(), password)
                ? Optional.of(account)
                : Optional.empty();
    }

    private void add(String username, String password, AccessProfile profile) {
        accounts.put(username, new UserAccount(username, password, profile));
    }

    private static boolean validAnonymousPassword(String password) {
        int at = password.indexOf('@');
        return at > 0 && at < password.length() - 1 && password.length() <= 254;
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        int difference = expected.length() ^ presented.length();
        int length = Math.max(expected.length(), presented.length());
        for (int index = 0; index < length; index++) {
            char left = index < expected.length() ? expected.charAt(index) : 0;
            char right = index < presented.length() ? presented.charAt(index) : 0;
            difference |= left ^ right;
        }
        return difference == 0;
    }
}
