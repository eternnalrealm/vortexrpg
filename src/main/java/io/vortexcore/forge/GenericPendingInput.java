package io.vortexcore.forge;

/**
 * Generic pending chat input record used by all forge services.
 *
 * @param <T> the editable entity type (EditableSpell, EditableEffect, EditableItem)
 * @param <F> the ChatField enum type specific to each forge
 */
public record GenericPendingInput<T, F extends Enum<F>>(T entity, F field) {}
