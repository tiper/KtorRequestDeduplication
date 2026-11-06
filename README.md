# Ktor Client Request Deduplication

A Kotlin Multiplatform library that prevents duplicate concurrent HTTP requests in Ktor clients.

When multiple components request the same resource simultaneously, only one actual HTTP request is executed, and all callers receive the same response. This optimizes network usage and reduces server load.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.tiper/ktor-client-deduplication)](https://central.sonatype.com/artifact/io.github.tiper/ktor-client-deduplication)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.20-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Ktor](https://img.shields.io/badge/ktor-3.0.0+-orange.svg)](https://ktor.io)

## Supported Platforms

### Version 2.x.x (Ktor 2.3.0+)

- ✅ JVM (Java 8+)
- ✅ Android (API 21+)
- ✅ iOS (arm64, x64, simulatorArm64)
- ✅ macOS (x64, arm64)
- ✅ Linux (x64)
- ✅ JavaScript (Browser, Node.js)

### Version 3.x.x (Ktor 3.0.0+)

All platforms from v2.x.x plus:

- ✅ Android Native (arm32, arm64, x86, x64)
- ✅ tvOS (x64, arm64, simulatorArm64)
- ✅ watchOS (arm32, arm64, x64, simulatorArm64, deviceArm64)
- ✅ Linux ARM64
- ✅ Windows (mingwX64)
- ✅ WebAssembly (wasmJs) - Browser and Node.js

### ⚠️ IMPORTANT: Choose the correct version for your Ktor version!

| If you use Ktor | Use plugin version |
|---|---|
| **2.3.0** to **2.x.x** | **2.x.x** ← Use this |
| **3.0.0+** | **3.x.x** ← Use this |

## Installation

### Choose the version matching your Ktor version:

#### For Ktor 2.3.0+

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.tiper:ktor-client-deduplication:2.x.x")
}
```

#### For Ktor 3.0.0+

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.tiper:ktor-client-deduplication:3.x.x")
}
```

## Quick Start

### Basic Usage

```kotlin
val client = HttpClient {
    install(RequestDeduplication)
}

// Multiple concurrent GET requests to the same URL
launch { client.get("https://api.example.com/users") }
launch { client.get("https://api.example.com/users") }
launch { client.get("https://api.example.com/users") }

// Result: Only ONE actual HTTP request is made!
// All three callers receive the same shared response
```

### Plugin Installation Order

Most plugins change the request before it reaches the Send step, where deduplication happens. Their changes are **always part of the cache key, wherever they are installed**. This covers `DefaultRequest`, `Auth`, and any plugin using `onRequest`. To stop such a header from affecting deduplication, add it to `excludeHeaders`.

Order only matters for plugins that change the request in `on(Send)`:

- **Installed before RequestDeduplication**: their changes are part of the cache key.
- **Installed after RequestDeduplication**: their changes come after deduplication, so they are not part of the cache key, and only run for the request that is actually sent.

**Example:**
```kotlin
val client = HttpClient {
    install(Auth) { ... }              // In the cache key, even if installed last
    install(DefaultRequest) { ... }    // In the cache key, even if installed last

    install(BeforeDedup)               // on(Send) plugin: in the cache key
    install(RequestDeduplication)
    install(AfterDedup)                // on(Send) plugin: not in the cache key
}
```

### Advanced Configuration

```kotlin
val client = HttpClient {
    install(RequestDeduplication) {
        // Deduplicate both GET and HEAD requests
        deduplicateMethods = setOf(HttpMethod.Get, HttpMethod.Head)

        // Exclude tracing/telemetry headers from cache key computation
        excludeHeaders = setOf(
            "X-Trace-Id",
            "X-Request-Id",
            "X-Correlation-Id",
            "traceparent",
            "tracestate"
        )

        // Optional: Add minimum deduplication window for fast responses
        // Useful when error responses or cached data return very quickly
        // minWindow = 50 // milliseconds (default: 0)
    }
}
```

## How It Works

