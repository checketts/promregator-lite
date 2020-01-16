# promregator-lite

This is a very simplified version of Promregator

This alters some design decisions around Promregator.

## Main differences in Promregator Lite:

* WebFlux/Netty as the runtime
* No built in cache. 
    * Instead the calls to the mono/fluxes from the CF API are reused (`.cache()` on the `Mono`s)
    * For single target scraping the scrape target is encoded into the url (via a base64 encoded json payload)  I might switch it to a signed payload (JWT) but for now it isn't
* There is no label enrichment. That all happens in the Prometheus relabeling
* The `promregator_up` metric is gone. Instead the `singleTargetScraping` endpoint returns an error if the underlying service also errors.
* Added micrometer metrics to get better insight into what was making all the calls and how it is behaving
