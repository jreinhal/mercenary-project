package com.jreinhal.mercenary.constant;

/**
 * Centralized metadata key and value constants for vector store documents.
 */
public final class MetadataConstants {

    private MetadataConstants() {
    } // Prevent instantiation

    // Keys
    public static final String STATUS_KEY = "status";
    public static final String DEPT_KEY = "department";
    public static final String SOURCE_KEY = "source";
    public static final String EVOLUTION_NOTE_KEY = "evolution_note";

    // Values
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
}
