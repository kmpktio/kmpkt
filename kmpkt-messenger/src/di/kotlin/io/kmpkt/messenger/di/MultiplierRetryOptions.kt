package io.kmpkt.messenger.di

import io.kmpkt.di.compiler.literal
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import kotlin.time.Duration

// What `retry<MultiplierRetryStrategy> { … }` may set: the strategy's constructor parameters, by their names.
// A property that is not set is not passed, so the default stays where it is declared - in the strategy.

/** How often a failed message is tried again. */
public var StrategyOptions<MultiplierRetryStrategy>.maxRetries: Int
    get() = get("maxRetries")
    set(value) = set("maxRetries", value, literal(value))

/** The wait before the first retry. */
public var StrategyOptions<MultiplierRetryStrategy>.delay: Duration
    get() = get("delay")
    set(value) = set("delay", value, literal(value))

/** How much longer each wait is than the one before. */
public var StrategyOptions<MultiplierRetryStrategy>.multiplier: Double
    get() = get("multiplier")
    set(value) = set("multiplier", value, literal(value))

/** The longest a wait may get; `Duration.ZERO` for no limit. */
public var StrategyOptions<MultiplierRetryStrategy>.maxDelay: Duration
    get() = get("maxDelay")
    set(value) = set("maxDelay", value, literal(value))

/** The fraction of a wait, `0.0..1.0`, it is randomly spread by either way. */
public var StrategyOptions<MultiplierRetryStrategy>.jitter: Double
    get() = get("jitter")
    set(value) = set("jitter", value, literal(value))
