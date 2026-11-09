package com.postech.foundation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only enabled by the explicitly selected local fault-test override. No HTTP switch. */
@Component
public class Faults {

	private final boolean enabled;

	public Faults(@Value("${foundation.faults:false}") boolean enabled) {
		this.enabled = enabled;
	}

	public void check(String name) throws IOException {
		if (enabled && Files.deleteIfExists(Path.of("/tmp/foundation-" + name)))
			Runtime.getRuntime().halt(77);
	}

}
