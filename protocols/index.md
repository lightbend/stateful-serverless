# CloudState Documentation and Specification format rules

The CloudState Protocol Specification MUST use [RFC-2119](https://www.ietf.org/rfc/rfc2119.txt) definitions.

Use the following formatting and Markdown syntax to define documentation and specification of the CloudState Protocol.
The template/example below is proven to work well with the `protoc-gen-doc` plugin, so please stick to it 100%.

```
//The *EntityDiscovery* Service is the entrypoint for CloudState Proxies
//
//---
//
//`Specification`:
//
//  - A `User Function` MUST implement EntityDiscovery
//  - `discover`
//    - *MUST* be invoked with a *valid* `ProxyInfo`
//    - *MUST* be *idempotent*
//    - *MAY* be called any number of times by its CloudState Proxy
//    - *MUST* yield a failure if the provided `ProxyInfo` is not deemed compatible with the User Function
//  - `reportError`
//    - *MUST* only be invoked to inform a User Function about violations of the CloudState Specification
//    - *MUST* only receive failures not originating from invoking `reportError`, since that would cause a crash-loop.
//    - implementations are *RECOMMENDED* to log the received errors, for debugging purposes.
service EntityDiscovery {

    // Lets a CloudState Proxy discover which `service` and `entities` a User Function implements.
    rpc discover(ProxyInfo) returns (EntitySpec) {}

    // Lets a CloudState Proxy inform a User Function about specification violations.
    rpc reportError(UserFunctionError) returns (google.protobuf.Empty) {}
}
```