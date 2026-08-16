// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Applies validated RFC 6902 operations to immutable framework-owned JSON values. */
public final class AGUIJsonPatch {
    /** Default maximum operations accepted by one patch application. */
    public static final int DEFAULT_MAX_OPERATIONS = 1_000;

    private AGUIJsonPatch() {}

    /**
     * Applies a bounded patch atomically and returns the resulting immutable value.
     *
     * @param document source document
     * @param operations ordered operations
     * @return patched document
     */
    public static StateValue apply(StateValue document, List<AGUIJsonPatchOperation> operations) {
        return apply(document, operations, DEFAULT_MAX_OPERATIONS);
    }

    /**
     * Applies a patch with an explicit positive operation bound.
     *
     * @param document source document
     * @param operations ordered operations
     * @param maxOperations maximum accepted operations
     * @return patched document
     */
    public static StateValue apply(StateValue document, List<AGUIJsonPatchOperation> operations, int maxOperations) {
        java.util.Objects.requireNonNull(document, "document");
        List<AGUIJsonPatchOperation> checked = AGUIValidation.list(operations, "operations");
        if (maxOperations <= 0 || checked.size() > maxOperations) {
            throw AGUIJsonPointer.invalid("JSON Patch exceeds its operation limit.");
        }
        StateValue current = document;
        for (AGUIJsonPatchOperation operation : checked) {
            current = applyOne(current, operation);
        }
        return current;
    }

    private static StateValue applyOne(StateValue document, AGUIJsonPatchOperation operation) {
        List<String> path = AGUIJsonPointer.parse(operation.path());
        return switch (operation.op()) {
            case ADD -> set(document, path, operation.value(), Mode.ADD);
            case REMOVE -> remove(document, path);
            case REPLACE -> set(document, path, operation.value(), Mode.REPLACE);
            case COPY -> set(document, path, get(document, AGUIJsonPointer.parse(operation.from())), Mode.ADD);
            case MOVE -> move(document, AGUIJsonPointer.parse(operation.from()), path);
            case TEST -> {
                if (!get(document, path).equals(operation.value())) {
                    throw AGUIJsonPointer.invalid("JSON Patch test operation failed.");
                }
                yield document;
            }
        };
    }

    private static StateValue move(StateValue document, List<String> from, List<String> path) {
        if (from.equals(path)) {
            return document;
        }
        if (path.size() > from.size() && path.subList(0, from.size()).equals(from)) {
            throw AGUIJsonPointer.invalid("JSON Patch move target must not be a child of its source.");
        }
        if (from.isEmpty()) {
            throw AGUIJsonPointer.invalid("JSON Patch cannot move the document root.");
        }
        StateValue value = get(document, from);
        return set(remove(document, from), path, value, Mode.ADD);
    }

    private static StateValue get(StateValue current, List<String> path) {
        StateValue value = current;
        for (String segment : path) {
            value = child(value, segment, false);
        }
        return value;
    }

    private static StateValue child(StateValue value, String segment, boolean allowAppend) {
        return switch (value) {
            case StateValue.ObjectValue object -> {
                StateValue child = object.values().get(segment);
                if (child == null) {
                    throw AGUIJsonPointer.invalid("JSON Pointer references an absent object member.");
                }
                yield child;
            }
            case StateValue.ArrayValue array -> {
                int index = index(segment, array.values().size(), allowAppend);
                if (index == array.values().size()) {
                    throw AGUIJsonPointer.invalid("JSON Pointer append token has no readable value.");
                }
                yield array.values().get(index);
            }
            default -> throw AGUIJsonPointer.invalid("JSON Pointer traverses a scalar value.");
        };
    }

    private static StateValue set(StateValue current, List<String> path, StateValue value, Mode mode) {
        if (path.isEmpty()) {
            if (mode == Mode.REPLACE || mode == Mode.ADD) {
                return value;
            }
            throw AGUIJsonPointer.invalid("Unsupported root patch operation.");
        }
        return setRecursive(current, path, 0, value, mode);
    }

