package com.davidneto.homepage.cameras;

import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.security.RememberDeviceServices;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/cameras/auth")
public class CameraAuthController {

    public record TokenRequest(String username, String password, String label) {
    }

    private final CameraTokenService tokens;
    private final UserDetailsService userDetails;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final RememberDeviceServices rememberDevice;
    /** Hash used for unknown usernames so both paths cost one BCrypt check. */
    private final String dummyHash;

    public CameraAuthController(CameraTokenService tokens, UserDetailsService userDetails, PasswordEncoder encoder,
                                LoginRateLimiter limiter, RememberDeviceServices rememberDevice) {
        this.tokens = tokens;
        this.rememberDevice = rememberDevice;
        this.userDetails = userDetails;
        this.encoder = encoder;
        this.limiter = limiter;
        this.dummyHash = encoder.encode("timing-equaliser");
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody TokenRequest body, HttpServletRequest request) {
        String username = body.username() == null ? "" : body.username();
        String ip = request.getRemoteAddr();
        LoginRateLimiter.Decision d = limiter.check(ip, username);
        if (d.kind() != LoginRateLimiter.DecisionKind.ALLOW) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(d.retryAfterSeconds()))
                    .body(Map.of("error", "too many attempts - try again later"));
        }
        if (!credentialsValid(username, body.password())) {
            limiter.recordFailure(ip, username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "wrong username or password"));
        }
        limiter.recordSuccess(username);
        CameraTokenService.Issued issued = tokens.issue(body.label());
        return ResponseEntity.ok(Map.of("token", issued.token(), "expiresAt", issued.expiresAt().toString()));
    }

    @DeleteMapping("/token")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        tokens.revoke(CameraTokenService.bearer(authorization));
        return ResponseEntity.noContent().build();
    }

    /** "Sign out all devices": every app token, every remembered browser and the sessions they signed in. */
    @PostMapping("/revoke-all")
    public Map<String, Integer> revokeAll(HttpServletRequest request) {
        return Map.of("revoked", tokens.revokeAll(), "devices", rememberDevice.forgetAll(request));
    }

    private boolean credentialsValid(String username, String password) {
        if (password == null) return false;
        try {
            UserDetails u = userDetails.loadUserByUsername(username);
            return encoder.matches(password, u.getPassword());
        } catch (UsernameNotFoundException e) {
            encoder.matches(password, dummyHash); // equal timing for unknown users
            return false;
        }
    }
}
