package com.postech.foundation;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.postech.foundation")
public class Errors {

	@ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
	ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException error) {
		return ResponseEntity.status(error.getStatusCode())
			.body(Map.of("code", error.getReason() == null ? "REQUEST_REJECTED" : error.getReason()));
	}

	@ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
	ResponseEntity<?> conflict() {
		return ResponseEntity.status(409).body(Map.of("code", "CONCURRENT_CHANGE"));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<?> invalid(IllegalArgumentException error) {
		return ResponseEntity.unprocessableEntity().body(Map.of("code", "INVALID_OPERATION"));
	}

	@ExceptionHandler(org.springframework.dao.DataAccessException.class)
	ResponseEntity<?> dependency() {
		return ResponseEntity.status(503).body(Map.of("code", "DEPENDENCY_UNAVAILABLE"));
	}

}
