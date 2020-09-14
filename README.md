### Connect Proxy

Implementation of a proxy server using [HTTP CONNECT](https://tools.ietf.org/html/rfc7231#section-4.3.6) interface. Use cases include bypassing network restrictions and anonymizing clients.

The possible destination hosts are currently protected by a whitelist.

To run with default options (port=8443 and whitelist=\[localhost\]), execute on CLI:

```
./gradlew run
```

The default options can be changed:

```
./gradlew run --args "8443 wikipedia.org netty.io"
```

Now, port 8443 is still being used and two sites are whitelisted (wikipedia.org, netty.io).

Once the server is running, `curl` could be used to test the server. For example:

```
curl -v --proxy-insecure --proxy "https://localhost:8443" -I https://wikipedia.org
```

## Pending

Some desirable future improvements:

- Support for non-self-signed SSL certificates.
- Usage of wildcards in whitelist.
- Configurable using files.
- Support configuration for threads, buffers, network queues.