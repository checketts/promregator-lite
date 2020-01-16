package org.cloudfoundry.promregator.cfaccessor;

import org.cloudfoundry.client.v2.PaginatedResponse;

import java.util.List;

@FunctionalInterface
public interface PaginatedResponseGeneratorFunction<S, T extends PaginatedResponse<?>> {
	// for the idea, see also https://stackoverflow.com/a/27872395 
	T apply(List<S> data, int numberOfPages);
}
