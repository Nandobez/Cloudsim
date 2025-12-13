package com.example.filesystem;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

class JournalEntry {
    private static final String DELIMITER = "|";

    private final Instant timestamp;
    private final OperationType type;
    private final List<String> arguments;

    JournalEntry(OperationType type, List<String> arguments) {
        this(Instant.now(), type, arguments);
    }

    JournalEntry(Instant timestamp, OperationType type, List<String> arguments) {
        this.timestamp = timestamp;
        this.type = type;
        this.arguments = new ArrayList<>(arguments);
    }

    Instant getTimestamp() {
        return timestamp;
    }

    OperationType getType() {
        return type;
    }

    List<String> getArguments() {
        return Collections.unmodifiableList(arguments);
    }

    String serialize() {
        StringBuilder builder = new StringBuilder();
        builder.append(timestamp).append(DELIMITER)
               .append(type.name()).append(DELIMITER)
               .append(arguments.size());
        for (String argument : arguments) {
            builder.append(DELIMITER).append(encode(argument));
        }
        return builder.toString();
    }

    static JournalEntry deserialize(String line) {
        String[] rawParts = line.split("\\" + DELIMITER, -1);
        if (rawParts.length < 3) {
            throw new IllegalArgumentException("Linha invalida no journal: " + line);
        }
        Instant timestamp = Instant.parse(rawParts[0]);
        OperationType type = OperationType.valueOf(rawParts[1]);
        int argCount = Integer.parseInt(rawParts[2]);
        List<String> args = new ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            int index = 3 + i;
            if (index < rawParts.length) {
                args.add(decode(rawParts[index]));
            } else {
                args.add("");
            }
        }
        return new JournalEntry(timestamp, type, args);
    }

    private static String encode(String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
