package org.cloudfoundry.promregator.cfaccessor;

//import io.prometheus.client.Histogram.Timer;
//import org.cloudfoundry.promregator.internalmetrics.InternalMetrics;

public class ReactiveTimer {
//	private Timer t;
//	private final InternalMetrics im;
	private final String requestType;
	
	public ReactiveTimer(final String requestType) {
		this.requestType = requestType;
	}
	
	public void start() {
//		this.t = this.im.startTimerCFFetch(this.requestType);
	}

	public void stop() {
//		if (this.t != null) {
//			this.t.observeDuration();
//		}
	}
}