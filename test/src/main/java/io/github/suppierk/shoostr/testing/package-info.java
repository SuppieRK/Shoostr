/**
 * Framework-independent real-HTTP testing for an existing application. The fixture owns the
 * supplied app and its client, not caller dependencies. Requests use raw bytes or an explicit JDK
 * body handler; assertions and scoped/shared fixture lifetimes belong to the caller's testing
 * framework.
 */
@org.jspecify.annotations.NullMarked
package io.github.suppierk.shoostr.testing;
