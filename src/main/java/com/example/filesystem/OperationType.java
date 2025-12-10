package com.example.filesystem;

enum OperationType {
    CREATE_FILE_WITH_CONTENT(2),
    CREATE_FILE(1),
    WRITE_FILE(3),
    COPY_FILE(2),
    DELETE_FILE(1),
    RENAME_FILE(2),

    CREATE_DIRECTORY(1),
    DELETE_DIRECTORY(2),
    RENAME_DIRECTORY(2),

    BEGIN_TRANSACTION(1),
    COMMIT_TRANSACTION(1),
    ABORT_TRANSACTION(1),
    CHECKPOINT(1);

    private final int argumentCount;

    OperationType(int argumentCount) {
        this.argumentCount = argumentCount;
    }

    int getArgumentCount() {
        return argumentCount;
    }
}
