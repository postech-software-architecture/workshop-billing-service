package com.postech.foundation;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

class SecurityTest {

	private static final String SECRET = "local-test-key-only-not-a-secret-".repeat(2);

	String token(String issuer, String audience, String subject, int expires, JWSAlgorithm algorithm) throws Exception {
		var claims = new JWTClaimsSet.Builder().issuer(issuer)
			.audience(audience)
			.subject(subject)
			.issueTime(Date.from(Instant.now()))
			.expirationTime(Date.from(Instant.now().plusSeconds(expires)))
			.build();
		var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
		jwt.sign(new MACSigner(SECRET));
		return jwt.serialize();
	}

	@Test
	void acceptsCompatibleTokenAndRejectsInvalidTrustBoundary() throws Exception {
		var decoder = new Security().decoder(SECRET);
		String id = "11111111-1111-4111-8111-111111111111";
		assertEquals(id,
				decoder.decode(token("workshop-auth", "workshop-service", id, 600, JWSAlgorithm.HS256)).getSubject());
		assertThrows(JwtException.class,
				() -> decoder.decode(token("wrong", "workshop-service", id, 600, JWSAlgorithm.HS256)));
		assertThrows(JwtException.class,
				() -> decoder.decode(token("workshop-auth", "wrong", id, 600, JWSAlgorithm.HS256)));
		assertThrows(JwtException.class,
				() -> decoder.decode(token("workshop-auth", "workshop-service", id, -120, JWSAlgorithm.HS256)));
		assertThrows(JwtException.class,
				() -> decoder.decode(token("workshop-auth", "workshop-service", "not-uuid", 600, JWSAlgorithm.HS256)));
		assertThrows(JwtException.class,
				() -> decoder.decode(token("workshop-auth", "workshop-service", null, 600, JWSAlgorithm.HS256)));
		assertThrows(JwtException.class,
				() -> decoder.decode(token("workshop-auth", null, id, 600, JWSAlgorithm.HS256)));
		assertThrows(JwtException.class,
				() -> decoder.decode(token("workshop-auth", "workshop-service", id, 600, JWSAlgorithm.HS512)));
		assertThrows(JwtException.class, () -> decoder.decode("invalid"));
		assertThrows(IllegalArgumentException.class, () -> new Security().decoder("short"));
	}

}
