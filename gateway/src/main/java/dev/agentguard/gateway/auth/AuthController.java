package dev.agentguard.gateway.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues the token a user hands to their agent. The agent sends it on every
 * MCP call, so the gateway always knows which human is behind a tool call.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final String dummyHash;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder,
                          JwtEncoder encoder, JwtProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.encoder = encoder;
        this.props = props;
        // Compared against when the username is unknown, so both failures take the same time.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        Optional<AppUser> found = users.findByUsername(request.username());
        boolean matches = passwordEncoder.matches(request.password(),
            found.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches || !found.get().isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        return issue(found.get());
    }

    private TokenResponse issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.ttl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer(props.issuer())
            .subject(user.getUsername())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(UserIdentity.CLAIM_USER_ID, user.getId())
            .claim(UserIdentity.CLAIM_ROLE, user.getRole().name());
        if (user.getDepartment() != null) {
            claims.claim(UserIdentity.CLAIM_DEPARTMENT, user.getDepartment());
        }
        if (user.getHospitalStaffId() != null) {
            claims.claim(UserIdentity.CLAIM_STAFF_ID, user.getHospitalStaffId());
        }
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return new TokenResponse(token, "Bearer", expiresAt);
    }

    public record LoginRequest(@NotBlank @Size(max = 60) String username,
                               @NotBlank @Size(max = 200) String password) {}

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {}
}
