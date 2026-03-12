package com.nanoexchange.engine;

/**
 * Callback surface for matching-engine output. The engine invokes this for every execution
 * report it produces. Implementations must not retain the {@link ExecutionReport} reference
 * past the callback return — the engine reuses the instance from a pool.
 */
@FunctionalInterface
public interface ExecutionSink {

    /** Handle one report. Copy any fields that need to outlive this call. */
    void onExecutionReport(ExecutionReport report);
}