1. **Request Interception**: When a request is made, the plugin generates a cache key from the HTTP method, URL, headers (excluding those in `excludeHeaders`), and body.
2. **Deduplication Check**: If another request with the same cache key is in-flight, the plugin waits for it to complete. Deduplication only applies to concurrent requests; sequential requests are not deduplicated.
3. **Response Sharing**: The first request's response body is read once into memory and shared with all waiting callers (including error responses). The response is **not cached** - it's only shared during the concurrent execution window.
4. **Cleanup**: As soon as the response arrives, the entry is removed. Subsequent requests will trigger new HTTP calls.

### Cache Key Generation

The cache key is built from:
- HTTP method (GET, POST, etc.)
- Full URL including query parameters
- Request headers (excluding those in `excludeHeaders`)
- Request body and its `Content-Type`

The key holds the actual headers and body, not a hash of them, so requests with different credentials or bodies can never collide. Header order doesn't matter.

Only text and byte array bodies can be compared. Requests that can't be compared go straight through without deduplication:
- Streamed bodies (channels, multipart), which can only be read once
- WebSocket and Server-Sent Events requests, which open a live connection that can't be shared

## Configuration Options

### `deduplicateMethods`

**Type:** `Set<HttpMethod>`
**Default:** `setOf(HttpMethod.Get)`

HTTP methods to deduplicate. Typically you only want to deduplicate idempotent methods (GET, HEAD). You can add POST if your use case allows.

```kotlin
deduplicateMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Post)
```

### `minWindow`

**Type:** `Long` (milliseconds)
**Default:** `0` (no delay)

Minimum deduplication window (in milliseconds). This adds an artificial delay to ensure fast responses (like errors or cached responses) wait long enough for concurrent requests to join the deduplication window.

**When to use:**
- Fast error responses that complete before other concurrent requests arrive
- Cached responses that return almost instantly
- You want to maximize deduplication effectiveness for rapid requests

**Trade-off:** Higher values increase deduplication effectiveness but can add latency to fast requests by enforcing a minimum response window (slower requests are not delayed further)

**Example:**
```kotlin
install(RequestDeduplication) {
    minWindow = 50 // Wait at least 50ms before completing
}
```

**Recommended:** Start with 50-100ms if you have very fast responses and measure the impact.

### `excludeHeaders`

**Type:** `Set<String>`
**Default:** `emptySet()`

Headers to exclude from cache key computation, matched regardless of case. This is crucial for preventing tracing/telemetry headers from breaking deduplication.

**Common headers to exclude:**

| Category | Headers |
|----------|---------|
| **Distributed Tracing** | `X-Trace-Id`, `X-Request-Id`, `X-Correlation-Id` |
| **Zipkin/B3** | `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-ParentSpanId`, `X-B3-Sampled` |
| **W3C Trace Context** | `traceparent`, `tracestate` |
| **Firebase** | `X-Firebase-Locale`, `X-Firebase-Auth-Time` |
| **AWS** | `X-Amzn-Trace-Id` |
| **Google Cloud** | `X-Cloud-Trace-Context` |

**Example:**

```kotlin
excludeHeaders = setOf(
    "X-Trace-Id",
    "X-Request-Id",
    "traceparent"
)
```

### Skipping deduplication for one request

Shared responses are read fully into memory before any caller sees them. Use `skipDeduplication()` for a response that must be streamed, such as a large download:

```kotlin
client.prepareGet("https://example.com/large-file.zip") {
    skipDeduplication()
}.execute { response ->
    response.bodyAsChannel().copyTo(file)
}
```

## Debugging

If deduplication isn't working as expected, a header probably differs between requests. Log the headers to find it, then add it to `excludeHeaders`:

```kotlin
val client = HttpClient {
    install(Logging) {
        level = LogLevel.HEADERS
    }
    install(RequestDeduplication) {
        excludeHeaders = setOf("X-Custom-Header")
    }
}
```

## Use Cases

### ✅ Ideal For

- Multiple UI components loading the same data on screen initialization
- Retry logic that might trigger duplicate requests
- Race conditions in concurrent code
- Apps with aggressive data prefetching
- Reducing server load from duplicate requests

### ❌ Not Recommended For

