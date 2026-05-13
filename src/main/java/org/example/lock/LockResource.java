package org.example.lock;

/**
 * Centralized registry for all distributed locks.
 * Prevents typos, survives refactoring/blue-green deployments, and provides a single place
 * to view all system locks.
 */
public enum LockResource {
    
    // Example entity-level locks
    ORDER_PROCESSING("order:process"),
    INVENTORY_ALLOCATION("inventory:allocate"),
    ASSET_UPDATE("asset:update"),

    // Example global/process-level locks
    DAILY_REPORT_GENERATION("report:daily"),
    SYSTEM_SYNC("system:sync");

    private final String namespace;

    LockResource(String namespace) {
        this.namespace = namespace;
    }

    public String getNamespace() {
        return namespace;
    }
}
