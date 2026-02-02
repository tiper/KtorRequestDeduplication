# Ktor Client Request Deduplication

A Kotlin Multiplatform library that prevents duplicate concurrent HTTP requests in Ktor clients.

When multiple components request the same resource simultaneously, only one actual HTTP request is executed, and all callers receive the same response. This optimizes network usage and reduces server load.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.tiper/ktor-client-deduplication)](https://central.sonatype.com/artifact/io.github.tiper/ktor-client-deduplication)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.20-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Ktor](https://img.shields.io/badge/ktor-2.3.0+-orange.svg)](https://ktor.io)

## Supported Platforms

- ✅ JVM (Java 8+)
- ✅ Android (API 21+)
- ✅ iOS (arm64, x64, simulatorArm64)
- ✅ macOS (x64, arm64)
- ✅ Linux (x64)
- ✅ JavaScript (Browser, Node.js)

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    // For Ktor 2.x
    implementation("io.github.tiper:ktor-client-deduplication:2.0.0")

    // For Ktor 3.x (coming soon)
    // implementation("io.github.tiper:ktor-client-deduplication:3.0.0")
}
```

### Version Compatibility

This library uses semantic versioning aligned with Ktor major versions:

| Plugin Version | Ktor Version | Support |
|---|---|---|
| 2.x.x | 2.3.0+ | ✅ Current |
| 3.x.x | 3.0.0+ | 🔄 Planned |

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

### ⚠️ Important: Plugin Installation Order

**This plugin MUST be installed LAST** (or at least after any plugins that modify requests).

Plugins like `Auth`, `DefaultRequest`, and custom header plugins must be installed **before** `RequestDeduplication` to ensure their modifications (tokens, headers, etc.) are included in the deduplicated request.

**Correct installation order:**
```kotlin
val client = HttpClient {
    install(Auth) {
        bearer {
            loadTokens { ... }
        }
    }
    install(DefaultRequest) {
        header("User-Agent", "MyApp/1.0")
    }
    install(RequestDeduplication)  // Install LAST ✅
}
```

### Advanced Configuration

```kotlin
val client = HttpClient {
    install(RequestDeduplication) {
        // Deduplicate both GET and HEAD requests, or add POST if needed
        deduplicateMethods = setOf(HttpMethod.Get, HttpMethod.Head)
        // You can also add HttpMethod.Post if you want POST deduplication
        // excludeHeaders: exclude tracing/telemetry headers from cache key computation
        excludeHeaders = setOf(
            "X-Trace-Id",
            "X-Request-Id",
            "X-Correlation-Id",
            "traceparent",
            "tracestate"
        )
    }
}
```

## How It Works

1. **Request Interception**: When a request is made, the plugin generates a cache key from the HTTP method, URL, and headers (excluding those in `excludeHeaders`).
2. **Deduplication Check**: If another request with the same cache key is in-flight, the plugin waits for it to complete. Deduplication only applies to concurrent requests; sequential requests are not deduplicated.
3. **Response Sharing**: The first request's response body is read once into memory and shared with all waiting callers (including error responses). The response is **not cached** - it's only shared during the concurrent execution window.
4. **Cleanup**: After all concurrent callers complete, the shared response is released and the entry is removed. Subsequent requests will trigger new HTTP calls.

### Cache Key Generation

The cache key is built from:
- HTTP method (GET, POST, etc.)
- Full URL including query parameters
- Request headers (excluding those in `excludeHeaders`)

Headers are combined using **polynomial rolling hash** to ensure order-independence and collision resistance:
```
GET:https://api.example.com/users?id=123|h=1847563829
```

## Configuration Options

### `deduplicateMethods`

**Type:** `Set<HttpMethod>`
**Default:** `setOf(HttpMethod.Get)`

HTTP methods to deduplicate. Typically you only want to deduplicate idempotent methods (GET, HEAD). You can add POST if your use case allows.

```kotlin
deduplicateMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Post)
```

### `excludeHeaders`

**Type:** `Set<String>`
**Default:** `emptySet()`

Headers to exclude from cache key computation (case-sensitive). This is crucial for preventing tracing/telemetry headers from breaking deduplication.

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

**⚠️ Important:** Header matching is case-sensitive. Make sure the casing matches exactly how your SDK sends the headers.

## Debugging

If deduplication isn't working as expected, check the actual header names sent by your SDK:

```kotlin
val client = HttpClient {
    install(Logging) {
        level = LogLevel.HEADERS
    }
    install(RequestDeduplication) {
        // Add headers after verifying their exact casing
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

- Large file downloads (entire response loaded into memory)
- Streaming responses
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
- Very large files (>10MB): ⚠️ Still loads entire response into memory
- Mobile devices: ✅ Memory-efficient due to sharing
- Streaming responses: ❌ Not supported (entire body must be loaded)

### Thread Safety

The implementation is thread-safe and uses Kotlin's `Mutex` for synchronization. It's safe to use with concurrent coroutines across all platforms.

### Performance

- **Cache key generation:** O(n) where n = number of headers
- **Deduplication check:** O(1) HashMap lookup
- **Overhead:** Negligible compared to network latency (~microseconds vs milliseconds)

### Error Responses

Error responses (e.g., 404, 500) are also deduplicated and shared with all waiting callers.

### Cancellation Behavior

The plugin handles cancellation gracefully to ensure robustness:

**Individual caller cancellation:**
- If one caller cancels, other concurrent callers **continue normally** and receive the response
- The cancelled caller gets a `CancellationException` as expected
- This is achieved using a supervisor scope for the in-flight request

**All callers cancel:**
- If **all** concurrent callers cancel before the response arrives, the in-flight HTTP request is **cancelled automatically**
- This saves network bandwidth and server resources
- The plugin tracks active waiters and cancels the request when the count reaches zero

**Example:**
```kotlin
// Caller 1 starts request
launch { client.get("https://api.example.com/data") }

// Caller 2 joins (waits for same request)
val job = launch { client.get("https://api.example.com/data") }

// Caller 2 cancels
job.cancel()  // ✅ Caller 2 is cancelled, Caller 1 still gets response

// If both cancel before response arrives, the HTTP request is cancelled
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

Plugin versions are aligned with Ktor major versions for clarity:

| Component | Minimum Version | Notes |
|-----------|----------------|-------|
| Plugin | 2.0.0+ | Version 2.x for Ktor 2.x; Version 3.x for Ktor 3.x |
| Kotlin | 1.9.20+ | |
| Ktor | 2.3.0+ (3.0.0+ for v3.x) | See version table above |
| Android | API 21+ | |
| JVM | Java 8+ | |

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