- Large file downloads and streaming responses, unless the request calls `skipDeduplication()` (see [Skipping deduplication for one request](#skipping-deduplication-for-one-request))
- POST/PUT/PATCH requests (non-idempotent operations) unless explicitly enabled
- Requests where each call should be independent

## Important Notes

### Memory Usage

The plugin reads the response body once into memory and **shares it** (not copies) across all concurrent callers. This is highly memory-efficient:

**Memory behavior:**
- 1MB response with 10 concurrent callers = **1MB memory usage** (not 10MB)
- Response body is loaded into a single ByteArray
- All callers read from the same shared ByteArray
- Memory is freed after all concurrent callers complete

**Implications:**
- Small JSON/XML responses: ✅ No problem (e.g., 10KB × 1 = 10KB)
- Large responses with many callers: ✅ Efficient (e.g., 1MB × 1 = 1MB, not 1MB × 10)
- Very large files (>10MB): ⚠️ Still loads entire response into memory unless the request calls `skipDeduplication()`
- Mobile devices: ✅ Memory-efficient due to sharing
- Streaming responses: ❌ Not supported unless the request calls `skipDeduplication()` (entire body must be loaded)

### Thread Safety

The implementation is thread-safe and uses Kotlin's `Mutex` for synchronization. It's safe to use with concurrent coroutines across all platforms.

### Performance

- **Cache key generation:** O(n) where n = number of headers, plus the body size when comparing bodies
- **Deduplication check:** O(1) HashMap lookup
- **Overhead:** Negligible compared to network latency (~microseconds vs milliseconds)

### Error Responses

Error responses (e.g., 404, 500) are also deduplicated and shared with all waiting callers.

### Cancellation Behavior

The plugin handles cancellation gracefully to ensure robustness:

**A waiting caller cancels:**
- Other callers **continue normally** and receive the response
- The cancelled caller gets a `CancellationException` as expected

**The first caller cancels:**
- The first caller owns the HTTP request, so cancelling it **aborts that request**
- Any callers still waiting retry as a new deduplicated group, which **sends a second HTTP request**

**All callers cancel:**
- The in-flight HTTP request is **cancelled**, which saves network bandwidth and server resources

**Example:**
```kotlin
// Caller 1 starts the request
val first = launch { client.get("https://api.example.com/data") }

// Caller 2 joins (waits for the same request)
val second = launch { client.get("https://api.example.com/data") }

second.cancel()  // ✅ Caller 2 is cancelled, Caller 1 still gets the response

// If Caller 1 cancels instead, its request is aborted and Caller 2 sends a new one
```

### Reliable Testing

In unit tests, add artificial latency (e.g., `delay(100)`) in your mock handler to ensure concurrent requests overlap and deduplication is triggered reliably:

```kotlin
val client = mockClient {
    delay(100) // Simulate network latency
    requestCount++
    "response-$requestCount"
}
```

## Version Compatibility

This library uses **semantic versioning aligned with Ktor major versions**:

| Plugin Version | Ktor Version | Kotlin Version |
|---|---|----------------|
| **2.x.x** | 2.3.0+ | 1.9.20+        |
| **3.x.x** | 3.0.0+ | 2.0.20+        |

### Platform Support

Both versions support the same platforms:
- ✅ JVM (Java 8+)
- ✅ Android (API 21+)
- ✅ iOS (arm64, x64, simulatorArm64)
- ✅ macOS (x64, arm64)
- ✅ Linux (x64)
- ✅ JavaScript (Browser, Node.js)

### Additional Platforms in v3.x.x

Version 3.x additionally supports:

**Apple Ecosystem:**
- ✅ tvOS (x64, arm64, simulatorArm64)
- ✅ watchOS (arm32, arm64, x64, simulatorArm64, deviceArm64)

**Mobile:**
- ✅ Android Native (arm32, arm64, x86, x64)

**Desktop:**
- ✅ Windows (mingwX64)
- ✅ Linux ARM64

**Web:**
- ✅ WebAssembly (wasmJs) - Browser and Node.js

## License

```
Copyright 2026 Tiago Pereira

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
