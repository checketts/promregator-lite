package com.github.promregator.config;

public final class EndpointConstants {
	private EndpointConstants() {
		throw new IllegalStateException("Should never be called");
	}
	
	public static final String ENDPOINT_PATH_DISCOVERY = "/discovery";
	public static final String ENDPOINT_PATH_SINGLE_ENDPOINT_SCRAPING = "/metrics";
	public static final String ENDPOINT_PATH_SINGLE_TARGET_SCRAPING = "/singleTargetMetrics";
	public static final String ENDPOINT_PATH_PROMREGATOR_METRICS = "/promregatorMetrics";
	public static final String ENDPOINT_PATH_CACHE_INVALIDATION = "/cache/invalidate";
	
	public static final String HTTP_HEADER_PROMREGATOR_INSTANCE_IDENTIFIER = "X-Promregator-Instance";
}
