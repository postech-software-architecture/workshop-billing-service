package com.postech.foundation;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

public interface Identity {

	List<String> roles(Jwt jwt);

}
