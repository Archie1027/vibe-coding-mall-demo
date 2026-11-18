package com.zhicai.platform.identity;

import com.zhicai.platform.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class AuthService {
    private static final Duration LIFETIME = Duration.ofHours(2);
    private final IdentityMapper accounts;
    private final SessionStore sessions;
    private final LoginThrottle throttle;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(IdentityMapper accounts, SessionStore sessions, LoginThrottle throttle, PasswordEncoder passwords, Clock clock) {
        this.accounts = accounts; this.sessions = sessions; this.throttle = throttle;
        this.passwords = passwords; this.clock = clock;
        this.dummyHash = passwords.encode(Base64.getEncoder().encodeToString(random.generateSeed(24)));
    }

    public Token login(AccountType type, String username, String password, String address) {
        String identity = type + ":" + username;
        var reservation = throttle.reserve(identity, address);
        boolean invalidCredentials = false;
        try {
            AccountRow row = accounts.findByUsername(type, username);
            boolean matches = passwords.matches(password, row == null ? dummyHash : row.passwordHash());
            if (row == null || row.accountType() != type || !row.enabled() || !matches) {
                invalidCredentials = true;
                throw ApiException.unauthenticated();
            }
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            // Server stores only a token hash and subject/version, never the bearer secret.
            sessions.put(TokenHash.sha256(token), row.id() + ":" + row.version(), LIFETIME);
            return new Token(token, "Bearer", clock.instant().plus(LIFETIME));
        } finally {
            if (invalidCredentials) throttle.failed(reservation);
            else throttle.succeeded(reservation);
        }
    }

    public AccountView authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw ApiException.unauthenticated();
        String subject = sessions.find(TokenHash.sha256(token));
        if (subject == null) throw ApiException.unauthenticated();
        String[] parts = subject.split(":", -1);
        if (parts.length != 2) throw ApiException.unauthenticated();
        AccountRow row = accounts.findById(parts[0]);
        if (row == null || !row.enabled() || !Long.toString(row.version()).equals(parts[1])) throw ApiException.unauthenticated();
        // Read current grants from MySQL on every request. Customers can never inherit staff grants.
        if (row.accountType() == AccountType.CUSTOMER)
            return new AccountView(row.id(), row.username(), row.displayName(), row.accountType(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), true, row.version());
        return new AccountView(row.id(), row.username(), row.displayName(), row.accountType(),
            accounts.permissions(row.id()), accounts.warehouses(row.id()), accounts.roles(row.id()), true, row.version());
    }

    public void logout(String token) { sessions.remove(TokenHash.sha256(token)); }
    public record Token(String accessToken, String tokenType, Instant expiresAt) {
        @Override public String toString() { return "Token[REDACTED]"; }
    }
}
