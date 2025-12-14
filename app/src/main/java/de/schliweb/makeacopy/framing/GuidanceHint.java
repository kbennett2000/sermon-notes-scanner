package de.schliweb.makeacopy.framing;

/**
 * Enumeration representing various guidance hints.
 * This enum defines a set of directional or positional guidance hints
 * that can be used to indicate desired movements or adjustments.
 * <p>
 * The hints include cardinal direction movements, proximity adjustments,
 * and tilting motions to guide an entity to a specific state or position.
 */
public enum GuidanceHint {
    OK,
    MOVE_LEFT, MOVE_RIGHT,
    MOVE_UP, MOVE_DOWN,
    MOVE_CLOSER, MOVE_BACK,
    TILT_LEFT, TILT_RIGHT,
    TILT_FORWARD, TILT_BACK,
    NO_DOCUMENT_DETECTED,
    ORIENTATION_PORTRAIT_TIP,
    ORIENTATION_LANDSCAPE_TIP
}
