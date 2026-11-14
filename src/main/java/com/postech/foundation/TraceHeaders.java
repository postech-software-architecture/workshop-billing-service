package com.postech.foundation;

import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

@Component
public class TraceHeaders {

	private final Tracer tracer;

	public TraceHeaders(Tracer tracer) {
		this.tracer = tracer;
	}

	public String current() {
		var span = tracer.currentSpan();
		if (span == null)
			return null;
		var c = span.context();
		return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.TRUE.equals(c.sampled()) ? "01" : "00");
	}

}