    private static StateValue setRecursive(
            StateValue current, List<String> path, int offset, StateValue value, Mode mode) {
        String segment = path.get(offset);
        boolean leaf = offset == path.size() - 1;
        return switch (current) {
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>(object.values());
                if (leaf) {
                    if (mode == Mode.REPLACE && !copy.containsKey(segment)) {
                        throw AGUIJsonPointer.invalid("JSON Patch replace target is absent.");
                    }
                    copy.put(segment, value);
                } else {
                    StateValue child = copy.get(segment);
                    if (child == null) {
                        throw AGUIJsonPointer.invalid("JSON Pointer references an absent object member.");
                    }
                    copy.put(segment, setRecursive(child, path, offset + 1, value, mode));
                }
                yield StateValue.object(copy);
            }
            case StateValue.ArrayValue array -> {
                ArrayList<StateValue> copy = new ArrayList<>(array.values());
                int target = index(segment, copy.size(), leaf && mode == Mode.ADD);
                if (leaf) {
                    if (mode == Mode.ADD) {
                        copy.add(target, value);
                    } else {
                        if (target == copy.size()) {
                            throw AGUIJsonPointer.invalid("JSON Patch replace target is absent.");
                        }
                        copy.set(target, value);
                    }
                } else {
                    if (target == copy.size()) {
                        throw AGUIJsonPointer.invalid("JSON Pointer append token is invalid before the leaf.");
                    }
                    copy.set(target, setRecursive(copy.get(target), path, offset + 1, value, mode));
                }
                yield StateValue.array(copy);
            }
            default -> throw AGUIJsonPointer.invalid("JSON Pointer traverses a scalar value.");
        };
    }

    private static StateValue remove(StateValue current, List<String> path) {
        if (path.isEmpty()) {
            throw AGUIJsonPointer.invalid("JSON Patch cannot remove the document root.");
        }
        return removeRecursive(current, path, 0);
    }

    private static StateValue removeRecursive(StateValue current, List<String> path, int offset) {
        String segment = path.get(offset);
        boolean leaf = offset == path.size() - 1;
        return switch (current) {
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>(object.values());
                StateValue child = copy.get(segment);
                if (child == null) {
                    throw AGUIJsonPointer.invalid("JSON Patch remove target is absent.");
                }
                if (leaf) {
                    copy.remove(segment);
                } else {
                    copy.put(segment, removeRecursive(child, path, offset + 1));
                }
                yield StateValue.object(copy);
            }
            case StateValue.ArrayValue array -> {
                ArrayList<StateValue> copy = new ArrayList<>(array.values());
                int target = index(segment, copy.size(), false);
                if (leaf) {
                    copy.remove(target);
                } else {
                    copy.set(target, removeRecursive(copy.get(target), path, offset + 1));
                }
                yield StateValue.array(copy);
            }
            default -> throw AGUIJsonPointer.invalid("JSON Pointer traverses a scalar value.");
        };
    }

    private static int index(String segment, int size, boolean allowAppend) {
        if ("-".equals(segment)) {
            if (allowAppend) {
                return size;
            }
            throw AGUIJsonPointer.invalid("JSON Pointer append token is not valid here.");
        }
        if (segment.isEmpty() || segment.length() > 1 && segment.charAt(0) == '0') {
            throw AGUIJsonPointer.invalid("JSON Pointer array index is not canonical.");
        }
        for (int index = 0; index < segment.length(); index++) {
            if (!Character.isDigit(segment.charAt(index))) {
                throw AGUIJsonPointer.invalid("JSON Pointer array index is invalid.");
            }
        }
        try {
            int value = Integer.parseInt(segment);
            if (value < 0 || value > size || value == size && !allowAppend) {
                throw AGUIJsonPointer.invalid("JSON Pointer array index is out of bounds.");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw AGUIJsonPointer.invalid("JSON Pointer array index is out of range.");
        }
    }

    private enum Mode {
        ADD,
        REPLACE
    }
}
